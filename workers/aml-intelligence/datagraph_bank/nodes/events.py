"""Risk event extraction with a real local knowledge base retriever."""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from datagraph_bank.io_utils import load_json
from datagraph_bank.knowledge_base import create_knowledge_repository
from datagraph_bank.llm import DeepSeekLLMClient


EVENT_PROMPT_FIELDS = (
    "event_id",
    "event_name",
    "event_type",
    "event_description",
    "event_start_date",
    "event_end_date",
    "recognition_rule",
    "product_service",
    "value_tool",
    "risk_type",
    "risk_indicator",
    "disposition_measures",
)

LOCAL_KB_CUE_GROUPS: Dict[str, Tuple[Tuple[str, ...], ...]] = {
    "ET001": (("大额", "大额转账"), ("转账", "转入", "转出")),
    "ET002": (("高频", "频繁", "笔"), ("日", "天", "月", "期间", "观察期")),
    "ET003": (("夜间", "凌晨"), ("交易", "转账")),
    "ET004": (("多跳", "三跳", "接力转账"),),
    "ET005": (("跨区域", "多省", "多个省", "异地"), ("交易", "转账")),
    "ET006": (("第三方支付", "支付宝", "微信"), ("高频", "笔", "结算")),
    "ET007": (("有权机关", "司法", "公安", "经侦"), ("查询", "协查")),
    "ET008": (
        ("不匹配", "无法充分说明", "未能提供", "不能解释"),
        ("职业", "收入", "待业", "退休", "经营", "交易用途"),
    ),
    "ET009": (
        ("快进快出", "快速转出", "24小时内转出", "2小时内"),
        ("余额", "转出", "分发"),
    ),
    "ET010": (("整数", "固定金额", "整数倍"), ("交易", "消费", "转账")),
    "ET011": (("小额", "拆分"), ("高频", "多笔", "频繁")),
    "ET012": (("关联客户", "关联账户", "协同", "共同"), ("交易", "转账", "资金")),
    "ET013": (("涉赌", "赌博", "博彩", "投注"),),
    "ET014": (("集中归集", "资金归集", "归集"), ("分散转出", "多笔转出")),
    "ET015": (
        ("分散转入", "多人转入"),
        ("集中转出", "转至少数", "少量去向", "集中去向"),
    ),
    "ET016": (("现金",), ("集中存", "集中取", "存取", "取现")),
    "ET017": (("ATM", "自动柜员机"), ("多地", "异地", "跨区域"), ("取现",)),
    "ET018": (("公转私", "对公转个人", "公司账户转入个人"),),
    "ET019": (("私转公", "个人转对公", "个人账户转入公司"),),
    "ET020": (("回流", "循环"), ("资金", "交易", "转账")),
    "ET021": (("休眠", "闲置", "无主动交易"), ("启用", "恢复", "活跃")),
    "ET022": (("交易对手", "对手"), ("分散", "一次性", "多人", "多名")),
    "ET023": (("交易对手", "对手"), ("高度集中", "固定对手", "单一对手")),
    "ET024": (("共用", "共同", "共享", "同一"), ("设备", "IP", "DEV")),
    "ET025": (("复用", "共同", "相同"), ("联系方式", "电话", "地址"), ("开户", "账户")),
    "ET026": (("出租", "出借", "代持", "他人控制"),),
    "ET027": (("空壳", "无实际经营"), ("公司", "企业")),
    "ET028": (
        ("虚假贸易", "虚假服务", "无真实交易", "凭证不足"),
        ("结算", "贸易", "服务", "货款"),
    ),
    "ET029": (("跨境", "境外"), ("多层", "绕转", "多跳")),
    "ET030": (("虚拟币", "虚拟资产", "数字货币"), ("兑换", "转换", "买入", "卖出")),
    "ET031": (("混币", "跨链", "剥离"),),
    "ET032": (("贷款", "信贷"), ("挪用", "回流")),
    "ET033": (
        ("信用卡", "POS"),
        ("套现", "回流", "虚假消费", "凭证不足", "关联路径", "不能完整解释"),
    ),
    "ET034": (("保险",), ("投保", "退保")),
    "ET035": (("投资", "基金", "理财"), ("申购", "赎回", "资产转换")),
    "ET036": (("已冻结", "冻结", "已限制", "限制交易", "非柜面限额"),),
    "ET037": (("风险等级", "风险评级", "增强尽调", "尽职调查"),),
    "ET038": (("支付信息", "报文"), ("删改", "制裁规避")),
    "ET039": (("电诈", "诈骗", "受害"), ("归集", "转移")),
    "ET040": (("跑分", "账户网络", "分层转移"),),
    "ET041": (("药品", "医药", "药企"), ("回扣", "返点", "利益输送")),
    "ET042": (("医疗设备", "设备采购", "设备维保"), ("回扣", "顾问费", "利益输送")),
    "ET043": (("耗材", "高值耗材"), ("回扣", "返点", "利益输送")),
    "ET044": (("统方", "处方数据"), ("回扣", "好处费", "利益输送")),
    "ET045": (("科研经费", "课题经费"), ("套取", "虚假服务", "虚假报销")),
}


@dataclass
class RetrievalHit:
    event_type: Dict[str, Any]
    score: float
    tfidf_score: float
    embedding_score: Optional[float] = None
    method: str = "tfidf"


