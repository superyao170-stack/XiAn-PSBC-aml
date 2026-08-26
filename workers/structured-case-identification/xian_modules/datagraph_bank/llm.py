"""Optional DeepSeek OpenAI-compatible chat and embedding client."""

from __future__ import annotations

import json
import os
import re
from typing import Any, Dict, List, Optional

from datagraph_bank.concurrency import LocalServiceGate

try:
    import httpx
except ImportError:  # pragma: no cover - optional dependency
    httpx = None  # type: ignore[assignment]


# The reusable source module keeps safe defaults, while the deployed worker
# injects credentials through its process environment (loaded from the
# worker-local .env by framework_extraction.py before this module is imported).
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_CHAT_MODEL = os.getenv("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash")
DEEPSEEK_EMBEDDING_MODEL = os.getenv("DEEPSEEK_EMBEDDING_MODEL", "")
DEEPSEEK_TEMPERATURE = float(os.getenv("DEEPSEEK_TEMPERATURE", "0"))
DEEPSEEK_TOP_P = float(os.getenv("DEEPSEEK_TOP_P", "1"))
DEEPSEEK_TIMEOUT_SECONDS = float(os.getenv("DEEPSEEK_TIMEOUT_SECONDS", "90"))
DEEPSEEK_MAX_TOKENS = int(os.getenv("DEEPSEEK_MAX_TOKENS", "4096"))
_configured_seed = os.getenv("DEEPSEEK_SEED", "").strip()
DEEPSEEK_SEED = int(_configured_seed) if _configured_seed else None


DEFAULT_BASE_URL = DEEPSEEK_BASE_URL
DEFAULT_CHAT_MODEL = DEEPSEEK_CHAT_MODEL


