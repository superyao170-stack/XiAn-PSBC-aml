"""Chinese coreference resolution with CoreNLP candidates and DeepSeek review."""

from __future__ import annotations

import json
import os
import re
from collections import defaultdict
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

try:
    import httpx
except ImportError:  # pragma: no cover - optional at import time
    httpx = None  # type: ignore[assignment]

from datagraph_bank.llm import DeepSeekLLMClient
from datagraph_bank.concurrency import LocalServiceGate


CoreNLPCallable = Callable[[str], Dict[str, Any]]


class CoreferenceResolver:
    """Resolve Chinese references before entity enhancement.

    CoreNLP generates mention chains and a locally explainable confidence for
    each reference. Candidates above ``auto_threshold`` are applied directly;
    candidates from ``review_threshold`` through ``auto_threshold`` (inclusive)
    are sent to DeepSeek for a second opinion; candidates below
    ``review_threshold`` are retained as standalone entities instead of being
    forced into an existing entity chain.
    """

    REFERENCE_PATTERN = re.compile(
        r"该客户|上述客户|客户本人|该账户|上述账户|其账户|其本人|"
        r"该线索|上述线索|本案例|前者|后者|其中|其(?!他|中|余|整数)"
    )
    CUSTOMER_REFERENCES = {"该客户", "上述客户", "客户本人", "其本人"}
    ACCOUNT_REFERENCES = {"该账户", "上述账户"}

    def __init__(
        self,
        llm_client: DeepSeekLLMClient,
        corenlp_url: str | None = None,
        auto_threshold: float = 0.80,
        review_threshold: float = 0.55,
        enabled: bool = True,
        timeout: float = 120.0,
        corenlp_client: CoreNLPCallable | None = None,
        service_gate: LocalServiceGate | None = None,
    ) -> None:
        self.llm_client = llm_client
        self.corenlp_url = (
            corenlp_url
            or os.getenv("CORENLP_URL")
            or "http://localhost:9000"
        ).rstrip("/")
        self.corenlp_version = os.getenv("CORENLP_VERSION", "4.5.10")
        self.auto_threshold = self._validate_threshold(auto_threshold, "auto_threshold")
        self.review_threshold = self._validate_threshold(
            review_threshold,
            "review_threshold",
        )
        if self.review_threshold > self.auto_threshold:
            raise ValueError("review_threshold must not exceed auto_threshold")
        self.enabled = enabled
        self.timeout = timeout
        self.corenlp_client = corenlp_client
        self.service_gate = service_gate

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        texts = state.get("preprocessed_texts") or {}
        entity_catalog = self._build_entity_catalog(state)
        resolved_texts: Dict[str, str] = {}
        resolved_sections: Dict[str, List[Dict[str, Any]]] = {}
        all_chains: List[Dict[str, Any]] = []
        all_resolutions: List[Dict[str, Any]] = []
        all_review_calls: List[Dict[str, Any]] = []
        engine_documents: Dict[str, Dict[str, Any]] = {}

        for text_name, text in texts.items():
            annotation: Dict[str, Any] | None = None
            engine_error = ""
            engine = f"corenlp_{self.corenlp_version}_chinese_neural"
            if self.enabled:
                try:
                    annotation = self._annotate(text)
                except Exception as exc:  # network/runtime dependent
                    engine_error = str(exc)
            else:
                engine_error = "CoreNLP disabled by CLI option"

            if annotation and isinstance(annotation.get("corefs"), dict):
                chains, resolutions = self._from_corenlp(
                    text_name,
                    text,
                    annotation,
                    entity_catalog,
                )
                candidate_source = "corenlp"
            else:
                chains, resolutions = self._from_local_fallback(
                    text_name,
                    text,
                    entity_catalog,
                )
                candidate_source = "local_fallback"
                engine = "local_reference_fallback"

            review_call = self._review_medium_confidence(
                text_name=text_name,
                text=text,
                resolutions=resolutions,
                chains=chains,
                entity_catalog=entity_catalog,
            )
            if review_call is not None:
                all_review_calls.append(review_call)

            self._finalize_resolutions(resolutions, chains, entity_catalog)
            resolved_text = self._apply_resolutions(text, resolutions)
            resolved_texts[text_name] = resolved_text
            resolved_sections[text_name] = self._resolved_document_units(
                state,
                text_name,
                resolved_text,
            )
            all_chains.extend(chains)
            all_resolutions.extend(resolutions)
            engine_documents[text_name] = {
                "engine": engine,
                "candidate_source": candidate_source,
                "engine_error": engine_error,
                "chain_count": len(chains),
                "resolution_count": len(resolutions),
                "auto_pass_count": sum(
                    1 for item in resolutions if item["status"] == "auto_pass"
                ),
                "llm_review_candidate_count": sum(
                    1
                    for item in resolutions
                    if item.get("llm_review_required")
                ),
                "llm_pass_count": sum(
                    1 for item in resolutions if item["status"] == "llm_pass"
                ),
                "standalone_entity_count": sum(
                    1
                    for item in resolutions
                    if item["status"] == "standalone_entity"
                ),
                "manual_review_count": sum(
                    1 for item in resolutions if item["status"] == "manual_review"
                ),
            }

        review_queue = [
            self._manual_review_item(item)
            for item in all_resolutions
            if item.get("status") == "manual_review"
        ]
        resolved_count = sum(
            1
            for item in all_resolutions
            if item.get("status") in {"auto_pass", "llm_pass"}
        )
        standalone_entities = [
            self._standalone_entity_item(item)
            for item in all_resolutions
            if item.get("status") == "standalone_entity"
        ]
        return {
            "coreference_resolved_texts": resolved_texts,
            "coreference_resolved_sections": resolved_sections,
            "coreference_chains": all_chains,
            "coreference_resolutions": all_resolutions,
            "coreference_standalone_entities": standalone_entities,
            "coreference_review_queue": review_queue,
            "coreference_review_calls": all_review_calls,
            "coreference_engine_status": {
                "primary_engine": f"Stanford CoreNLP {self.corenlp_version} Chinese neural coreference",
                "corenlp_url": self.corenlp_url,
                "enabled": self.enabled,
                "documents": engine_documents,
            },
            "coreference_report": {
                "text_count": len(texts),
                "chain_count": len(all_chains),
                "reference_count": len(all_resolutions),
                "resolved_count": resolved_count,
                "auto_pass_count": sum(
                    1 for item in all_resolutions if item.get("status") == "auto_pass"
                ),
                "llm_review_candidate_count": sum(
                    1
                    for item in all_resolutions
                    if item.get("llm_review_required")
                ),
                "llm_pass_count": sum(
                    1 for item in all_resolutions if item.get("status") == "llm_pass"
                ),
                "standalone_entity_count": len(standalone_entities),
                "manual_review_count": len(review_queue),
                "auto_threshold": self.auto_threshold,
                "review_threshold": self.review_threshold,
                "local_confidence_band_counts": {
                    "high": sum(
                        1
                        for item in all_resolutions
                        if item.get("local_confidence_band") == "high"
                    ),
                    "medium": sum(
                        1
                        for item in all_resolutions
                        if item.get("local_confidence_band") == "medium"
                    ),
                    "low": sum(
                        1
                        for item in all_resolutions
                        if item.get("local_confidence_band") == "low"
                    ),
                },
                "policy": (
                    "CoreNLP候选置信度超过80%自动通过；55%至80%（含边界）"
                    "由DeepSeek v4 flash复核；低于55%不复核、不强制指向已有"
                    "实体，直接保留为独立实体"
                ),
            },
        }

    def _annotate(self, text: str) -> Dict[str, Any]:
        def request() -> Dict[str, Any]:
            if self.corenlp_client is not None:
                return self.corenlp_client(text)
            if httpx is None:
                raise RuntimeError("httpx is not installed")

            properties = {
                # CoreNLP 4.5.10's Chinese neural coreference pipeline depends on
                # the constituency parser and the RULE mention detector. The DEP
                # detector attempts to deserialize an obsolete hcoref class from
                # the Chinese model jar and returns HTTP 500.
                "annotators": "tokenize,ssplit,pos,lemma,ner,parse,coref",
                "coref.algorithm": "neural",
                "coref.md.type": "RULE",
                "coref.md.liberalMD": "true",
                "coref.language": "zh",
                "pipelineLanguage": "zh",
                "outputFormat": "json",
            }
            response = httpx.post(
                self.corenlp_url,
                params={"properties": json.dumps(properties, ensure_ascii=False)},
                content=text.encode("utf-8"),
                headers={"Content-Type": "text/plain; charset=utf-8"},
                timeout=self.timeout,
            )
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload, dict):
                raise RuntimeError("CoreNLP returned a non-object JSON response")
            return payload

        if self.service_gate is not None:
            return self.service_gate.run(request)
        return request()

    def _from_corenlp(
        self,
        text_name: str,
        text: str,
        annotation: Dict[str, Any],
        entity_catalog: List[Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        sentences = annotation.get("sentences") or []
        raw_corefs = annotation.get("corefs") or {}
        chains: List[Dict[str, Any]] = []
        resolutions: List[Dict[str, Any]] = []

        for raw_chain_id, raw_mentions in raw_corefs.items():
            if not isinstance(raw_mentions, list) or not raw_mentions:
                continue
            mentions = [
                self._normalize_corenlp_mention(text, sentences, item)
                for item in raw_mentions
                if isinstance(item, dict)
            ]
            mentions = [item for item in mentions if item is not None]
            if not mentions:
                continue

            canonical = self._select_canonical_mention(mentions)
            entity = self._match_entity(
                canonical.get("text") or "",
                entity_catalog,
            )
            chain_id = f"COREF_{text_name}_{raw_chain_id}"
            chain = self._chain_record(
                chain_id=chain_id,
                text_name=text_name,
                canonical=canonical,
                entity=entity,
                mentions=mentions,
                source="corenlp",
            )
            chains.append(chain)

            for mention in mentions:
                mention_text = str(mention.get("text") or "")
                if mention is canonical or not self._is_reference(mention_text):
                    continue
                confidence, factors = self._score_corenlp_resolution(
                    mention=mention,
                    canonical=canonical,
                    entity=entity,
                    chain_size=len(mentions),
                )
                resolutions.append(
                    self._resolution_record(
                        text_name=text_name,
                        text=text,
                        mention=mention,
                        chain=chain,
                        confidence=confidence,
                        confidence_factors=factors,
                        source="corenlp",
                    )
                )
        return chains, self._deduplicate_resolutions(resolutions)

    def _normalize_corenlp_mention(
        self,
        text: str,
        sentences: List[Dict[str, Any]],
        mention: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        try:
            sentence_index = int(mention.get("sentNum", 1)) - 1
            start_token = int(mention.get("startIndex", 1)) - 1
            end_token_exclusive = int(mention.get("endIndex", start_token + 2)) - 1
            tokens = (sentences[sentence_index] or {}).get("tokens") or []
            first = tokens[start_token]
            last = tokens[max(start_token, end_token_exclusive - 1)]
            begin = int(first["characterOffsetBegin"])
            end = int(last["characterOffsetEnd"])
        except (IndexError, KeyError, TypeError, ValueError):
            return None
        if begin < 0 or end <= begin or end > len(text):
            return None
        surface = text[begin:end]
        return {
            "text": surface or str(mention.get("text") or "").replace(" ", ""),
            "corenlp_text": mention.get("text") or "",
            "mention_type": mention.get("type") or "",
            "sentence_index": sentence_index,
            "start_char": begin,
            "end_char": end,
            "is_representative": bool(mention.get("isRepresentativeMention")),
            "number": mention.get("number") or "",
            "gender": mention.get("gender") or "",
            "animacy": mention.get("animacy") or "",
        }

    def _select_canonical_mention(
        self,
        mentions: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        representatives = [
            item
            for item in mentions
            if item.get("is_representative") and not self._is_reference(item.get("text") or "")
        ]
        if representatives:
            return max(representatives, key=lambda item: len(item.get("text") or ""))
        named = [
            item
            for item in mentions
            if not self._is_reference(item.get("text") or "")
        ]
        if named:
            return max(named, key=lambda item: len(item.get("text") or ""))
        return mentions[0]

    def _score_corenlp_resolution(
        self,
        mention: Dict[str, Any],
        canonical: Dict[str, Any],
        entity: Dict[str, Any] | None,
        chain_size: int,
    ) -> Tuple[float, List[str]]:
        score = 0.54
        factors = ["CoreNLP神经共指模型将该提及归入候选实体链"]
        sentence_distance = abs(
            int(mention.get("sentence_index") or 0)
            - int(canonical.get("sentence_index") or 0)
        )
        if entity:
            score += 0.14
            factors.append("代表提及与结构化实体唯一匹配")
        if sentence_distance <= 1:
            score += 0.08
            factors.append("指代与代表提及相距不超过一句")
        if self._reference_type_compatible(
            str(mention.get("text") or ""),
            str((entity or {}).get("entity_type") or ""),
        ):
            score += 0.05
            factors.append("指代表达与候选实体类型相容")
        if chain_size >= 3:
            score += 0.04
            factors.append("同一实体链包含至少三个提及")
        if not self._is_reference(str(canonical.get("text") or "")):
            score += 0.03
            factors.append("实体链具有明确的非指代代表提及")
        if str(mention.get("text") or "") in {"其中", "该"}:
            score -= 0.12
            factors.append("指代表达本身语义较弱或可能指向非实体")
        if not entity:
            score -= 0.04
            factors.append("代表提及未与结构化实体绑定")
        return round(max(0.0, min(score, 0.95)), 4), factors

    def _from_local_fallback(
        self,
        text_name: str,
        text: str,
        entity_catalog: List[Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        entity_mentions = self._find_entity_mentions(text, entity_catalog)
        sentence_spans = self._sentence_spans(text)
        chain_mentions: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        resolutions: List[Dict[str, Any]] = []

        for found in self.REFERENCE_PATTERN.finditer(text):
            surface = found.group()
            sentence_index = self._sentence_index(found.start(), sentence_spans)
            mention = {
                "text": surface,
                "mention_type": "FALLBACK_REFERENCE",
                "sentence_index": sentence_index,
                "start_char": found.start(),
                "end_char": found.end(),
                "is_representative": False,
            }
            candidates = [
                item
                for item in entity_mentions
                if item["end_char"] <= found.start()
                and self._reference_type_compatible(
                    surface,
                    str(item["entity"].get("entity_type") or ""),
                )
            ]
            candidates.sort(
                key=lambda item: (
                    found.start() - item["end_char"],
                    -len(item["surface"]),
                )
            )
            best = candidates[0] if candidates else None
            entity = best["entity"] if best else None
            chain_id = (
                f"COREF_{text_name}_{entity['entity_id']}"
                if entity
                else f"COREF_{text_name}_UNRESOLVED_{found.start()}"
            )
            canonical_text = (
                str(best["surface"])
                if best
                else ""
            )
            canonical = {
                "text": canonical_text,
                "mention_type": "ENTITY_ALIAS" if entity else "",
                "sentence_index": best["sentence_index"] if best else sentence_index,
                "start_char": best["start_char"] if best else -1,
                "end_char": best["end_char"] if best else -1,
                "is_representative": True,
            }
            distance = (
                sentence_index - int(best["sentence_index"])
                if best
                else 99
            )
            score = 0.50
            factors = ["CoreNLP不可用，使用最近前置实体保守生成候选"]
            if entity:
                score += 0.14
                factors.append("候选与结构化实体唯一绑定")
            if distance <= 1:
                score += 0.07
                factors.append("候选实体位于当前句或前一句")
            if entity and self._reference_type_compatible(
                surface,
                str(entity.get("entity_type") or ""),
            ):
                score += 0.04
                factors.append("指代表达与候选实体类型相容")
            if len(candidates) == 1:
                score += 0.03
                factors.append("当前窗口只有一个相容候选实体")
            if surface in {"其中", "该"}:
                score -= 0.16
                factors.append("指代表达过于宽泛")
            score = round(max(0.0, min(score, 0.89)), 4)

            chain_mentions[chain_id].append(mention)
            chain = self._chain_record(
                chain_id=chain_id,
                text_name=text_name,
                canonical=canonical,
                entity=entity,
                mentions=[canonical, *chain_mentions[chain_id]],
                source="local_fallback",
            )
            resolutions.append(
                self._resolution_record(
                    text_name=text_name,
                    text=text,
                    mention=mention,
                    chain=chain,
                    confidence=score,
                    confidence_factors=factors,
                    source="local_fallback",
                )
            )

        chains_by_id: Dict[str, Dict[str, Any]] = {}
        for resolution in resolutions:
            chain_id = resolution["candidate_chain_id"]
            entity = self._entity_by_id(
                resolution.get("candidate_entity_id"),
                entity_catalog,
            )
            mentions = chain_mentions[chain_id]
            first = resolution
            canonical = {
                "text": first.get("candidate_name") or "",
                "mention_type": "ENTITY_ALIAS" if entity else "",
                "sentence_index": max(
                    0,
                    int(first.get("sentence_index") or 0) - 1,
                ),
                "start_char": -1,
                "end_char": -1,
                "is_representative": True,
            }
            chains_by_id[chain_id] = self._chain_record(
                chain_id=chain_id,
                text_name=text_name,
                canonical=canonical,
                entity=entity,
                mentions=[canonical, *mentions],
                source="local_fallback",
            )
        return list(chains_by_id.values()), self._deduplicate_resolutions(resolutions)

    def _review_medium_confidence(
        self,
        text_name: str,
        text: str,
        resolutions: List[Dict[str, Any]],
        chains: List[Dict[str, Any]],
        entity_catalog: List[Dict[str, Any]],
    ) -> Dict[str, Any] | None:
        medium_confidence = [
            item
            for item in resolutions
            if self.review_threshold
            <= float(item.get("local_confidence") or 0.0)
            <= self.auto_threshold
        ]
        if not medium_confidence:
            return None

        prompt = self._build_review_prompt(
            text_name,
            text,
            medium_confidence,
            chains,
            entity_catalog,
        )
        result = self.llm_client.review_coreferences(prompt)
        payload = result.get("payload") or {}
        reviews = payload.get("reviews") if isinstance(payload, dict) else None
        reviews_by_id = {
            str(item.get("resolution_id")): item
            for item in (reviews or [])
            if isinstance(item, dict) and item.get("resolution_id")
        }
        for resolution in medium_confidence:
            review = reviews_by_id.get(str(resolution["resolution_id"]))
            if review is None:
                resolution["llm_review"] = {
                    "required": True,
                    "performed": bool(result.get("available")),
                    "available": bool(result.get("available")),
                    "decision": "abstain",
                    "confidence": 0.0,
                    "reason": result.get("reason") or "DeepSeek未返回该候选的复核结果",
                }
                continue
            resolution["llm_review"] = self._normalize_review(review)

        return {
            "text_name": text_name,
            "model": getattr(self.llm_client, "chat_model", "deepseek-v4-flash"),
            "confidence_interval": {
                "minimum_inclusive": self.review_threshold,
                "maximum_inclusive": self.auto_threshold,
            },
            "candidate_count": len(medium_confidence),
            "candidate_ids": [
                item["resolution_id"]
                for item in medium_confidence
            ],
            "available": bool(result.get("available")),
            "reason": result.get("reason") or "",
            "response": payload if isinstance(payload, dict) else None,
        }

    def _build_review_prompt(
        self,
        text_name: str,
        text: str,
        resolutions: List[Dict[str, Any]],
        chains: List[Dict[str, Any]],
        entity_catalog: List[Dict[str, Any]],
    ) -> str:
        chain_briefs = [
            {
                "chain_id": item.get("chain_id"),
                "canonical_mention": item.get("canonical_mention"),
                "entity_id": item.get("canonical_entity_id"),
                "entity_name": item.get("canonical_entity_name"),
                "entity_type": item.get("canonical_entity_type"),
            }
            for item in chains
        ]
        candidate_briefs = [
            {
                "resolution_id": item["resolution_id"],
                "mention": item["mention"],
                "context": item["context"],
                "candidate_chain_id": item["candidate_chain_id"],
                "candidate_entity_id": item.get("candidate_entity_id") or "",
                "candidate_name": item.get("candidate_name") or "",
                "candidate_type": item.get("candidate_type") or "",
                "local_confidence": item["local_confidence"],
                "confidence_factors": item["confidence_factors"],
            }
            for item in resolutions
        ]
        entity_briefs = [
            {
                "entity_id": item["entity_id"],
                "entity_name": item["entity_name"],
                "entity_type": item["entity_type"],
                "aliases": item["aliases"],
            }
            for item in entity_catalog
        ]
        return f"""你是银行反洗钱中文指代消解复核员。CoreNLP或本地兜底已经给出候选实体链，
但以下候选的本地置信度位于{self.review_threshold:.0%}至
{self.auto_threshold:.0%}（含边界），需要你逐条独立复核。

规则：
1. 只能根据原文上下文和给定实体/实体链判断，禁止补造主体。
2. decision=confirm 表示确认当前候选；decision=replace 表示改指向给定的
   target_chain_id 或 target_entity_id；无法唯一确认必须输出 abstain。
3. confidence 表示你对最终判断的置信度，取0到1。只有 confirm/replace 且
   confidence>={self.auto_threshold:.2f} 才会自动写回文本，其他结果进入人工复核。
4. “其中”“该”等表达可能指向事实集合、资金或句子成分，不应强行绑定客户。
5. 输出必须为一个JSON对象，不要输出Markdown。

文本名称：{text_name}
原文：
{text}

结构化实体：
{json.dumps(entity_briefs, ensure_ascii=False, indent=2)}

候选实体链：
{json.dumps(chain_briefs, ensure_ascii=False, indent=2)}

待复核候选：
{json.dumps(candidate_briefs, ensure_ascii=False, indent=2)}

输出格式：
{{
  "reviews": [
    {{
      "resolution_id": "RES_...",
      "decision": "confirm|replace|abstain",
      "target_chain_id": "",
      "target_entity_id": "",
      "confidence": 0.0,
      "reason": "一句话说明直接文本依据"
    }}
  ]
}}
"""

    def _finalize_resolutions(
        self,
        resolutions: List[Dict[str, Any]],
        chains: List[Dict[str, Any]],
        entity_catalog: List[Dict[str, Any]],
    ) -> None:
        chains_by_id = {str(item["chain_id"]): item for item in chains}
        for item in resolutions:
            local_confidence = float(item.get("local_confidence") or 0.0)
            item["final_chain_id"] = item.get("candidate_chain_id") or ""
            item["final_entity_id"] = item.get("candidate_entity_id") or ""
            item["final_name"] = item.get("candidate_name") or ""
            item["final_type"] = item.get("candidate_type") or ""

            if local_confidence < self.review_threshold:
                standalone_chain = self._create_standalone_chain(item)
                standalone_chain_id = str(standalone_chain["chain_id"])
                if standalone_chain_id not in chains_by_id:
                    chains.append(standalone_chain)
                    chains_by_id[standalone_chain_id] = standalone_chain
                item["status"] = "standalone_entity"
                item["final_chain_id"] = standalone_chain_id
                item["final_entity_id"] = (
                    standalone_chain.get("canonical_entity_id") or ""
                )
                item["final_name"] = item.get("mention") or ""
                item["final_type"] = "独立实体"
                item["final_confidence"] = local_confidence
                item["resolution_method"] = "low_confidence_standalone_entity"
                item["review_reason"] = (
                    f"候选置信度低于{self.review_threshold:.0%}，"
                    "不调用DeepSeek且不绑定已有实体"
                )
                continue

            if local_confidence > self.auto_threshold and item.get("final_name"):
                item["status"] = "auto_pass"
                item["final_confidence"] = local_confidence
                item["resolution_method"] = (
                    f"corenlp_{self.corenlp_version}_chinese_neural"
                    if item.get("candidate_source") == "corenlp"
                    else "local_reference_fallback"
                )
                item["review_reason"] = "候选置信度超过自动通过阈值"
                continue

            review = item.get("llm_review") or {}
            decision = str(review.get("decision") or "abstain")
            confidence = float(review.get("confidence") or 0.0)
            if decision == "replace":
                target_chain = chains_by_id.get(str(review.get("target_chain_id") or ""))
                target_entity = self._entity_by_id(
                    review.get("target_entity_id"),
                    entity_catalog,
                )
                if target_chain:
                    item["final_chain_id"] = target_chain.get("chain_id") or ""
                    item["final_entity_id"] = target_chain.get("canonical_entity_id") or ""
                    item["final_name"] = (
                        target_chain.get("canonical_entity_name")
                        or target_chain.get("canonical_mention")
                        or ""
                    )
                    item["final_type"] = target_chain.get("canonical_entity_type") or ""
                elif target_entity:
                    item["final_chain_id"] = (
                        f"COREF_{item['text_name']}_{target_entity['entity_id']}"
                    )
                    item["final_entity_id"] = target_entity["entity_id"]
                    item["final_name"] = target_entity["entity_name"]
                    item["final_type"] = target_entity["entity_type"]
                else:
                    decision = "abstain"

            approved = (
                decision in {"confirm", "replace"}
                and confidence >= self.auto_threshold
                and bool(item.get("final_name"))
            )
            if approved:
                item["status"] = "llm_pass"
                item["final_confidence"] = confidence
                item["resolution_method"] = "deepseek_v4_flash_review"
                item["review_reason"] = review.get("reason") or "DeepSeek复核通过"
            else:
                item["status"] = "manual_review"
                item["final_confidence"] = (
                    confidence
                    if decision in {"confirm", "replace"}
                    else 0.0
                )
                item["resolution_method"] = "manual_review_required"
                item["review_reason"] = (
                    review.get("reason")
                    or "低于80%阈值或无法唯一确认，需要人工复核"
                )

        for item in resolutions:
            if item.get("status") not in {"auto_pass", "llm_pass"}:
                continue
            final_chain_id = str(item.get("final_chain_id") or "")
            if not final_chain_id:
                continue
            final_chain = chains_by_id.get(final_chain_id)
            if final_chain is None:
                entity = self._entity_by_id(
                    item.get("final_entity_id"),
                    entity_catalog,
                )
                canonical = {
                    "text": item.get("final_name") or "",
                    "mention_type": "ENTITY_ALIAS" if entity else "",
                    "sentence_index": item.get("sentence_index") or 0,
                    "start_char": -1,
                    "end_char": -1,
                    "is_representative": True,
                }
                final_chain = self._chain_record(
                    chain_id=final_chain_id,
                    text_name=str(item.get("text_name") or ""),
                    canonical=canonical,
                    entity=entity,
                    mentions=[canonical],
                    source="deepseek_replacement",
                )
                chains.append(final_chain)
                chains_by_id[final_chain_id] = final_chain
            self._append_resolution_to_chain(final_chain, item)

        resolved_chain_statuses: Dict[str, List[str]] = defaultdict(list)
        manual_chain_statuses: Dict[str, List[str]] = defaultdict(list)
        standalone_chain_statuses: Dict[str, List[str]] = defaultdict(list)
        for item in resolutions:
            status = str(item.get("status") or "")
            if status in {"auto_pass", "llm_pass"}:
                resolved_chain_statuses[str(item.get("final_chain_id") or "")].append(
                    status
                )
            elif status == "manual_review":
                manual_chain_statuses[
                    str(item.get("candidate_chain_id") or "")
                ].append(status)
            elif status == "standalone_entity":
                standalone_chain_statuses[
                    str(item.get("final_chain_id") or "")
                ].append(status)
        for chain in chains:
            chain_id = str(chain.get("chain_id") or "")
            chain["resolved_reference_count"] = len(
                resolved_chain_statuses.get(chain_id, [])
            )
            chain["manual_review_count"] = len(
                manual_chain_statuses.get(chain_id, [])
            )
            chain["standalone_entity_count"] = len(
                standalone_chain_statuses.get(chain_id, [])
            )
            chain["needs_human_review"] = bool(
                manual_chain_statuses.get(chain_id)
            )

        self._synchronize_chain_reference_confidences(resolutions, chains)

    def _append_resolution_to_chain(
        self,
        chain: Dict[str, Any],
        resolution: Dict[str, Any],
    ) -> None:
        mention = self._resolved_mention_record(resolution)
        mentions = chain.setdefault("mentions", [])
        key = self._mention_key(mention)
        existing_index = next(
            (
                index
                for index, item in enumerate(mentions)
                if self._mention_key(item) == key
            ),
            None,
        )
        if existing_index is None:
            mentions.append(mention)
        else:
            mentions[existing_index] = {
                **mentions[existing_index],
                **mention,
            }

    def _resolved_mention_record(
        self,
        resolution: Dict[str, Any],
    ) -> Dict[str, Any]:
        local_confidence = float(resolution.get("local_confidence") or 0.0)
        is_standalone = resolution.get("status") == "standalone_entity"
        return {
            "text": resolution.get("mention") or "",
            "mention_type": (
                "STANDALONE_REFERENCE_ENTITY"
                if is_standalone
                else "RESOLVED_REFERENCE"
            ),
            "sentence_index": resolution.get("sentence_index") or 0,
            "start_char": resolution.get("start_char") or 0,
            "end_char": resolution.get("end_char") or 0,
            "is_representative": is_standalone,
            "is_reference": True,
            "resolution_id": resolution.get("resolution_id") or "",
            "confidence": local_confidence,
            "local_confidence": local_confidence,
            "local_confidence_band": resolution.get("local_confidence_band") or "",
            "status": resolution.get("status") or "",
            "llm_review_required": bool(
                resolution.get("llm_review_required")
            ),
            "llm_review": resolution.get("llm_review"),
            "final_confidence": resolution.get("final_confidence") or 0.0,
            "final_chain_id": resolution.get("final_chain_id") or "",
            "final_entity_id": resolution.get("final_entity_id") or "",
            "resolution_method": resolution.get("resolution_method") or "",
        }

    def _mention_key(self, mention: Dict[str, Any]) -> Tuple[int, int, str]:
        return (
            int(mention.get("start_char") or 0),
            int(mention.get("end_char") or 0),
            str(mention.get("text") or ""),
        )

    def _create_standalone_chain(
        self,
        resolution: Dict[str, Any],
    ) -> Dict[str, Any]:
        start = int(resolution.get("start_char") or 0)
        end = int(resolution.get("end_char") or 0)
        text_name = str(resolution.get("text_name") or "")
        mention_text = str(resolution.get("mention") or "")
        chain_id = f"COREF_{text_name}_STANDALONE_{start:06d}_{end:06d}"
        canonical = {
            "text": mention_text,
            "mention_type": "STANDALONE_REFERENCE_ENTITY",
            "sentence_index": int(resolution.get("sentence_index") or 0),
            "start_char": start,
            "end_char": end,
            "is_representative": True,
            "is_reference": True,
        }
        chain = self._chain_record(
            chain_id=chain_id,
            text_name=text_name,
            canonical=canonical,
            entity=None,
            mentions=[canonical],
            source="low_confidence_standalone",
        )
        chain["canonical_entity_id"] = (
            f"COREFENT_{text_name}_{start:06d}_{end:06d}"
        )
        chain["canonical_entity_name"] = mention_text
        chain["canonical_entity_type"] = "独立实体"
        chain["is_standalone_entity"] = True
        return chain

    def _synchronize_chain_reference_confidences(
        self,
        resolutions: List[Dict[str, Any]],
        chains: List[Dict[str, Any]],
    ) -> None:
        chains_by_id = {str(item.get("chain_id") or ""): item for item in chains}
        for resolution in resolutions:
            candidate_chain_id = str(
                resolution.get("candidate_chain_id") or ""
            )
            final_chain_id = str(resolution.get("final_chain_id") or "")
            status = str(resolution.get("status") or "")
            target_chain_id = (
                final_chain_id
                if status in {"auto_pass", "llm_pass", "standalone_entity"}
                else candidate_chain_id
            )

            if target_chain_id != candidate_chain_id:
                candidate_chain = chains_by_id.get(candidate_chain_id)
                if candidate_chain is not None:
                    resolution_key = self._mention_key(
                        {
                            "start_char": resolution.get("start_char"),
                            "end_char": resolution.get("end_char"),
                            "text": resolution.get("mention"),
                        }
                    )
                    candidate_chain["mentions"] = [
                        mention
                        for mention in candidate_chain.get("mentions") or []
                        if self._mention_key(mention) != resolution_key
                    ]

            target_chain = chains_by_id.get(target_chain_id)
            if target_chain is not None:
                self._append_resolution_to_chain(target_chain, resolution)

        for chain in chains:
            normalized_mentions = []
            for mention in chain.get("mentions") or []:
                normalized = dict(mention)
                normalized["is_reference"] = bool(
                    normalized.get("is_reference")
                    or self._is_reference(str(normalized.get("text") or ""))
                )
                if normalized["is_reference"]:
                    normalized.setdefault("confidence", None)
                    normalized.setdefault("local_confidence", None)
                    normalized.setdefault("local_confidence_band", "")
                    normalized.setdefault("status", "unscored")
                    normalized.setdefault("final_confidence", 0.0)
                normalized_mentions.append(normalized)
            chain["mentions"] = normalized_mentions

    def _apply_resolutions(
        self,
        text: str,
        resolutions: List[Dict[str, Any]],
    ) -> str:
        replacements: Dict[Tuple[int, int], Tuple[str, float]] = {}
        for item in resolutions:
            if item.get("status") not in {"auto_pass", "llm_pass"}:
                continue
            start = int(item.get("start_char") or 0)
            end = int(item.get("end_char") or 0)
            entity_id = str(item.get("final_entity_id") or "")
            replacement = (
                f"[{entity_id}]"
                if entity_id
                else str(item.get("final_name") or "")
            )
            if (
                str(item.get("mention") or "") == "其账户"
                and str(item.get("final_type") or "") != "账户"
            ):
                replacement = f"{replacement}账户"
            if not replacement or end <= start:
                continue
            key = (start, end)
            confidence = float(item.get("final_confidence") or 0.0)
            if key not in replacements or confidence > replacements[key][1]:
                replacements[key] = (replacement, confidence)

        result = text
        for (start, end), (replacement, _) in sorted(
            replacements.items(),
            key=lambda item: item[0][0],
            reverse=True,
        ):
            result = f"{result[:start]}{replacement}{result[end:]}"
        return result

    def _build_entity_catalog(self, state: Dict[str, Any]) -> List[Dict[str, Any]]:
        entities: List[Dict[str, Any]] = []
        for customer in state.get("customers") or []:
            self._append_entity(
                entities,
                customer,
                "客户",
                "customer_name",
                ("customer_name", "customer_number", "id_number"),
            )
        for account in state.get("accounts") or []:
            self._append_entity(
                entities,
                account,
                "账户",
                "account_number",
                (
                    "account_number",
                    "bank_card_number",
                ),
            )
        for other in state.get("other_entities") or []:
            self._append_entity(
                entities,
                other,
                "其他实体",
                "entity_attr_1",
                ("entity_attr_1", "entity_attr_2", "entity_attr_3", "entity_attr_4"),
            )
        return entities

    def _append_entity(
        self,
        output: List[Dict[str, Any]],
        raw: Dict[str, Any],
        entity_type: str,
        name_field: str,
        alias_fields: Iterable[str],
    ) -> None:
        entity_id = str(raw.get("entity_id") or "").strip()
        if not entity_id:
            return
        aliases = {
            str(raw.get(field) or "").strip()
            for field in alias_fields
            if str(raw.get(field) or "").strip()
        }
        aliases.add(entity_id)
        entity_name = str(raw.get(name_field) or "").strip()
        if not entity_name and entity_type == "账户":
            entity_name = str(raw.get("holder_name") or entity_id)
        if entity_name:
            aliases.add(entity_name)
        output.append(
            {
                "entity_id": entity_id,
                "entity_name": entity_name or entity_id,
                "entity_type": entity_type,
                "aliases": sorted(aliases, key=len, reverse=True),
            }
        )

    def _find_entity_mentions(
        self,
        text: str,
        entity_catalog: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        spans = self._sentence_spans(text)
        mentions: Dict[Tuple[int, int, str], Dict[str, Any]] = {}
        for entity in entity_catalog:
            for alias in entity["aliases"]:
                if len(alias) < 2:
                    continue
                for found in re.finditer(re.escape(alias), text):
                    key = (found.start(), found.end(), entity["entity_id"])
                    mentions[key] = {
                        "surface": found.group(),
                        "start_char": found.start(),
                        "end_char": found.end(),
                        "sentence_index": self._sentence_index(found.start(), spans),
                        "entity": entity,
                    }
        return sorted(
            mentions.values(),
            key=lambda item: (item["start_char"], -len(item["surface"])),
        )

    def _match_entity(
        self,
        mention: str,
        entity_catalog: List[Dict[str, Any]],
    ) -> Dict[str, Any] | None:
        normalized = self._normalize_surface(mention)
        exact_matches = []
        contained_matches = []
        for entity in entity_catalog:
            aliases = {
                self._normalize_surface(alias)
                for alias in entity.get("aliases") or []
            }
            if normalized and normalized in aliases:
                exact_matches.append(entity)
                continue
            if normalized and any(
                len(alias) >= 2 and alias in normalized
                for alias in aliases
            ):
                contained_matches.append(entity)
        if len(exact_matches) == 1:
            return exact_matches[0]
        if not exact_matches and len(contained_matches) == 1:
            return contained_matches[0]
        return None

    def _entity_by_id(
        self,
        entity_id: Any,
        entity_catalog: List[Dict[str, Any]],
    ) -> Dict[str, Any] | None:
        target = str(entity_id or "")
        return next(
            (
                item
                for item in entity_catalog
                if str(item.get("entity_id") or "") == target
            ),
            None,
        )

    def _chain_record(
        self,
        chain_id: str,
        text_name: str,
        canonical: Dict[str, Any],
        entity: Dict[str, Any] | None,
        mentions: List[Dict[str, Any]],
        source: str,
    ) -> Dict[str, Any]:
        unique_mentions = {
            (
                int(item.get("start_char") or -1),
                int(item.get("end_char") or -1),
                str(item.get("text") or ""),
            ): item
            for item in mentions
        }
        return {
            "chain_id": chain_id,
            "text_name": text_name,
            "canonical_mention": canonical.get("text") or "",
            "canonical_entity_id": (entity or {}).get("entity_id") or "",
            "canonical_entity_name": (
                (entity or {}).get("entity_name")
                or canonical.get("text")
                or ""
            ),
            "canonical_entity_type": (entity or {}).get("entity_type") or "",
            "candidate_source": source,
            "mentions": list(unique_mentions.values()),
            "is_standalone_entity": False,
            "resolved_reference_count": 0,
            "manual_review_count": 0,
            "standalone_entity_count": 0,
            "needs_human_review": False,
        }

    def _resolution_record(
        self,
        text_name: str,
        text: str,
        mention: Dict[str, Any],
        chain: Dict[str, Any],
        confidence: float,
        confidence_factors: List[str],
        source: str,
    ) -> Dict[str, Any]:
        start = int(mention.get("start_char") or 0)
        end = int(mention.get("end_char") or 0)
        resolution_id = f"RES_{text_name}_{start:06d}_{end:06d}"
        confidence_band = self._confidence_band(confidence)
        llm_review_required = confidence_band == "medium"
        return {
            "resolution_id": resolution_id,
            "text_name": text_name,
            "sentence_index": int(mention.get("sentence_index") or 0),
            "mention": mention.get("text") or text[start:end],
            "start_char": start,
            "end_char": end,
            "context": text[max(0, start - 100) : min(len(text), end + 100)],
            "candidate_chain_id": chain.get("chain_id") or "",
            "candidate_entity_id": chain.get("canonical_entity_id") or "",
            "candidate_name": (
                chain.get("canonical_entity_name")
                or chain.get("canonical_mention")
                or ""
            ),
            "candidate_type": chain.get("canonical_entity_type") or "",
            "candidate_source": source,
            "local_confidence": confidence,
            "local_confidence_band": confidence_band,
            "confidence_factors": confidence_factors,
            "llm_review_required": llm_review_required,
            "llm_review": {
                "required": llm_review_required,
                "performed": False,
                "available": False,
                "decision": "pending" if llm_review_required else "not_required",
                "confidence": 0.0,
                "reason": (
                    "置信度位于DeepSeek复核区间"
                    if llm_review_required
                    else (
                        "置信度超过自动通过阈值，无需复核"
                        if confidence_band == "high"
                        else "置信度低于复核阈值，按独立实体处理"
                    )
                ),
            },
            "status": "pending",
            "final_chain_id": "",
            "final_entity_id": "",
            "final_name": "",
            "final_type": "",
            "final_confidence": 0.0,
            "resolution_method": "",
            "review_reason": "",
        }

    def _normalize_review(self, review: Dict[str, Any]) -> Dict[str, Any]:
        decision = str(review.get("decision") or "abstain").lower()
        if decision not in {"confirm", "replace", "abstain"}:
            decision = "abstain"
        try:
            confidence = float(review.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        if confidence > 1.0:
            confidence /= 100.0
        return {
            "required": True,
            "performed": True,
            "available": True,
            "decision": decision,
            "target_chain_id": str(review.get("target_chain_id") or ""),
            "target_entity_id": str(review.get("target_entity_id") or ""),
            "confidence": round(max(0.0, min(confidence, 1.0)), 4),
            "reason": str(review.get("reason") or ""),
        }

    def _manual_review_item(self, item: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "resolution_id": item.get("resolution_id"),
            "text_name": item.get("text_name"),
            "sentence_index": item.get("sentence_index"),
            "mention": item.get("mention"),
            "start_char": item.get("start_char"),
            "end_char": item.get("end_char"),
            "context": item.get("context"),
            "candidate_chain_id": item.get("candidate_chain_id"),
            "candidate_entity_id": item.get("candidate_entity_id"),
            "candidate_name": item.get("candidate_name"),
            "candidate_type": item.get("candidate_type"),
            "local_confidence": item.get("local_confidence"),
            "local_confidence_band": item.get("local_confidence_band"),
            "llm_review_required": item.get("llm_review_required"),
            "llm_review": item.get("llm_review"),
            "final_confidence": item.get("final_confidence"),
            "review_reason": item.get("review_reason"),
            "required_action": "人工确认正确指向，或标记为非实体指代/无法消解",
        }

    def _standalone_entity_item(self, item: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "entity_id": item.get("final_entity_id"),
            "entity_chain_id": item.get("final_chain_id"),
            "text_name": item.get("text_name"),
            "sentence_index": item.get("sentence_index"),
            "entity_mention": item.get("mention"),
            "start_char": item.get("start_char"),
            "end_char": item.get("end_char"),
            "context": item.get("context"),
            "confidence": item.get("local_confidence"),
            "confidence_band": item.get("local_confidence_band"),
            "entity_type": "独立实体",
            "source_candidate_chain_id": item.get("candidate_chain_id"),
            "source_candidate_name": item.get("candidate_name"),
            "processing_policy": (
                f"置信度低于{self.review_threshold:.0%}，不调用DeepSeek，"
                "不与候选实体链合并"
            ),
        }

    def _resolved_document_units(
        self,
        state: Dict[str, Any],
        text_name: str,
        resolved_text: str,
    ) -> List[Dict[str, Any]]:
        source_sections = (state.get("structured_sections") or {}).get(text_name) or []
        if source_sections:
            return [
                {
                    **source_sections[0],
                    "text": resolved_text,
                    "coreference_resolved": True,
                }
            ]
        return [
            {
                "section_id": "document_001",
                "section_title": "全文",
                "section_name": "全文",
                "text": resolved_text,
                "coreference_resolved": True,
            }
        ]

    def _reference_type_compatible(self, reference: str, entity_type: str) -> bool:
        if reference in self.CUSTOMER_REFERENCES:
            return entity_type == "客户"
        if reference in self.ACCOUNT_REFERENCES:
            return entity_type == "账户"
        if reference == "其账户":
            return entity_type in {"客户", "账户"}
        if "线索" in reference:
            return entity_type == "其他实体"
        return True

    def _is_reference(self, text: str) -> bool:
        normalized = self._normalize_surface(text)
        return bool(
            normalized
            and (
                self.REFERENCE_PATTERN.fullmatch(normalized)
                or normalized.startswith("其")
                or normalized.startswith("该")
                or normalized.startswith("上述")
            )
        )

    def _deduplicate_resolutions(
        self,
        resolutions: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        unique: Dict[Tuple[int, int, str], Dict[str, Any]] = {}
        for item in resolutions:
            key = (
                int(item.get("start_char") or 0),
                int(item.get("end_char") or 0),
                str(item.get("candidate_chain_id") or ""),
            )
            existing = unique.get(key)
            if existing is None or float(item.get("local_confidence") or 0.0) > float(
                existing.get("local_confidence") or 0.0
            ):
                unique[key] = item
        return sorted(unique.values(), key=lambda item: int(item["start_char"]))

    def _sentence_spans(self, text: str) -> List[Tuple[int, int]]:
        spans = [
            (match.start(), match.end())
            for match in re.finditer(r"[^。！？；;\n]+[。！？；;]?", text)
            if match.group().strip()
        ]
        return spans or [(0, len(text))]

    def _sentence_index(
        self,
        position: int,
        spans: List[Tuple[int, int]],
    ) -> int:
        for index, (start, end) in enumerate(spans):
            if start <= position < end:
                return index
        return max(0, len(spans) - 1)

    def _normalize_surface(self, text: str) -> str:
        return re.sub(r"[\s\[\]（）()，,。；;：:]+", "", str(text or ""))

    def _validate_threshold(self, value: float, field_name: str) -> float:
        numeric = float(value)
        if not 0.0 <= numeric <= 1.0:
            raise ValueError(f"{field_name} must be between 0 and 1")
        return numeric

    def _confidence_band(self, confidence: float) -> str:
        if confidence > self.auto_threshold:
            return "high"
        if confidence >= self.review_threshold:
            return "medium"
        return "low"