class CharTfidfIndex:
    """Small dependency-free char n-gram TF-IDF index for Chinese text."""

    def __init__(self, documents: Sequence[str], ngram_range: Tuple[int, int] = (2, 4)) -> None:
        self.documents = list(documents)
        self.ngram_range = ngram_range
        self.idf: Dict[str, float] = {}
        self.doc_vectors: List[Dict[str, float]] = []
        self._build()

    def search(self, query: str, top_k: int = 3) -> List[Tuple[int, float]]:
        query_vector = self._vectorize(query)
        scores = [
            (index, self._cosine(query_vector, doc_vector))
            for index, doc_vector in enumerate(self.doc_vectors)
        ]
        return sorted(scores, key=lambda item: item[1], reverse=True)[:top_k]

    def _build(self) -> None:
        tokenized_docs = [self._tokens(document) for document in self.documents]
        document_frequency: Counter[str] = Counter()
        for tokens in tokenized_docs:
            document_frequency.update(set(tokens))

        total_docs = len(tokenized_docs) or 1
        self.idf = {
            token: math.log((total_docs + 1) / (df + 1)) + 1.0
            for token, df in document_frequency.items()
        }
        self.doc_vectors = [self._vectorize_tokens(tokens) for tokens in tokenized_docs]

    def _tokens(self, text: str) -> List[str]:
        normalized = re.sub(r"\s+", "", text or "")
        tokens: List[str] = []
        min_n, max_n = self.ngram_range
        for n in range(min_n, max_n + 1):
            tokens.extend(normalized[index : index + n] for index in range(max(0, len(normalized) - n + 1)))
        return [token for token in tokens if token]

    def _vectorize(self, text: str) -> Dict[str, float]:
        return self._vectorize_tokens(self._tokens(text))

    def _vectorize_tokens(self, tokens: List[str]) -> Dict[str, float]:
        counts = Counter(tokens)
        weights = {
            token: count * self.idf.get(token, 1.0)
            for token, count in counts.items()
        }
        norm = math.sqrt(sum(value * value for value in weights.values())) or 1.0
        return {token: value / norm for token, value in weights.items()}

    def _cosine(self, left: Dict[str, float], right: Dict[str, float]) -> float:
        if len(left) > len(right):
            left, right = right, left
        return sum(value * right.get(token, 0.0) for token, value in left.items())