class DeepSeekLLMClient:
    """Thin wrapper around DeepSeek's OpenAI-compatible HTTP API.

    The client is intentionally optional: if HTTP support, API key, or embedding model
    is missing, callers can keep running deterministic local logic.
    """

    def __init__(
        self,
        enabled: bool = True,
        chat_model: Optional[str] = None,
        embedding_model: Optional[str] = None,
        service_gate: Optional[LocalServiceGate] = None,
    ) -> None:
        self.enabled = enabled
        self.provider = "deepseek"
        self.api_key = DEEPSEEK_API_KEY.strip()
        self.base_url = DEEPSEEK_BASE_URL.rstrip("/")
        self.chat_model = chat_model or DEEPSEEK_CHAT_MODEL or DEFAULT_CHAT_MODEL
        self.embedding_model = embedding_model or DEEPSEEK_EMBEDDING_MODEL or None
        self.temperature = float(DEEPSEEK_TEMPERATURE)
        self.top_p = float(DEEPSEEK_TOP_P)
        self.seed = DEEPSEEK_SEED
        self.service_gate = service_gate
        self.client: Optional[Any] = None
        self.init_error: Optional[str] = None

        if not enabled:
            self.init_error = "LLM disabled by CLI option"
            return
        if httpx is None:
            self.init_error = "httpx is not installed"
            return
        if not self.api_key or self.api_key == "your_deepseek_api_key_here":
            self.init_error = (
                "DEEPSEEK_API_KEY is not configured in the worker environment"
            )
            return

        try:
            self.client = httpx.Client(
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
                timeout=httpx.Timeout(DEEPSEEK_TIMEOUT_SECONDS, connect=10.0),
            )
        except Exception as exc:  # pragma: no cover - depends on runtime internals
            self.init_error = f"DeepSeek client init failed: {exc}"

    @property
    def chat_available(self) -> bool:
        return bool(self.enabled and self.client and self.chat_model)

    @property
    def embedding_available(self) -> bool:
        return bool(self.enabled and self.client and self.embedding_model)

    def status(self) -> Dict[str, Any]:
        return {
            "provider": self.provider,
            "enabled": self.enabled,
            "chat_available": self.chat_available,
            "embedding_available": self.embedding_available,
            "base_url": self.base_url,
            "chat_model": self.chat_model if self.chat_available else None,
            "embedding_model": self.embedding_model if self.embedding_available else None,
            "temperature": self.temperature,
            "top_p": self.top_p,
            "seed": self.seed,
            "transport": "direct_http",
            "service_gate": (
                self.service_gate.snapshot() if self.service_gate is not None else None
            ),
            "init_error": self.init_error,
        }

    def chat_text(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
    ) -> Optional[str]:
        if not self.chat_available or self.client is None:
            return None

        def request() -> str:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                json={
                    "model": self.chat_model,
                    "messages": [
                        *(
                            [{"role": "system", "content": system_prompt}]
                            if system_prompt
                            else []
                        ),
                        {"role": "user", "content": prompt},
                    ],
                    "stream": False,
                    "temperature": self.temperature,
                    "top_p": self.top_p,
                    "response_format": {"type": "json_object"},
                    "max_tokens": DEEPSEEK_MAX_TOKENS,
                    "thinking": {"type": "disabled"},
                    **({"seed": self.seed} if self.seed is not None else {}),
                },
            )
            response.raise_for_status()
            response_payload = response.json()
            content = self._extract_text(response_payload)
            if not content.strip() or content == "None":
                choice = (response_payload.get("choices") or [{}])[0]
                message = choice.get("message") or {}
                raise RuntimeError(
                    "模型未返回最终内容"
                    f"（finish_reason={choice.get('finish_reason') or 'unknown'}, "
                    f"reasoning_content={'present' if message.get('reasoning_content') else 'absent'}）"
                )
            return content

        if self.service_gate is not None:
            return self.service_gate.run(request)
        return request()

    def chat_json(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Run a prompt that must return one JSON object."""

        try:
            content = self.chat_text(prompt, system_prompt=system_prompt)
            if not content:
                return {"available": False, "payload": None, "reason": "LLM unavailable"}
            payload = self._parse_json_object(content)
            if payload is None:
                return {
                    "available": True,
                    "payload": None,
                    "reason": "LLM response is not valid JSON",
                    "raw": content,
                }
            return {
                "available": True,
                "payload": payload,
                "reason": "",
                "raw": content,
            }
        except Exception as exc:
            return {"available": False, "payload": None, "reason": str(exc)}

    def extract_events(self, prompt: str) -> Dict[str, Any]:
        """Extract document-level business events with the configured prompt."""

        result = self.chat_json(prompt)
        payload = result.get("payload")
        if payload is not None and not isinstance(payload.get("events"), list):
            return {
                **result,
                "payload": None,
                "reason": "LLM JSON does not contain an events array",
            }
        return result

    def validate_event_kb_matches(
        self,
        events: List[Dict[str, Any]],
        kb_candidates: List[Dict[str, Any]],
        source_text: str,
    ) -> Dict[str, Any]:
        """Independently adjudicate extracted events against retrieved KB rules."""

        prompt = f"""你是反洗钱风险事件知识库规则裁决器。生成模型已经给出候选事件；你只负责逐条复核，不能新增事件。

裁决顺序：
1. 先判断候选是否由原文明确事实支持，并形成完整、可解释可疑性的动态业务模式。只有汇总金额、笔数、夜间占比、对手数量、净流入/净流出，或只是未达到某规则门槛时，判定 NOT_EVENT。
2. 若事件成立，遍历全部候选知识条目。只有原文事实完整满足某条 rule 的所有必要条件、内部数值门槛，且不命中排除条件，才判定 EXISTING。缺少任一事实值、时间窗口、比例、基线、关联关系或认定依据，均不得推测满足。
3. 事件成立但没有任何 rule 被完整满足时判定 NEW。相似、关键词命中或接近门槛不能判 EXISTING。
4. “案件来源事实/案例线索”中的具体资金行为可以作为带来源限定的事实；“待核验”不等于 NOT_EVENT，但不得升级为违法犯罪认定。
5. event_type_id 仅在 EXISTING 时填写，且必须来自 KB_CANDIDATES；NEW/NOT_EVENT 填空字符串。

KB_CANDIDATES：
{json.dumps(kb_candidates, ensure_ascii=False, indent=2)}

EXTRACTED_EVENTS：
{json.dumps(events, ensure_ascii=False, indent=2)}

SOURCE_TEXT：
{source_text}

只输出 JSON：
{{
  "reviews": [
    {{
      "event_id": "与候选事件一致",
      "decision": "EXISTING 或 NEW 或 NOT_EVENT",
      "event_type_id": "ETxxx或空字符串",
      "reason": "逐项说明原文事实如何满足或缺少必要条件/门槛/排除条件"
    }}
  ]
}}
"""
        result = self.chat_json(prompt)
        payload = result.get("payload")
        if payload is not None and not isinstance(payload.get("reviews"), list):
            return {
                **result,
                "payload": None,
                "reason": "LLM JSON does not contain a reviews array",
            }
        return result

    def extract_relationships(self, prompt: str) -> Dict[str, Any]:
        """Extract one explicitly scoped relationship category."""

        result = self.chat_json(prompt)
        payload = result.get("payload")
        if payload is not None and not isinstance(payload.get("relationships"), list):
            return {
                **result,
                "payload": None,
                "reason": "LLM JSON does not contain a relationships array",
            }
        return result

    def review_coreferences(self, prompt: str) -> Dict[str, Any]:
        """Review low-confidence coreference candidates as one JSON batch."""

        result = self.chat_json(prompt)
        payload = result.get("payload")
        if payload is not None and not isinstance(payload.get("reviews"), list):
            return {
                **result,
                "payload": None,
                "reason": "LLM JSON does not contain a reviews array",
            }
        return result

    def generate_analysis_texts(self, prompt: str) -> Dict[str, Any]:
        """Generate the two analysis text fields required by the graph workflow."""

        result = self.chat_json(prompt)
        payload = result.get("payload")
        required_fields = ("analysis_text1", "analysis_text2")
        if payload is not None and any(
            not isinstance(payload.get(field_name), str)
            or not payload.get(field_name, "").strip()
            for field_name in required_fields
        ):
            return {
                **result,
                "payload": None,
                "reason": (
                    "LLM JSON must contain non-empty analysis_text1 and "
                    "analysis_text2 strings"
                ),
            }
        return result

    def judge_duplicate_events(
        self,
        event_a: Dict[str, Any],
        event_b: Dict[str, Any],
        similarity: float,
    ) -> Dict[str, Any]:
        """Ask the configured model whether two high-similarity events are duplicates."""

        prompt = f"""你是反洗钱事件去重审核专家。请判断两个事件是否描述同一风险事件。

已知文本相似度：{similarity:.4f}

事件A：
{json.dumps(self._event_brief(event_a), ensure_ascii=False, indent=2)}

事件B：
{json.dumps(self._event_brief(event_b), ensure_ascii=False, indent=2)}

请只输出 JSON，不要 markdown：
{{
  "is_duplicate": true,
  "reason": "一句话说明判断依据"
}}
"""
        try:
            content = self.chat_text(prompt)
            if not content:
                return {"available": False, "is_duplicate": None, "reason": "LLM unavailable"}
            payload = self._parse_json_object(content)
            if payload is None:
                return {
                    "available": True,
                    "is_duplicate": None,
                    "reason": "LLM response is not valid JSON",
                    "raw": content,
                }
            is_duplicate = payload.get("is_duplicate")
            if not isinstance(is_duplicate, bool):
                return {
                    "available": True,
                    "is_duplicate": None,
                    "reason": "LLM is_duplicate must be a JSON boolean",
                    "raw": content,
                }
            return {
                "available": True,
                "is_duplicate": is_duplicate,
                "reason": payload.get("reason") or "",
                "raw": content,
            }
        except Exception as exc:
            return {"available": False, "is_duplicate": None, "reason": str(exc)}

    def embed_texts(self, texts: List[str]) -> Optional[List[List[float]]]:
        if not self.embedding_available or self.client is None:
            return None
        try:
            def request() -> Optional[List[List[float]]]:
                response = self.client.post(
                    f"{self.base_url}/embeddings",
                    json={
                        "model": self.embedding_model,
                        "input": texts,
                    },
                )
                response.raise_for_status()
                return self._extract_embeddings(response.json())

            if self.service_gate is not None:
                return self.service_gate.run(request)
            return request()
        except Exception:
            return None

    def close(self) -> None:
        """Release the underlying HTTP connection pool."""

        if self.client is not None:
            self.client.close()

    def _event_brief(self, event: Dict[str, Any]) -> Dict[str, Any]:
        fields = (
            "event_name",
            "event_type",
            "event_description",
            "event_start_date",
            "event_end_date",
            "risk_indicator",
            "involved_entities",
            "source_section",
        )
        return {field: event.get(field) for field in fields}

    def _extract_text(self, response: Any) -> str:
        if isinstance(response, dict):
            try:
                return str(response["choices"][0]["message"]["content"])
            except (KeyError, IndexError, TypeError):
                return str(response)
        try:
            for item in response.output:
                if getattr(item, "type", None) == "message":
                    for content_item in getattr(item, "content", []):
                        text = getattr(content_item, "text", None)
                        if text:
                            return text
        except Exception:
            pass
        return str(response)

    def _extract_embeddings(self, response: Any) -> Optional[List[List[float]]]:
        data = getattr(response, "data", None)
        if data is None and isinstance(response, dict):
            data = response.get("data")
        if data is None:
            return None

        vectors: List[List[float]] = []
        for item in data:
            embedding = getattr(item, "embedding", None)
            if embedding is None and isinstance(item, dict):
                embedding = item.get("embedding")
            if embedding is None:
                return None
            vectors.append([float(value) for value in embedding])
        return vectors

    def _parse_json_object(self, text: str) -> Optional[Dict[str, Any]]:
        """Decode the first complete JSON object, allowing fences or commentary.

        A greedy ``{...}`` regex merges multiple objects and also treats braces
        inside surrounding prose as JSON. ``raw_decode`` instead stops at the
        exact end of each candidate object without silently repairing truncated
        model output.
        """
        candidate = text.strip()
        if candidate.startswith("```"):
            candidate = re.sub(r"^```(?:json)?\s*", "", candidate, flags=re.IGNORECASE)
            candidate = re.sub(r"\s*```$", "", candidate).strip()
        try:
            payload = json.loads(candidate)
        except json.JSONDecodeError:
            pass
        else:
            return payload if isinstance(payload, dict) else None

        decoder = json.JSONDecoder()
        for index, character in enumerate(candidate):
            if character != "{":
                continue
            try:
                payload, _ = decoder.raw_decode(candidate, index)
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                return payload
        return None


# Backward-compatible import name for existing callers.
ArkLLMClient = DeepSeekLLMClient
