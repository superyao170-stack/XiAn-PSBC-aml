"""Staged relationship extraction by node-pair category."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from datagraph_bank.llm import DeepSeekLLMClient


class RelationshipExtractor:
    """Extract one node-pair category at a time, then validate and merge."""

    STAGE_DEFINITIONS = (
        {
            "stage": "structural",
            "label": "确定性结构关系",
            "reason": "案例归属和证据来源直接来自结构化字段，不需要模型推断。",
        },
        {
            "stage": "entity_entity",
            "label": "实体—实体关系",
            "reason": "先建立持有和有明确证据的社会关系，形成稳定实体骨架。",
        },
        {
            "stage": "entity_event",
            "label": "实体—事件关系",
            "reason": "在实体骨架和事件节点确定后，区分主动参与者与客观涉及对象。",
        },
        {
            "stage": "event_event",
            "label": "事件—事件关系",
            "reason": "最后利用事件时间、业务阶段和共享主体判断直接顺承、上下位或应对关系，避免按时间机械串链。",
        },
    )

    ALLOWED_TYPES = {
        "entity_entity": {"持有关系", "社会关系"},
        "entity_event": {"参与关系", "涉及关系"},
        "event_event": {"顺承关系", "上下位关系", "应对关系"},
    }

    def __init__(
        self,
        llm_client: Optional[DeepSeekLLMClient] = None,
        prompt_paths: Optional[Dict[str, Path]] = None,
    ) -> None:
        self.llm_client = llm_client or DeepSeekLLMClient(enabled=False)
        self.prompt_paths = {
            key: Path(value)
            for key, value in (prompt_paths or {}).items()
        }
        self.prompt_templates = {
            stage: self._load_prompt(stage)
            for stage in self.ALLOWED_TYPES
        }
        self.relationship_counter = 0

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        self.relationship_counter = 0
        nodes = self._build_node_registry(state)
        candidates_by_category: Dict[str, List[Dict[str, Any]]] = {}
        call_reports: List[Dict[str, Any]] = []

        candidates_by_category["structural"] = self._structural_relationships(
            state,
            nodes,
        )
        for stage in ("entity_entity", "entity_event", "event_event"):
            relationship_id_start = (
                sum(len(items) for items in candidates_by_category.values()) + 1
            )
            stage_candidates, report = self._extract_stage(
                stage,
                state,
                nodes,
                relationship_id_start,
            )
            candidates_by_category[stage] = stage_candidates
            call_reports.append(report)

        candidates = [
            candidate
            for stage in ("structural", "entity_entity", "entity_event", "event_event")
            for candidate in candidates_by_category.get(stage, [])
        ]
        candidates_by_type = self._group_by_type(candidates)
        relationships, dedup_report = self._deduplicate(candidates, nodes)
        return {
            "relationship_extraction_order": list(self.STAGE_DEFINITIONS),
            "relationship_extraction_calls": call_reports,
            "relationship_candidates": candidates,
            "relationship_candidates_by_category": candidates_by_category,
            "relationship_candidates_by_type": candidates_by_type,
            "relationship_deduplication_report": dedup_report,
            "relationships": relationships,
        }

    def _load_prompt(self, stage: str) -> str:
        path = self.prompt_paths.get(stage)
        if path is None or not path.exists():
            return ""
        return path.read_text(encoding="utf-8-sig")

    def _extract_stage(
        self,
        stage: str,
        state: Dict[str, Any],
        nodes: Dict[str, Dict[str, Any]],
        relationship_id_start: int,
    ) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """Execute exactly one node-pair category in one call."""

        prompt_template = self.prompt_templates.get(stage) or ""
        if self.llm_client.chat_available and prompt_template:
            prompt = self._render_relationship_prompt(
                prompt_template,
                stage,
                state,
                nodes,
                relationship_id_start=f"{relationship_id_start:07d}",
            )
            response = self.llm_client.extract_relationships(prompt)
            payload = response.get("payload")
            if payload is not None:
                normalized, rejected = self._normalize_llm_relationships(
                    stage,
                    payload.get("relationships") or [],
                    nodes,
                )
                if stage == "entity_event":
                    normalized = self._prefer_participation(normalized)
                local_supplement_count = 0
                if stage == "event_event":
                    normalized, local_supplement_count = self._merge_event_relation_supplement(
                        normalized,
                        self._local_event_event_relationships(state),
                    )
                return normalized, {
                    "stage": stage,
                    "mode": (
                        "llm_prompt+local_event_pair_supplement"
                        if stage == "event_event"
                        else "llm_prompt"
                    ),
                    "status": "success",
                    "accepted_count": len(normalized),
                    "local_supplement_count": local_supplement_count,
                    "rejected_count": len(rejected),
                    "rejected": rejected,
                    "reason": "",
                }
            failure_reason = response.get("reason") or "invalid LLM response"
        else:
            failure_reason = "LLM or category prompt unavailable"

        fallback = {
            "entity_entity": self._local_entity_entity_relationships,
            "entity_event": self._local_entity_event_relationships,
            "event_event": self._local_event_event_relationships,
        }[stage](state)
        if stage == "entity_event":
            fallback = self._prefer_participation(fallback)
        return fallback, {
            "stage": stage,
            "mode": "local_fallback",
            "status": "success",
            "accepted_count": len(fallback),
            "rejected_count": 0,
            "reason": failure_reason,
        }

    def _render_relationship_prompt(
        self,
        template: str,
        stage: str,
        state: Dict[str, Any],
        nodes: Dict[str, Dict[str, Any]],
        relationship_id_start: str,
    ) -> str:
        entities = [
            node
            for node in nodes.values()
            if node.get("node_type") not in {"事件", "案例", "证据"}
        ]
        events = [
            {
                "node_id": event.get("event_id") or "",
                "node_name": event.get("event_name") or "",
                "node_type": "事件",
                "event_type": event.get("event_type") or "",
                "event_description": event.get("event_description") or "",
                "event_start_date": event.get("event_start_date") or "",
                "event_end_date": event.get("event_end_date") or "",
                "involved_entities": event.get("involved_entities") or [],
                "disposition_measures": event.get("disposition_measures") or "",
            }
            for event in state.get("events") or []
        ]
        input_text = "\n\n".join(
            text
            for text in (
                state.get("entity_enhanced_texts")
                or state.get("preprocessed_texts")
                or {}
            ).values()
            if text
        )
        replacements = {
            "{{ENTITY_LIST_JSON}}": json.dumps(
                entities,
                ensure_ascii=False,
                indent=2,
            ),
            "{{EVENT_LIST_JSON}}": json.dumps(
                events,
                ensure_ascii=False,
                indent=2,
            ),
            "{{EVENT_PAIR_CANDIDATES_JSON}}": json.dumps(
                self._event_pair_candidates(state.get("events") or [])
                if stage == "event_event"
                else [],
                ensure_ascii=False,
                indent=2,
            ),
            "{{INPUT_TEXT}}": input_text,
            "{{RELATIONSHIP_ID_START}}": relationship_id_start,
        }
        prompt = template
        for placeholder, value in replacements.items():
            prompt = prompt.replace(placeholder, value)
        return prompt

    def _normalize_llm_relationships(
        self,
        stage: str,
        raw_relationships: List[Any],
        nodes: Dict[str, Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        accepted: List[Dict[str, Any]] = []
        rejected: List[Dict[str, Any]] = []
        allowed_types = self.ALLOWED_TYPES[stage]

        for raw in raw_relationships:
            if not isinstance(raw, dict):
                rejected.append({"relationship": raw, "reason": "关系不是 JSON 对象"})
                continue
            relation_type = str(raw.get("relationship_type") or "")
            source_id = str(raw.get("source_node_id") or "")
            target_id = str(raw.get("target_node_id") or "")
            if relation_type not in allowed_types:
                rejected.append({"relationship": raw, "reason": "关系类型不属于当前抽取类别"})
                continue
            if source_id not in nodes or target_id not in nodes:
                rejected.append({"relationship": raw, "reason": "源节点或目标节点不存在"})
                continue
            if not self._valid_stage_nodes(
                stage,
                nodes[source_id]["node_type"],
                nodes[target_id]["node_type"],
            ):
                rejected.append({"relationship": raw, "reason": "节点组合不属于当前抽取类别"})
                continue
            accepted.append(
                self._candidate(
                    relation_type,
                    source_id,
                    target_id,
                    str(raw.get("relationship_description") or ""),
                    0.85,
                    extraction_category=stage,
                    reason="按指定类别 Prompt 独立抽取并通过节点类型校验",
                )
            )
        return accepted, rejected

    def _valid_stage_nodes(
        self,
        stage: str,
        source_type: str,
        target_type: str,
    ) -> bool:
        source_is_event = source_type == "事件"
        target_is_event = target_type == "事件"
        if stage == "entity_entity":
            return not source_is_event and not target_is_event
        if stage == "entity_event":
            return not source_is_event and target_is_event
        return source_is_event and target_is_event

    def _merge_event_relation_supplement(
        self,
        llm_candidates: List[Dict[str, Any]],
        local_candidates: List[Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], int]:
        merged = list(llm_candidates)
        keys = {
            (
                item.get("relationship_type"),
                item.get("source_node_id"),
                item.get("target_node_id"),
            )
            for item in merged
        }
        supplement_count = 0
        for candidate in local_candidates:
            key = (
                candidate.get("relationship_type"),
                candidate.get("source_node_id"),
                candidate.get("target_node_id"),
            )
            if key in keys:
                continue
            merged.append(candidate)
            keys.add(key)
            supplement_count += 1
        return merged, supplement_count

    def _prefer_participation(
        self,
        candidates: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """Do not keep both 参与 and 涉及 for the same entity-event pair."""

        participation_pairs = {
            (item.get("source_node_id"), item.get("target_node_id"))
            for item in candidates
            if item.get("relationship_type") == "参与关系"
        }
        return [
            item
            for item in candidates
            if not (
                item.get("relationship_type") == "涉及关系"
                and (
                    item.get("source_node_id"),
                    item.get("target_node_id"),
                )
                in participation_pairs
            )
        ]

    def _event_pair_candidates(
        self,
        events: List[Dict[str, Any]],
        max_pairs: int = 200,
    ) -> List[Dict[str, Any]]:
        """Build a bounded coverage checklist for event-event extraction."""

        candidates: List[Tuple[int, Dict[str, Any]]] = []
        for left_index, left in enumerate(events):
            for right_index in range(left_index + 1, len(events)):
                right = events[right_index]
                left_entities = set(left.get("involved_entities") or [])
                right_entities = set(right.get("involved_entities") or [])
                shared_entities = sorted(left_entities.intersection(right_entities))
                same_source = bool(
                    left.get("source_text_name")
                    and left.get("source_text_name") == right.get("source_text_name")
                )
                cues: List[str] = []
                score = 0
                if shared_entities:
                    score += 3
                    cues.append("共享主体或账户")
                if same_source:
                    score += 1
                    cues.append("来自同一分析文本")
                if self._direct_business_continuation(left, right):
                    score += 4
                    cues.append("存在上游—下游业务阶段词")
                if self._is_summary_event(left) or self._is_summary_event(right):
                    score += 3
                    cues.append("一端具有综合/统摄事件特征")
                if self._is_disposition_event(left) or self._is_disposition_event(right):
                    score += 3
                    cues.append("一端具有已实施调查或处置特征")
                if left.get("event_start_date") and right.get("event_start_date"):
                    score += 1
                    cues.append("两端均有明确日期")

                # Adjacent events remain in the checklist for coverage, but the
                # prompt must not accept them solely because they are adjacent.
                if right_index == left_index + 1:
                    score += 1
                    cues.append("文本/事件序列相邻，仅供核查")
                if score <= 0:
                    continue
                candidates.append(
                    (
                        score,
                        {
                            "event_a_id": left.get("event_id") or "",
                            "event_a_name": left.get("event_name") or "",
                            "event_b_id": right.get("event_id") or "",
                            "event_b_name": right.get("event_name") or "",
                            "shared_entities": shared_entities,
                            "same_source_text": same_source,
                            "event_a_dates": [
                                left.get("event_start_date") or "",
                                left.get("event_end_date") or "",
                            ],
                            "event_b_dates": [
                                right.get("event_start_date") or "",
                                right.get("event_end_date") or "",
                            ],
                            "coverage_cues": cues,
                            "instruction": "逐项核查，不得仅凭候选分数或相邻位置建立关系",
                        },
                    )
                )
        candidates.sort(key=lambda item: item[0], reverse=True)
        return [item for _, item in candidates[:max_pairs]]

    def _build_node_registry(self, state: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
        nodes: Dict[str, Dict[str, Any]] = {}
        basic_info = state.get("basic_info") or {}
        case_id = basic_info.get("case_id")
        if case_id:
            nodes[case_id] = {
                "node_id": case_id,
                "node_name": basic_info.get("case_name") or case_id,
                "node_type": "案例",
            }

        collections = (
            ("customers", "客户", ("customer_name",)),
            ("accounts", "账户", ("account_number", "holder_name")),
            ("other_entities", "其他实体", ("entity_attr_1",)),
            ("evidences", "证据", ("evidence_type",)),
        )
        for collection_name, node_type, name_fields in collections:
            id_field = "evidence_id" if collection_name == "evidences" else "entity_id"
            for item in state.get(collection_name) or []:
                node_id = item.get(id_field)
                if not node_id:
                    continue
                node_name = next(
                    (item.get(field) for field in name_fields if item.get(field)),
                    node_id,
                )
                nodes[node_id] = {
                    "node_id": node_id,
                    "node_name": node_name,
                    "node_type": node_type,
                }

        for event in state.get("events") or []:
            event_id = event.get("event_id")
            if event_id:
                nodes[event_id] = {
                    "node_id": event_id,
                    "node_name": event.get("event_name") or event.get("event_type") or event_id,
                    "node_type": "事件",
                }
        return nodes

    def _structural_relationships(
        self,
        state: Dict[str, Any],
        nodes: Dict[str, Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        candidates: List[Dict[str, Any]] = []
        case_id = (state.get("basic_info") or {}).get("case_id")
        if case_id:
            for collection_name, description in (
                ("customers", "案例涉及客户"),
                ("accounts", "案例涉及账户"),
                ("other_entities", "案例涉及其他实体"),
            ):
                for item in state.get(collection_name) or []:
                    entity_id = item.get("entity_id")
                    if entity_id in nodes:
                        candidates.append(
                            self._candidate(
                                "涉及关系",
                                case_id,
                                entity_id,
                                description,
                                1.0,
                                extraction_category="structural",
                                reason="由案例输入的实体集合确定",
                            )
                        )
            for event in state.get("events") or []:
                if event.get("event_id") in nodes:
                    candidates.append(
                        self._candidate(
                            "包含关系",
                            case_id,
                            event["event_id"],
                            "案例包含已抽取业务事件",
                            event.get("confidence_score", 0.8),
                            evidence_text=event.get("source_text"),
                            source_text_name=event.get("source_text_name"),
                            source_section="全文",
                            extraction_category="structural",
                            reason="由案例与最终事件集合确定",
                        )
                    )

        for evidence in state.get("evidences") or []:
            link_id = evidence.get("evidence_link_id")
            evidence_id = evidence.get("evidence_id")
            if link_id and evidence_id:
                candidates.append(
                    self._candidate(
                        "来源关系",
                        link_id,
                        evidence_id,
                        "结构化证据关联到业务节点",
                        1.0,
                        evidence_ids=[evidence_id],
                        evidence_text=evidence.get("original_data"),
                        extraction_category="structural",
                        reason="由 evidence_link_id 确定",
                    )
                )
        return candidates

    def _local_entity_entity_relationships(
        self,
        state: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        candidates: List[Dict[str, Any]] = []
        customers = state.get("customers") or []
        for account in state.get("accounts") or []:
            account_id = account.get("entity_id")
            holder_name = account.get("holder_name")
            holder_id = account.get("holder_id_number")
            for customer in customers:
                name_match = holder_name and holder_name == customer.get("customer_name")
                id_match = holder_id and holder_id == customer.get("id_number")
                if not (name_match or id_match):
                    continue
                basis = (
                    "账户资料中的持有人姓名和证件号码与客户实体一致"
                    if name_match and id_match
                    else "账户资料中的持有人信息与客户实体一致"
                )
                candidates.append(
                    self._candidate(
                        "持有关系",
                        customer.get("entity_id"),
                        account_id,
                        basis,
                        1.0,
                        extraction_category="entity_entity",
                        reason="结构化账户持有人资料直接匹配",
                    )
                )
        return candidates

    def _local_entity_event_relationships(
        self,
        state: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        candidates: List[Dict[str, Any]] = []
        for event in state.get("events") or []:
            event_id = event.get("event_id")
            text = event.get("event_description") or event.get("source_text") or ""
            for entity_id in event.get("involved_entities") or []:
                relation_type = "涉及关系"
                description = "该实体是事件文本明确提及的重要对象、账户、交易对手或通道。"
                if entity_id.startswith("CUST") and any(
                    word in text
                    for word in ("发起", "实施", "通过", "转入", "转出", "收款", "付款")
                ):
                    relation_type = "参与关系"
                    description = "文本明确将该客户与事件中的核心交易行为相联系。"
                elif entity_id.startswith("ORG") and any(
                    word in text for word in ("查询", "调查", "冻结", "限制")
                ):
                    relation_type = "参与关系"
                    description = "该机构在文本中执行查询、调查或处置行为。"
                candidates.append(
                    self._candidate(
                        relation_type,
                        entity_id,
                        event_id,
                        description,
                        event.get("confidence_score", 0.7),
                        evidence_text=text,
                        source_text_name=event.get("source_text_name"),
                        source_section="全文",
                        extraction_category="entity_event",
                        reason="本地兜底按实体角色保守区分参与和涉及",
                    )
                )
        return candidates

    def _local_event_event_relationships(
        self,
        state: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        events = list(state.get("events") or [])
        candidates: List[Dict[str, Any]] = []

        # 1. 已实施的调查/处置 → 明确针对的风险事件。每个处置事件
        # 最多连接三个最相关风险事件，避免形成“处置节点连全案”的稠密图。
        for source in events:
            if not self._is_disposition_event(source):
                continue
            ranked_targets: List[Tuple[int, Dict[str, Any]]] = []
            for target in events:
                if source is target or self._is_disposition_event(target):
                    continue
                if self._event_sort_key(source) < self._event_sort_key(target):
                    continue
                shared = self._event_entities(source).intersection(
                    self._event_entities(target)
                )
                same_source = self._same_source_text(source, target)
                feature_overlap = self._event_feature_tags(source).intersection(
                    self._event_feature_tags(target)
                )
                if not shared and not (same_source and feature_overlap):
                    continue
                score = 3 * len(shared) + 2 * len(feature_overlap)
                if same_source:
                    score += 1
                ranked_targets.append((score, target))
            ranked_targets.sort(
                key=lambda item: (item[0], self._event_sort_key(item[1])),
                reverse=True,
            )
            for _, target in ranked_targets[:3]:
                candidates.append(
                    self._candidate(
                        "应对关系",
                        source.get("event_id"),
                        target.get("event_id"),
                        "已实施的调查或处置事件针对同一主体及其已识别风险行为。",
                        0.72,
                        extraction_category="event_event",
                        reason="本地兜底要求处置已经发生，并以共享主体或相同风险特征确认处置对象",
                    )
                )

        # 2. 综合/统摄事件 → 具有独立意义的明细子事件。
        for parent in events:
            if not self._is_summary_event(parent):
                continue
            parent_tags = self._event_feature_tags(parent)
            if not parent_tags:
                continue
            ranked_children: List[Tuple[int, Dict[str, Any], set[str]]] = []
            for child in events:
                if child is parent or self._is_summary_event(child):
                    continue
                child_tags = self._event_feature_tags(child)
                overlap = parent_tags.intersection(child_tags)
                if not overlap:
                    continue
                shared = self._event_entities(parent).intersection(
                    self._event_entities(child)
                )
                if not shared and not self._same_source_text(parent, child):
                    continue
                score = 3 * len(overlap) + 2 * len(shared)
                ranked_children.append((score, child, overlap))
            ranked_children.sort(
                key=lambda item: (item[0], self._event_sort_key(item[1])),
                reverse=True,
            )
            for _, child, overlap in ranked_children[:8]:
                feature_text = "、".join(sorted(overlap))
                candidates.append(
                    self._candidate(
                        "上下位关系",
                        parent.get("event_id"),
                        child.get("event_id"),
                        f"综合事件统摄明细事件中独立呈现的{feature_text}风险特征。",
                        0.7,
                        extraction_category="event_event",
                        reason="本地兜底要求综合事件标志、共同业务范围和风险特征交集同时成立",
                    )
                )

        # 3. 上游资金阶段 → 后续直接资金阶段。每个后续事件只保留
        # 一个最直接的前序事件，避免把整条链路做成两两相连。
        ordered = sorted(events, key=self._event_sort_key)
        for current_index, current in enumerate(ordered[1:], start=1):
            if self._is_summary_event(current):
                continue
            ranked_previous: List[Tuple[int, int, Dict[str, Any]]] = []
            for distance, previous in enumerate(
                reversed(ordered[max(0, current_index - 8):current_index]),
                start=1,
            ):
                if self._is_summary_event(previous):
                    continue
                if not self._direct_business_continuation(previous, current):
                    continue
                shared = self._event_entities(current).intersection(
                    self._event_entities(previous)
                )
                same_source = self._same_source_text(previous, current)
                if not shared and not same_source:
                    continue
                score = 4 + 3 * len(shared) + (1 if same_source else 0)
                ranked_previous.append((score, -distance, previous))
            if not ranked_previous:
                continue
            _, _, previous = max(ranked_previous, key=lambda item: (item[0], item[1]))
            candidates.append(
                self._candidate(
                    "顺承关系",
                    previous.get("event_id"),
                    current.get("event_id"),
                    "前序事件描述资金进入或归集，后续事件描述同一案件链路中的转出、分散或快速过渡。",
                    0.68,
                    extraction_category="event_event",
                    reason="本地兜底要求同一主体或文本范围以及直接上游—下游业务词同时成立",
                )
            )
        return candidates

    def _event_text(self, event: Dict[str, Any]) -> str:
        return " ".join(
            str(event.get(field) or "")
            for field in (
                "event_name",
                "event_type",
                "event_description",
                "disposition_measures",
                "source_text",
            )
        )

    def _event_entities(self, event: Dict[str, Any]) -> set[str]:
        return {
            str(entity_id)
            for entity_id in (event.get("involved_entities") or [])
            if entity_id
        }

    def _same_source_text(
        self,
        left: Dict[str, Any],
        right: Dict[str, Any],
    ) -> bool:
        left_source = left.get("source_text_name")
        return bool(left_source and left_source == right.get("source_text_name"))

    def _event_feature_tags(self, event: Dict[str, Any]) -> set[str]:
        text = self._event_text(event)
        patterns = {
            "资金归集": ("资金来源", "分散转入", "集中收款", "资金归集", "流入"),
            "快速转出": ("快速转出", "快进快出", "24小时内转出", "2小时内", "不留余额"),
            "分散转出": ("分散转出", "集中转出", "向外部个人", "资金去向", "流出"),
            "循环回流": ("回流", "循环", "三跳"),
            "夜间交易": ("夜间", "凌晨", "22:00", "06:00"),
            "跨行交易": ("跨行", "多个省份", "四个测试省份"),
            "整数金额": ("整数金额", "整数倍", "1000元", "2000元", "5000元"),
            "闲置账户启用": ("闲置账户", "突然恢复", "突然活跃", "无主动交易"),
            "身份规模不匹配": ("身份不匹配", "职业收入", "交易规模", "年收入"),
            "共享控制要素": ("共同设备", "共享设备", "共同IP", "共同电话", "共同地址"),
            "尽职调查": ("尽调", "尽职调查", "业务资料", "经营凭证"),
            "账户限制": ("已冻结", "已限制", "维持已实施", "非柜面限额", "风险等级调整"),
            "司法调查": ("有权机关", "司法查询", "公安", "经侦"),
        }
        return {
            tag
            for tag, keywords in patterns.items()
            if any(keyword in text for keyword in keywords)
        }

    def _is_summary_event(self, event: Dict[str, Any]) -> bool:
        text = self._event_text(event)
        summary_cues = (
            "综合分析",
            "综合判断",
            "全案",
            "多主体案例概述",
            "支持可疑判断的证据包括",
            "共同构成",
            "整体呈现",
        )
        return any(cue in text for cue in summary_cues) and len(
            self._event_feature_tags(event)
        ) >= 2

    def _is_disposition_event(self, event: Dict[str, Any]) -> bool:
        text = self._event_text(event)
        if any(word in text for word in ("建议", "拟采取", "可考虑")) and not any(
            word in text for word in ("已完成", "已实施", "已冻结", "已限制")
        ):
            return False
        completed_cues = (
            "已完成增强尽调",
            "完成增强尽调",
            "已完成尽调",
            "已实施",
            "已冻结",
            "已限制",
            "完成调查",
            "实施非柜面限额",
            "风险等级已调整",
            "有权机关已查询",
        )
        return any(cue in text for cue in completed_cues)

    def _event_sort_key(self, event: Dict[str, Any]) -> Tuple[str, str]:
        return (
            event.get("event_start_date") or "9999-99-99",
            event.get("event_id") or "",
        )

    def _direct_business_continuation(
        self,
        previous: Dict[str, Any],
        current: Dict[str, Any],
    ) -> bool:
        previous_text = self._event_text(previous)
        current_text = self._event_text(current)
        upstream_words = ("转入", "收款", "归集", "存入", "资金来源")
        downstream_words = (
            "转出",
            "分散",
            "取现",
            "兑换",
            "付款",
            "资金去向",
            "快速过渡",
            "快进快出",
        )
        return any(word in previous_text for word in upstream_words) and any(
            word in current_text for word in downstream_words
        )

    def _group_by_type(
        self,
        candidates: List[Dict[str, Any]],
    ) -> Dict[str, List[Dict[str, Any]]]:
        grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for candidate in candidates:
            grouped[candidate.get("relationship_type") or "未分类"].append(candidate)
        return dict(grouped)

    def _deduplicate(
        self,
        candidates: List[Dict[str, Any]],
        nodes: Dict[str, Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        grouped: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = defaultdict(list)
        report: List[Dict[str, Any]] = []
        for candidate in candidates:
            source_id = candidate.get("source_node_id")
            target_id = candidate.get("target_node_id")
            if source_id not in nodes or target_id not in nodes:
                report.append({"candidate": candidate, "reason": "源节点或目标节点不存在"})
                continue
            key = (candidate["relationship_type"], source_id, target_id)
            grouped[key].append(candidate)

        relationships: List[Dict[str, Any]] = []
        for key, items in grouped.items():
            best = max(items, key=lambda item: item.get("confidence_score", 0.0))
            source = nodes[best["source_node_id"]]
            target = nodes[best["target_node_id"]]
            self.relationship_counter += 1
            relationships.append(
                {
                    "relationship_id": f"{self.relationship_counter:07d}",
                    "relationship_type": best["relationship_type"],
                    "relationship_description": best.get("relationship_description"),
                    "source_node_id": source["node_id"],
                    "source_node_name": source["node_name"],
                    "source_node_type": source["node_type"],
                    "target_node_id": target["node_id"],
                    "target_node_name": target["node_name"],
                    "target_node_type": target["node_type"],
                    "evidence_ids": sorted(
                        {
                            evidence_id
                            for item in items
                            for evidence_id in item.get("evidence_ids", [])
                        }
                    ),
                    "evidence_texts": sorted(
                        {
                            text
                            for item in items
                            for text in item.get("evidence_texts", [])
                            if text
                        }
                    ),
                    "source_text_name": best.get("source_text_name"),
                    "source_section": best.get("source_section"),
                    "confidence_score": max(
                        item.get("confidence_score", 0.0)
                        for item in items
                    ),
                    "reason": (
                        f"{best.get('reason') or '分类关系抽取'}；"
                        f"{len(items)} 条候选按 类型+源节点+目标节点 去重合并"
                    ),
                    "extraction_category": best.get("extraction_category"),
                }
            )
            if len(items) > 1:
                report.append(
                    {
                        "relationship_key": key,
                        "candidate_count": len(items),
                        "action": "候选关系去重合并",
                    }
                )
        return relationships, report

    def _candidate(
        self,
        relationship_type: str,
        source_node_id: Optional[str],
        target_node_id: Optional[str],
        description: str,
        confidence: float,
        evidence_ids: Optional[List[str]] = None,
        evidence_text: Optional[str] = None,
        source_text_name: Optional[str] = None,
        source_section: Optional[str] = None,
        extraction_category: Optional[str] = None,
        reason: Optional[str] = None,
    ) -> Dict[str, Any]:
        return {
            "relationship_type": relationship_type,
            "relationship_description": description,
            "source_node_id": source_node_id,
            "target_node_id": target_node_id,
            "evidence_ids": evidence_ids or [],
            "evidence_texts": [evidence_text] if evidence_text else [],
            "source_text_name": source_text_name,
            "source_section": source_section,
            "confidence_score": round(float(confidence or 0.0), 6),
            "extraction_category": extraction_category,
            "reason": reason,
        }