class EventExtractor:
    """Extract complete suspicious-risk events and classify them through the KB."""

    def __init__(
        self,
        kb_path: Path,
        risk_threshold: float = 0.12,
        dedup_threshold: float = 0.82,
        llm_review_threshold: float = 0.68,
        llm_client: Optional[DeepSeekLLMClient] = None,
        embedding_weight: float = 0.45,
        prompt_path: Optional[Path] = None,
    ) -> None:
        self.kb_path = Path(kb_path)
        self.risk_threshold = risk_threshold
        self.dedup_threshold = dedup_threshold
        self.llm_review_threshold = llm_review_threshold
        self.llm_client = llm_client or DeepSeekLLMClient(enabled=False)
        self.embedding_weight = embedding_weight
        self.prompt_path = Path(prompt_path) if prompt_path else None
        self.prompt_template = self._load_prompt_template()
        self.event_kb = load_json(self.kb_path, default={"event_types": []})
        self.knowledge_repository = create_knowledge_repository(json_path=self.kb_path)
        self.knowledge_base_backend = self.knowledge_repository.backend
        self.knowledge_base_reference = (
            f"qdrant://{self.knowledge_repository.collection}"
            if self.knowledge_base_backend == "qdrant"
            else str(self.kb_path)
        )
        self.event_types = [
            record.to_dict() for record in self.knowledge_repository.list_records()
        ]
        self.kb_candidate_top_k = int(
            (self.event_kb.get("context_control") or {}).get("recommended_top_k")
            or 5
        )
        self.kb_documents = [self._kb_document(item) for item in self.event_types]
        self.index = CharTfidfIndex(self.kb_documents)
        self._kb_embeddings: Optional[List[List[float]]] = None
        self.event_counter = 0

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        self.event_counter = 0
        candidates = self._extract_candidates(state)
        retrieval_results: List[Dict[str, Any]] = []

        for candidate in candidates:
            hits = self._retrieve(candidate)
            top_hit = hits[0] if hits else None
            retrieval_entry = {
                "candidate_id": candidate["candidate_id"],
                "candidate_text": candidate["candidate_text"],
                "top_k": [
                    {
                        "event_type_id": hit.event_type.get("id"),
                        "event_type": hit.event_type.get("name"),
                        "score": round(hit.score, 6),
                        "tfidf_score": round(hit.tfidf_score, 6),
                        "embedding_score": round(hit.embedding_score, 6)
                        if hit.embedding_score is not None
                        else None,
                        "method": hit.method,
                    }
                    for hit in hits
                ],
                "similarity_above_reference_threshold": bool(
                    top_hit and top_hit.score >= self.risk_threshold
                ),
                "reference_threshold": self.risk_threshold,
                "recall_method": hits[0].method if hits else "none",
                "note": "相似度仅用于知识库候选召回，不再用于过滤事件",
            }
            retrieval_results.append(retrieval_entry)

        llm_events, extraction_calls = self._extract_document_events_with_llm(state)
        if llm_events is not None:
            filled_events = llm_events
            extraction_mode = "llm_prompt"
        else:
            filled_events = self._build_local_events(candidates)
            extraction_mode = "local_fallback"

        numbered_events = self._renumber_events(filled_events)
        final_events, dedup_report = self._deduplicate(numbered_events)
        final_events = self._renumber_events(final_events)
        return {
            "event_candidates": candidates,
            "knowledge_base_recall_results": retrieval_results,
            "event_extraction_calls": extraction_calls,
            "event_extraction_mode": extraction_mode,
            "event_deduplication_report": dedup_report,
            "events": final_events,
        }

    def _extract_document_events_with_llm(
        self,
        state: Dict[str, Any],
    ) -> Tuple[Optional[List[Dict[str, Any]]], List[Dict[str, Any]]]:
        """Run the supplied event prompt once per complete analysis document.

        This deliberately avoids chapter-label boundaries. If any document call
        fails or returns an invalid payload, the whole node falls back to the
        deterministic path so a partially extracted case is not silently mixed.
        """

        calls: List[Dict[str, Any]] = []
        if not self.llm_client.chat_available or not self.prompt_template:
            calls.append(
                {
                    "status": "skipped",
                    "reason": "LLM or event prompt unavailable; use local fallback",
                }
            )
            return None, calls

        texts = (
            state.get("entity_enhanced_texts")
            or state.get("preprocessed_texts")
            or state.get("analysis_texts")
            or {}
        )
        entity_list = self._entity_prompt_list(state)
        extracted: List[Dict[str, Any]] = []
        next_id = 1

        for text_name, text in texts.items():
            analysis_text = self._analysis_content(text or "")
            kb_candidates = [
                {
                    "id": event_type.get("id") or "",
                    "name": event_type.get("name") or "",
                    "rule": event_type.get("rule") or "",
                    "category": event_type.get("category") or "",
                }
                for event_type in self.event_types
            ]
            prompt = self._render_event_prompt(
                kb_candidates=kb_candidates,
                entities=entity_list,
                input_text=analysis_text,
                event_id_start=f"{next_id:07d}",
            )
            response = self.llm_client.extract_events(prompt)
            payload = response.get("payload")
            call_report = {
                "text_name": text_name,
                "status": "success" if payload is not None else "failed",
                "reason": response.get("reason") or "",
                "knowledge_base_candidate_ids": [item["id"] for item in kb_candidates],
            }
            calls.append(call_report)
            if payload is None:
                return None, calls

            raw_events = payload.get("events") or []
            for raw_event in raw_events:
                if not isinstance(raw_event, dict):
                    continue
                normalized_event = self._normalize_prompt_event(
                    raw_event,
                    text_name=text_name,
                    source_text=analysis_text,
                    entity_catalog=entity_list,
                )
                if (
                    not normalized_event.get("involved_entities")
                    or self._is_observation_only_event(normalized_event)
                ):
                    continue
                extracted.append(normalized_event)
                next_id += 1

        return extracted, calls

    def _build_local_events(
        self,
        candidates: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """Keep valid suspicious-risk candidates, including knowledge-base misses."""

        events: List[Dict[str, Any]] = []
        for candidate in candidates:
            if not candidate.get("involved_entities"):
                continue
            query = self._candidate_query(candidate)
            hits = self._retrieve_text(query, top_k=len(self.event_types))
            matched_hit = self._select_local_kb_match(candidate, hits)
            top_hit = hits[0] if hits else None
            if matched_hit:
                events.append(self._fill_event(candidate, matched_hit))
                continue
            if self._is_observation_only_text(
                candidate.get("candidate_text") or "",
            ):
                continue
            events.append(self._fill_new_event(candidate, top_hit))
        return events

    def _load_prompt_template(self) -> str:
        if self.prompt_path is None or not self.prompt_path.exists():
            return ""
        return self.prompt_path.read_text(encoding="utf-8-sig")

    def _render_event_prompt(
        self,
        kb_candidates: List[Dict[str, str]],
        entities: List[Dict[str, str]],
        input_text: str,
        event_id_start: str,
    ) -> str:
        replacements = {
            "{{KB_EVENT_CANDIDATES_JSON}}": json.dumps(
                kb_candidates,
                ensure_ascii=False,
                indent=2,
            ),
            "{{ENTITY_LIST_JSON}}": json.dumps(
                entities,
                ensure_ascii=False,
                indent=2,
            ),
            "{{INPUT_TEXT}}": input_text,
            "{{EVENT_ID_START}}": event_id_start,
        }
        prompt = self.prompt_template
        for placeholder, value in replacements.items():
            prompt = prompt.replace(placeholder, value)
        return prompt

    def _entity_prompt_list(self, state: Dict[str, Any]) -> List[Dict[str, str]]:
        entities: List[Dict[str, str]] = []
        collections = (
            (
                "customers",
                "客户",
                ("customer_name", "customer_number", "id_number"),
            ),
            (
                "accounts",
                "账户",
                ("account_number", "holder_name", "bank_card_number"),
            ),
            (
                "other_entities",
                "其他实体",
                ("entity_attr_1", "entity_attr_2", "entity_attr_3", "entity_attr_4"),
            ),
        )
        for collection_name, node_type, alias_fields in collections:
            for item in state.get(collection_name) or []:
                entity_id = str(item.get("entity_id") or "")
                if not entity_id:
                    continue
                aliases = list(
                    dict.fromkeys(
                        str(item.get(field) or "").strip()
                        for field in alias_fields
                        if str(item.get(field) or "").strip()
                    )
                )
                node_name = aliases[0] if aliases else entity_id
                entities.append(
                    {
                        "node_id": entity_id,
                        "node_name": node_name,
                        "node_type": node_type,
                        "aliases": "、".join(aliases),
                    }
                )
        return entities

    def _normalize_prompt_event(
        self,
        raw_event: Dict[str, Any],
        text_name: str,
        source_text: str,
        entity_catalog: Optional[List[Dict[str, str]]] = None,
    ) -> Dict[str, Any]:
        normalized = {
            field: str(raw_event.get(field) or "").strip()
            for field in EVENT_PROMPT_FIELDS
        }
        normalized["event_description"] = self._clean_event_description(
            normalized["event_description"]
        )
        recognition_rule = normalized["recognition_rule"]
        matched_type = next(
            (
                item
                for item in self.event_types
                if item.get("name") == normalized["event_type"]
            ),
            None,
        )
        marked_type_id_match = re.search(
            r"知识库事件类型[:：]\s*(ET\d+)\s*/",
            recognition_rule,
        )
        is_existing = (
            "知识库标记：已有" in recognition_rule
            and matched_type is not None
            and marked_type_id_match is not None
            and marked_type_id_match.group(1) == matched_type.get("id")
        )
        if not is_existing:
            recognition_rule = re.sub(
                r"知识库标记：已有[^；]*；?",
                "",
                recognition_rule,
                count=1,
            ).strip("； ")
            recognition_rule = re.sub(
                r"知识库事件类型[:：]\s*ET\d+\s*/[^；]*；?",
                "",
                recognition_rule,
                count=1,
            ).strip("； ")
            if "知识库标记：新增" not in recognition_rule:
                recognition_rule = (
                    "知识库标记：新增；知识库事件类型：无；"
                    "成立规则：满足可疑风险事件定义但未完整满足已有类型必要规则；"
                    f"事实依据：{recognition_rule or normalized['event_description']}"
                )
        normalized["recognition_rule"] = recognition_rule

        dates = [
            value
            for value in (
                normalized["event_start_date"],
                normalized["event_end_date"],
            )
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value)
        ]
        if normalized["event_start_date"] and normalized["event_start_date"] not in dates:
            normalized["event_start_date"] = ""
        if normalized["event_end_date"] and normalized["event_end_date"] not in dates:
            normalized["event_end_date"] = ""
        normalized["risk_type"] = self._normalize_risk_type(
            normalized["risk_type"],
            matched_type.get("category") if matched_type else None,
        )

        event_text = " ".join(
            (
                normalized["event_name"],
                normalized["event_description"],
            )
        )
        involved_entities = self._resolve_event_entities(
            event_text,
            entity_catalog or [],
        )
        if is_existing and matched_type:
            normalized["event_type"] = str(matched_type.get("name") or "")
            normalized["event_name"] = self._canonical_existing_event_name(
                involved_entities,
                normalized["event_type"],
            )
        else:
            summarized_type = self._summarize_new_event_type(
                normalized["event_description"],
                normalized["event_type"],
                normalized["event_name"],
            )
            normalized["event_type"] = summarized_type
            normalized["event_name"] = (
                f"{self._entity_prefix(involved_entities)}"
                f"{summarized_type}事件（新增）"
            )
        metrics = self._extract_metrics(
            f"{normalized['event_description']} {normalized['risk_indicator']}"
        )
        return {
            **normalized,
            "event_type_id": matched_type.get("id") if is_existing else None,
            "source_text_name": text_name,
            "source_section": "全文",
            "source_text": normalized["event_description"] or source_text,
            "involved_entities": involved_entities,
            "metrics": metrics,
            "confidence_score": 0.9 if is_existing else 0.75,
            "reason": (
                "依据事件抽取 Prompt 按完整可疑风险事件粒度抽取；"
                + ("完整匹配知识库规则" if is_existing else "知识库外新增事件")
            ),
            "reason_steps": [
                "全文级抽取，不使用章节标签切分",
                "先判断业务事件是否成立，再核对知识库候选完整规则",
                "知识库相似度只用于召回，不用于过滤事件",
            ],
            "retrieval_method": "llm_prompt+knowledge_base_recall",
            "needs_llm_review": False,
            "llm_reviewed": True,
            "llm_review_reason": "",
            "duplicate_of": None,
        }

    def _renumber_events(self, events: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        ordered = sorted(
            events,
            key=lambda item: (
                0 if item.get("event_start_date") else 1,
                item.get("event_start_date") or "9999-99-99",
                item.get("event_end_date") or "9999-99-99",
                item.get("event_id") or "",
            ),
        )
        id_mapping: Dict[str, str] = {}
        for index, event in enumerate(ordered, start=1):
            old_id = str(event.get("event_id") or "")
            new_id = f"{index:07d}"
            if old_id:
                id_mapping[old_id] = new_id
            event["event_id"] = new_id
        for event in ordered:
            duplicate_of = event.get("duplicate_of")
            if duplicate_of in id_mapping:
                event["duplicate_of"] = id_mapping[duplicate_of]
        return ordered

    def _extract_candidates(self, state: Dict[str, Any]) -> List[Dict[str, Any]]:
        candidates: List[Dict[str, Any]] = []
        candidate_counter = 0
        keywords = self._risk_keywords()

        sections_by_text = (
            state.get("context_enhanced_sections")
            or state.get("entity_enhanced_sections")
            or state.get("structured_sections")
            or {}
        )
        for text_name, sections in sections_by_text.items():
            for section in sections:
                units = self._split_atomic_units(self._analysis_content(section["text"]))
                for unit in units:
                    hits = [keyword for keyword in keywords if keyword and keyword in unit]
                    if not self._looks_like_event(unit, hits):
                        continue
                    candidate_counter += 1
                    candidates.append(
                        {
                            "candidate_id": f"CAND_{candidate_counter:04d}",
                            "source_text_name": text_name,
                            "source_section": section.get("section_name") or section.get("section_title"),
                            "candidate_text": unit,
                            "trigger_keywords": sorted(set(hits)),
                            "involved_entities": self._extract_entity_ids(unit),
                            "metrics": self._extract_metrics(unit),
                            "dates": self._extract_dates(unit),
                            "context_metadata": section.get("metadata") or {},
                            "reason": "命中风险关键词或交易指标，进入风险事件候选集合",
                        }
                    )
        return candidates

    def _retrieve(self, candidate: Dict[str, Any]) -> List[RetrievalHit]:
        return self._retrieve_text(self._candidate_query(candidate), top_k=5)

    def _candidate_query(self, candidate: Dict[str, Any]) -> str:
        return " ".join(
            [
                candidate.get("candidate_text", ""),
                " ".join(candidate.get("trigger_keywords", [])),
                json.dumps(candidate.get("metrics", {}), ensure_ascii=False),
            ]
        )

    def _retrieve_text(self, query: str, top_k: int = 3) -> List[RetrievalHit]:
        if getattr(self.knowledge_repository, "supports_semantic_search", False):
            return [
                RetrievalHit(
                    event_type=hit.record.to_dict(),
                    score=float(hit.score),
                    tfidf_score=0.0,
                    embedding_score=float(hit.score),
                    method=hit.method,
                )
                for hit in self.knowledge_repository.search(query, top_k=top_k)
            ]

        recall_k = max(top_k, 5)
        tfidf_hits = {
            index: score
            for index, score in self.index.search(query, top_k=recall_k)
        }
        embedding_hits = self._embedding_scores(query)

        candidate_indices = set(tfidf_hits)
        candidate_indices.update(embedding_hits)
        hits: List[RetrievalHit] = []
        for index in candidate_indices:
            if index >= len(self.event_types):
                continue
            tfidf_score = tfidf_hits.get(index, 0.0)
            embedding_score = embedding_hits.get(index)
            if embedding_score is None:
                combined = tfidf_score
                method = "tfidf"
            else:
                combined = max(
                    tfidf_score,
                    tfidf_score * (1 - self.embedding_weight)
                    + embedding_score * self.embedding_weight,
                )
                method = "tfidf+embedding"
            hits.append(
                RetrievalHit(
                    event_type=self.event_types[index],
                    score=float(combined),
                    tfidf_score=float(tfidf_score),
                    embedding_score=float(embedding_score)
                    if embedding_score is not None
                    else None,
                    method=method,
                )
            )

        return sorted(hits, key=lambda item: item.score, reverse=True)[:top_k]

    def _fill_event(
        self,
        candidate: Dict[str, Any],
        hit: RetrievalHit,
    ) -> Dict[str, Any]:
        self.event_counter += 1
        event_type = hit.event_type
        dates = candidate.get("dates") or []
        metrics = candidate.get("metrics") or {}
        entities = candidate.get("involved_entities") or []
        entity_prefix = self._entity_prefix(entities)
        description = self._clean_event_description(candidate["candidate_text"])
        rule = event_type.get("rule") or "；".join(event_type.get("rules") or [])

        reason_steps = [
            f"候选事件来自 {candidate.get('source_text_name')} / {candidate.get('source_section')}",
            f"命中关键词: {', '.join(candidate.get('trigger_keywords') or []) or '无显式关键词'}",
            (
                f"知识库 Top1={event_type.get('name')}，融合分数={hit.score:.4f}，"
                f"TF-IDF={hit.tfidf_score:.4f}，"
                f"Embedding={hit.embedding_score:.4f}，阈值={self.risk_threshold:.4f}"
                if hit.embedding_score is not None
                else f"知识库 Top1={event_type.get('name')}，TF-IDF 分数={hit.score:.4f}，阈值={self.risk_threshold:.4f}"
            ),
        ]
        if metrics:
            reason_steps.append(f"抽取到指标: {json.dumps(metrics, ensure_ascii=False)}")

        return {
            "event_id": f"{self.event_counter:07d}",
            "event_name": f"{entity_prefix}{event_type.get('name')}事件",
            "event_type_id": event_type.get("id"),
            "event_type": event_type.get("name"),
            "event_description": description,
            "event_start_date": min(dates) if dates else None,
            "event_end_date": max(dates) if dates else None,
            "recognition_rule": (
                f"知识库标记：已有；知识库事件类型："
                f"{event_type.get('id')}/{event_type.get('name')}；"
                f"成立规则：本地兜底核对知识库类型的区别性事实特征，并结合相似度选择最匹配条目；"
                f"事实依据：{description}"
            ),
            "product_service": self._infer_product_service(description),
            "value_tool": self._infer_value_tool(description),
            "risk_type": self._normalize_risk_type(
                event_type.get("risk_type"),
                event_type.get("category"),
            ),
            "risk_indicator": self._risk_indicator(metrics, event_type),
            "disposition_measures": event_type.get("disposition_hint"),
            "source_text_name": candidate.get("source_text_name"),
            "source_section": candidate.get("source_section"),
            "source_text": description,
            "involved_entities": entities,
            "metrics": metrics,
            "confidence_score": round(min(0.98, hit.score + 0.35), 6),
            "reason": "；".join(reason_steps),
            "reason_steps": reason_steps,
            "retrieval_method": hit.method,
            "needs_llm_review": False,
            "llm_reviewed": False,
            "duplicate_of": None,
        }

    def _fill_new_event(
        self,
        candidate: Dict[str, Any],
        top_hit: Optional[RetrievalHit],
    ) -> Dict[str, Any]:
        self.event_counter += 1
        description = self._clean_event_description(
            candidate.get("candidate_text") or ""
        )
        entities = candidate.get("involved_entities") or []
        dates = candidate.get("dates") or []
        metrics = candidate.get("metrics") or {}
        event_type = self._summarize_new_event_type(description)
        entity_prefix = self._entity_prefix(entities)
        closest = (
            f"；最相近知识库候选：{top_hit.event_type.get('id')}/"
            f"{top_hit.event_type.get('name')}，仅作召回参考"
            if top_hit
            else ""
        )
        recognition_rule = (
            "知识库标记：新增；知识库事件类型：无；"
            "成立规则：满足可疑风险事件定义但未完整满足已有类型必要规则；"
            f"事实依据：{description}{closest}"
        )
        reason_steps = [
            f"候选事件来自 {candidate.get('source_text_name')} / 全文",
            "知识库相似度仅用于召回，未作为事件过滤条件",
            "未能保守确认完整满足已有知识库规则，按新增事件保留",
        ]
        return {
            "event_id": f"{self.event_counter:07d}",
            "event_name": f"{entity_prefix}{event_type}事件（新增）",
            "event_type_id": None,
            "event_type": event_type,
            "event_description": description,
            "event_start_date": min(dates) if dates else "",
            "event_end_date": max(dates) if dates else "",
            "recognition_rule": recognition_rule,
            "product_service": self._infer_product_service(description) or "",
            "value_tool": self._infer_value_tool(description) or "",
            "risk_type": self._normalize_risk_type(None, None),
            "risk_indicator": self._risk_indicator(metrics, {}) or "",
            "disposition_measures": "",
            "source_text_name": candidate.get("source_text_name"),
            "source_section": "全文",
            "source_text": description,
            "involved_entities": entities,
            "metrics": metrics,
            "confidence_score": 0.55,
            "reason": "；".join(reason_steps),
            "reason_steps": reason_steps,
            "retrieval_method": (
                top_hit.method if top_hit else "none"
            ),
            "needs_llm_review": True,
            "llm_reviewed": False,
            "llm_review_reason": "本地兜底无法完整执行知识库 rule 判定，建议下游复核",
            "duplicate_of": None,
        }

    def _local_existing_match(
        self,
        candidate: Dict[str, Any],
        hit: RetrievalHit,
    ) -> bool:
        """Conservative offline match based on distinctive KB cue groups."""

        return self._hit_satisfies_local_cues(
            candidate.get("candidate_text") or "",
            hit,
        )

    def _select_local_kb_match(
        self,
        candidate: Dict[str, Any],
        hits: List[RetrievalHit],
    ) -> Optional[RetrievalHit]:
        text = candidate.get("candidate_text") or ""
        eligible = [
            hit
            for hit in hits
            if self._hit_satisfies_local_cues(text, hit)
        ]
        return max(eligible, key=lambda hit: hit.score, default=None)

    def _hit_satisfies_local_cues(
        self,
        text: str,
        hit: RetrievalHit,
    ) -> bool:
        event_type_id = str(hit.event_type.get("id") or "")
        event_type_name = str(hit.event_type.get("name") or "")
        if event_type_name and event_type_name in text:
            return True
        cue_groups = LOCAL_KB_CUE_GROUPS.get(event_type_id)
        if not cue_groups:
            return False
        return all(any(cue in text for cue in group) for group in cue_groups)

    def _entity_prefix(self, entities: Sequence[str]) -> str:
        values = [str(entity_id) for entity_id in entities if entity_id]
        return "、".join(values[:3])

    def _resolve_event_entities(
        self,
        text: str,
        entity_catalog: Sequence[Dict[str, str]],
    ) -> List[str]:
        """Resolve an event to exact entity IDs using IDs and known aliases."""

        resolved = self._extract_entity_ids(text)
        resolved_set = set(resolved)
        for entity in entity_catalog:
            entity_id = str(entity.get("node_id") or "")
            if not entity_id or entity_id in resolved_set:
                continue
            aliases = [
                str(entity.get("node_name") or ""),
                *str(entity.get("aliases") or "").split("、"),
            ]
            if any(
                alias
                and len(alias) >= 2
                and alias != entity_id
                and alias in text
                for alias in aliases
            ):
                resolved.append(entity_id)
                resolved_set.add(entity_id)

        if not resolved:
            customers = [
                str(entity.get("node_id") or "")
                for entity in entity_catalog
                if entity.get("node_type") == "客户" and entity.get("node_id")
            ]
            if len(customers) == 1:
                resolved.append(customers[0])
        return resolved

    def _canonical_existing_event_name(
        self,
        entities: Sequence[str],
        event_type: str,
    ) -> str:
        return f"{self._entity_prefix(entities)}{event_type}事件"

    def _clean_event_description(self, text: str) -> str:
        """Remove report headings/serial numbers and return one prose paragraph."""

        cleaned = re.sub(r"\s+", " ", text or "").strip()
        heading_patterns = (
            r"^(?:第?[一二三四五六七八九十百]+[章节部分]?|[一二三四五六七八九十百]+)[、.．]\s*[^：:。；]{0,50}[：:]\s*",
            r"^【[一二三四五六七八九十百]+】\s*[^：:。；]{0,50}[：:]\s*",
            r"^[（(]\s*(?:\d+|[一二三四五六七八九十百]+)\s*[）)]\s*[^：:，,。；]{0,35}[：:，,]\s*",
            r"^\d+[、.．]\s*[^：:，,。；]{0,35}[：:，,]\s*",
        )
        changed = True
        while changed and cleaned:
            changed = False
            for pattern in heading_patterns:
                updated = re.sub(pattern, "", cleaned, count=1)
                if updated != cleaned:
                    cleaned = updated.strip()
                    changed = True
        return cleaned

    def _is_generic_event_label(self, value: str) -> bool:
        normalized = re.sub(r"[\s（）()]+", "", value or "")
        return (
            not normalized
            or "待归类" in normalized
            or normalized in {"异常交易", "异常交易事件", "业务事件", "相关事件"}
        )

    def _summarize_new_event_type(
        self,
        text: str,
        proposed_type: str = "",
        proposed_name: str = "",
    ) -> str:
        """Produce a meaningful non-KB label; never expose “待归类”."""

        if proposed_type and not self._is_generic_event_label(proposed_type):
            if proposed_type not in {
                str(item.get("name") or "")
                for item in self.event_types
            }:
                return re.sub(r"[（(]新增[）)]$", "", proposed_type).strip()

        patterns = (
            (("交易对手", "对手"), "交易对手结构异常"),
            (("共同设备", "共享设备", "共同IP"), "关联主体共同控制"),
            (("回流", "循环"), "资金循环回流"),
            (("跨行",), "跨行资金转移"),
            (("信用卡", "POS"), "信用卡资金活动"),
            (("交易规模", "交易金额"), "异常交易规模"),
            (("尽职调查", "尽调"), "客户尽职调查"),
            (("冻结", "限制", "限额"), "账户交易限制"),
            (("交易", "转账", "收款", "付款"), "账户资金活动"),
        )
        for keywords, summary in patterns:
            if any(keyword in text for keyword in keywords):
                return summary

        cleaned_name = re.sub(r"[（(]新增[）)]$", "", proposed_name or "").strip()
        cleaned_name = re.sub(r"^(?:相关主体|未知实体)", "", cleaned_name)
        cleaned_name = re.sub(r"事件$", "", cleaned_name)
        if cleaned_name and not self._is_generic_event_label(cleaned_name):
            return cleaned_name[:24]
        return "其他可解释业务行为"

    def _is_observation_only_event(self, event: Dict[str, Any]) -> bool:
        """Reject one-sided funding summaries that do not explain suspiciousness."""

        label = " ".join(
            (
                str(event.get("event_name") or ""),
                str(event.get("event_type") or ""),
            )
        )
        description = str(event.get("event_description") or "")
        return self._is_observation_only_text(description, label=label)

    def _is_observation_only_text(self, text: str, label: str = "") -> bool:
        """Return True for descriptive aggregates that are evidence, not events.

        The guard is intentionally narrow: it targets source/destination summaries
        and one-sided inflow/outflow statistics. A complete follow-through pattern,
        such as funds being aggregated and then rapidly transferred, remains valid.
        """

        combined = f"{label} {text}"
        summary_labels = (
            "资金来源汇总",
            "资金去向汇总",
            "资金流入汇总",
            "资金流出汇总",
            "资金流向汇总",
            "全案资金汇总",
            "收款汇总",
            "付款汇总",
            "资金来源统计",
            "资金去向统计",
            "关联客户内部转入",
            "关联客户内部转出",
            "内部转入汇总",
            "内部转出汇总",
        )
        summary_cues = (
            "占流入金额",
            "占流出金额",
            "资金主要来源",
            "资金主要去向",
            "外部个人分散转入",
            "外部个人分散转出",
            "资金来源分析",
            "资金去向分析",
            "全案资金流入",
            "全案资金流出",
            "净流入",
            "净流出",
            "净留存",
            "关联客户内部转入",
            "关联客户内部转出",
        )
        looks_like_summary = any(
            cue in combined for cue in (*summary_labels, *summary_cues)
        )
        if not looks_like_summary:
            return False
        return not self._has_complete_risk_mechanism(text)

    def _has_complete_risk_mechanism(self, text: str) -> bool:
        normalized = re.sub(r"\s+", "", text or "")
        explicit_patterns = (
            "快进快出",
            "循环回流",
            "资金回流",
            "多跳资金转移",
            "集中归集后分散转出",
            "分散转入后集中转出",
            "归集后快速转出",
            "过渡账户",
            "过渡性账户",
            "余额快速归零",
        )
        if any(pattern in normalized for pattern in explicit_patterns):
            return True
        sequential_patterns = (
            r"(?:转入|收款|归集|汇集).{0,45}(?:随后|后续|继而|之后|当日|24小时内|短期内|快速).{0,35}(?:转出|付款|提现|取现|分散|回流|兑换)",
            r"(?:多个来源|多名个人|分散转入).{0,45}(?:集中|快速|随即).{0,25}(?:转出|提现|取现|兑换)",
        )
        return any(re.search(pattern, normalized) for pattern in sequential_patterns)

    def _deduplicate(self, events: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        unique: List[Dict[str, Any]] = []
        report: List[Dict[str, Any]] = []

        for event in events:
            duplicate_index: Optional[int] = None
            best_similarity = 0.0
            review_target: Optional[str] = None
            for index, existing in enumerate(unique):
                similarity = self._event_similarity(event, existing)
                best_similarity = max(best_similarity, similarity)
                if similarity >= self.dedup_threshold:
                    review = self._llm_duplicate_review(existing, event, similarity)
                    event["needs_llm_review"] = True
                    existing["needs_llm_review"] = True
                    event["llm_reviewed"] = review.get("available", False) and review.get("is_duplicate") is not None
                    existing["llm_reviewed"] = event["llm_reviewed"]
                    if review.get("is_duplicate") is False:
                        report.append(
                            {
                                "event_id": event["event_id"],
                                "maybe_duplicate_with": existing["event_id"],
                                "similarity": round(similarity, 6),
                                "llm_review": review,
                                "action": "文本相似度超过阈值，但大模型二确判断不重复，保留两个事件",
                            }
                        )
                        continue
                    duplicate_index = index
                    event["llm_review_reason"] = review.get("reason")
                    existing["llm_review_reason"] = review.get("reason")
                    break
                if similarity >= self.llm_review_threshold:
                    event["needs_llm_review"] = True
                    existing["needs_llm_review"] = True
                    review_target = existing["event_id"]

            if duplicate_index is None:
                unique.append(event)
                if review_target:
                    report.append(
                        {
                            "event_id": event["event_id"],
                            "maybe_duplicate_with": review_target,
                            "similarity": round(best_similarity, 6),
                            "action": "相似度进入大模型二确区间；当前未配置模型，保留并标记 needs_llm_review",
                        }
                    )
                continue

            existing = unique[duplicate_index]
            keeper, removed = self._choose_keeper(existing, event)
            keeper["reason_steps"].append(
                f"事件去重: 与 {removed['event_id']} 相似度 {best_similarity:.4f}，保留置信度更高事件"
            )
            if removed.get("llm_review_reason"):
                keeper["reason_steps"].append(f"大模型二确: {removed['llm_review_reason']}")
            removed["duplicate_of"] = keeper["event_id"]
            unique[duplicate_index] = keeper
            report.append(
                {
                    "kept_event_id": keeper["event_id"],
                    "removed_event_id": removed["event_id"],
                    "similarity": round(best_similarity, 6),
                    "llm_reviewed": bool(removed.get("llm_reviewed") or keeper.get("llm_reviewed")),
                    "llm_review_reason": removed.get("llm_review_reason") or keeper.get("llm_review_reason"),
                    "action": "文本相似度超过去重阈值，经大模型二确或本地兜底后合并为同一风险事件",
                }
            )

        return unique, report

    def _choose_keeper(self, left: Dict[str, Any], right: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        if left.get("confidence_score", 0.0) >= right.get("confidence_score", 0.0):
            return left, right
        return right, left

    def _event_similarity(self, left: Dict[str, Any], right: Dict[str, Any]) -> float:
        left_type_key = left.get("event_type_id") or left.get("event_type")
        right_type_key = right.get("event_type_id") or right.get("event_type")
        if left_type_key != right_type_key:
            return 0.0
        left_text = " ".join([left.get("event_description") or "", left.get("event_name") or ""])
        right_text = " ".join([right.get("event_description") or "", right.get("event_name") or ""])
        text_similarity = SequenceMatcher(None, left_text, right_text).ratio()
        left_entities = set(left.get("involved_entities") or [])
        right_entities = set(right.get("involved_entities") or [])
        entity_similarity = (
            len(left_entities & right_entities) / len(left_entities | right_entities)
            if left_entities or right_entities
            else 0.0
        )
        return text_similarity * 0.75 + entity_similarity * 0.25

    def _llm_duplicate_review(
        self,
        existing: Dict[str, Any],
        incoming: Dict[str, Any],
        similarity: float,
    ) -> Dict[str, Any]:
        review = self.llm_client.judge_duplicate_events(existing, incoming, similarity)
        if review.get("is_duplicate") is None:
            # The workflow remains usable without network/SDK. High similarity still
            # falls back to deterministic merge, and the report keeps the reason.
            return {
                **review,
                "is_duplicate": True,
                "fallback": "LLM unavailable or undecidable; deterministic high-similarity merge applied",
            }
        return review

    def _analysis_content(self, text: str) -> str:
        marker = "【分析文本】"
        if marker not in text:
            return text
        return text.split(marker, 1)[1]

    def _embedding_scores(self, query: str) -> Dict[int, float]:
        if not self.llm_client.embedding_available:
            return {}
        kb_embeddings = self._ensure_kb_embeddings()
        if not kb_embeddings:
            return {}
        query_embeddings = self.llm_client.embed_texts([query])
        if not query_embeddings:
            return {}
        query_vector = query_embeddings[0]
        scores = {
            index: self._cosine_dense(query_vector, kb_vector)
            for index, kb_vector in enumerate(kb_embeddings)
        }
        return dict(sorted(scores.items(), key=lambda item: item[1], reverse=True)[:5])

    def _ensure_kb_embeddings(self) -> Optional[List[List[float]]]:
        if self._kb_embeddings is not None:
            return self._kb_embeddings
        embeddings = self.llm_client.embed_texts(self.kb_documents)
        self._kb_embeddings = embeddings
        return embeddings

    def _cosine_dense(self, left: List[float], right: List[float]) -> float:
        if not left or not right or len(left) != len(right):
            return 0.0
        dot = sum(a * b for a, b in zip(left, right))
        left_norm = math.sqrt(sum(a * a for a in left)) or 1.0
        right_norm = math.sqrt(sum(b * b for b in right)) or 1.0
        raw = dot / (left_norm * right_norm)
        return max(0.0, min(1.0, (raw + 1.0) / 2.0))

    def _kb_document(self, event_type: Dict[str, Any]) -> str:
        parts = [
            event_type.get("name", ""),
            event_type.get("description", ""),
            event_type.get("category", ""),
            event_type.get("rule", ""),
            " ".join(event_type.get("keywords", [])),
            " ".join(event_type.get("aliases", [])),
            " ".join(event_type.get("rules", [])),
        ]
        return " ".join(parts)

    def _risk_keywords(self) -> List[str]:
        keywords: List[str] = []
        for event_type in self.event_types:
            keywords.extend(event_type.get("keywords", []))
            keywords.extend(event_type.get("aliases", []))
            if event_type.get("name"):
                keywords.append(event_type["name"])
                keywords.extend(
                    token
                    for token in re.split(r"[、或与及/（）()\s]+", event_type["name"])
                    if len(token) >= 2
                )
        return sorted(set(keywords), key=len, reverse=True)

    def _split_atomic_units(self, text: str) -> List[str]:
        parts = re.split(r"(?<=[。；;])|\n", text)
        units: List[str] = []
        for part in parts:
            part = part.strip()
            if not part:
                continue
            if len(part) > 260:
                units.extend(item.strip() for item in re.split(r"[,，]", part) if item.strip())
            else:
                units.append(part)
        return units

    def _looks_like_event(self, text: str, keyword_hits: List[str]) -> bool:
        event_words = (
            "交易",
            "转账",
            "转入",
            "转出",
            "资金",
            "收款",
            "付款",
            "存款",
            "取现",
            "归集",
            "分散",
            "冻结",
            "限制",
            "查询",
            "调查",
            "尽调",
            "评级",
            "兑换",
            "申购",
            "赎回",
            "支付",
            "控制",
            "共用",
            "登录",
        )
        event_word_count = sum(1 for word in event_words if word in text)
        has_metric = bool(re.search(r"\d+(?:\.\d+)?\s*(?:笔|万元|元|条|次)", text))
        has_time = bool(
            re.search(
                r"\d{4}(?:[-./年]\d{1,2})(?:[-./月]\d{1,2}日?)?|"
                r"\d{1,2}:\d{2}",
                text,
            )
        )
        has_dynamic_pattern = any(
            word in text
            for word in (
                "快进快出",
                "集中交易",
                "短期内",
                "连续",
                "频繁",
                "多次",
                "随后",
                "期间",
                "已经",
                "实施",
            )
        )
        return bool(
            (keyword_hits and event_word_count)
            or (event_word_count and has_metric)
            or (event_word_count >= 2 and (has_time or has_dynamic_pattern))
        )

    def _extract_entity_ids(self, text: str) -> List[str]:
        return sorted(
            set(
                re.findall(
                    r"\[((?:CUST|ACCT|ORG|PER|PAY|OTH)[0-9A-Za-z._-]+)\]",
                    text,
                )
            )
        )

    def _extract_metrics(self, text: str) -> Dict[str, Any]:
        metrics: Dict[str, Any] = {}
        transaction_counts = [int(value) for value in re.findall(r"(\d+)\s*笔", text)]
        if transaction_counts:
            metrics["transaction_counts"] = transaction_counts
            metrics["max_transaction_count"] = max(transaction_counts)

        amount_wan = [float(value) for value in re.findall(r"(?:金额|涉及金额|交易金额|累计转账|累计转入金额|累计转出金额)[^\d]{0,8}(\d+(?:\.\d+)?)\s*万元", text)]
        if amount_wan:
            metrics["amount_wan"] = amount_wan
            metrics["max_amount_wan"] = max(amount_wan)

        yuan_amounts = [int(value) for value in re.findall(r"(\d{2,6})\s*元", text)]
        if yuan_amounts:
            metrics["yuan_amounts"] = sorted(set(yuan_amounts))

        time_windows = re.findall(r"\d{1,2}:\d{2}\s*[-至到—]\s*(?:次日)?\d{1,2}:\d{2}", text)
        if time_windows:
            metrics["time_windows"] = time_windows

        if "不留余额" in text or "极少余额" in text:
            metrics["balance_pattern"] = "不留余额或余额极少"
        if "快进快出" in text or "快速转出" in text:
            metrics["flow_pattern"] = "快进快出"
        return metrics

    def _extract_dates(self, text: str) -> List[str]:
        dates: List[str] = []
        matches = re.findall(
            r"(\d{4})[-./](\d{1,2})[-./](\d{1,2})|"
            r"(\d{4})年(\d{1,2})月(\d{1,2})日",
            text,
        )
        for match in matches:
            year, month, day = match[:3] if match[0] else match[3:]
            normalized_day = int(day)
            if 1 <= int(month) <= 12 and 1 <= normalized_day <= 31:
                dates.append(
                    f"{int(year):04d}-{int(month):02d}-{normalized_day:02d}"
                )
        return sorted(set(dates))

    def _infer_product_service(self, text: str) -> Optional[str]:
        if "电子银行" in text:
            return "电子银行"
        if "第三方支付" in text or "支付宝" in text or "微信" in text:
            return "第三方支付"
        if "储蓄" in text or "账户" in text:
            return "个人储蓄账户"
        return None

    def _infer_value_tool(self, text: str) -> Optional[str]:
        if "银行卡" in text or "卡" in text:
            return "银行卡"
        if "支付宝" in text or "微信" in text:
            return "第三方支付账户"
        if "账户" in text:
            return "银行账户"
        return None

    def _risk_indicator(self, metrics: Dict[str, Any], event_type: Dict[str, Any]) -> Optional[str]:
        names: List[str] = []
        if "max_transaction_count" in metrics:
            names.append(f"交易笔数最高 {metrics['max_transaction_count']} 笔")
        if "max_amount_wan" in metrics:
            names.append(f"金额最高 {metrics['max_amount_wan']} 万元")
        if "time_windows" in metrics:
            names.append(f"时间窗口 {', '.join(metrics['time_windows'])}")
        return "；".join(names) if names else event_type.get("indicator")

    def _normalize_risk_type(
        self,
        risk_type: Optional[str],
        category: Optional[str],
    ) -> str:
        allowed = ("客户风险", "渠道风险", "产品风险", "地域风险")
        if risk_type:
            matched = [item for item in allowed if item in risk_type]
            if matched:
                return "/".join(matched)
        category_text = category or ""
        if any(word in category_text for word in ("地域", "跨境", "跨区域")):
            return "地域风险"
        if any(word in category_text for word in ("渠道", "支付", "设备", "IP")):
            return "渠道风险"
        if any(word in category_text for word in ("产品", "保险", "信用卡", "贷款", "投资")):
            return "产品风险"
        return "客户风险"
