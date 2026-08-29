from __future__ import annotations

import json
import os
import re
from typing import Any, Protocol

try:
    import httpx
except ImportError:  # pragma: no cover - deployment dependency validation
    httpx = None  # type: ignore[assignment]

from .errors import ModelResponseError


DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
DEEPSEEK_BASE_URL = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip()
DEEPSEEK_CHAT_MODEL = os.environ.get("DEEPSEEK_CHAT_MODEL", "deepseek-chat").strip()
DEEPSEEK_TIMEOUT_SECONDS = float(os.environ.get("DEEPSEEK_TIMEOUT_SECONDS", "180"))


class JsonModel(Protocol):
    model_name: str

    def generate(self, system_prompt: str, user_prompt: str) -> dict[str, Any]: ...


class DeepSeekModel:
    model_name = DEEPSEEK_CHAT_MODEL

    def generate(self, system_prompt: str, user_prompt: str) -> dict[str, Any]:
        if not DEEPSEEK_API_KEY:
            raise ModelResponseError("请通过环境变量配置 DEEPSEEK_API_KEY")
        if httpx is None:
            raise ModelResponseError("反欺诈报告工作流缺少 httpx 运行依赖")
        body = {
            "model": DEEPSEEK_CHAT_MODEL,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": {"type": "json_object"},
            "stream": False,
            "temperature": 0.2,
        }
        try:
            response = httpx.post(
                f"{DEEPSEEK_BASE_URL.rstrip('/')}/chat/completions",
                json=body,
                timeout=DEEPSEEK_TIMEOUT_SECONDS,
                headers={"Authorization": f"Bearer {DEEPSEEK_API_KEY}", "Content-Type": "application/json", "Accept": "application/json"},
            )
            response.raise_for_status()
            envelope = response.json()
            content = str(envelope["choices"][0]["message"]["content"])
        except httpx.HTTPStatusError as exc:
            raise ModelResponseError(
                f"DeepSeek HTTP {exc.response.status_code}: {exc.response.text[:1000] or exc.response.reason_phrase}"
            ) from exc
        except httpx.RequestError as exc:
            raise ModelResponseError(f"DeepSeek请求失败: {exc}") from exc
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise ModelResponseError("DeepSeek返回结构无效") from exc
        return parse_json_object(content)


def parse_json_object(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if stripped.startswith("```") and stripped.endswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped, flags=re.IGNORECASE)
        stripped = re.sub(r"\s*```$", "", stripped)
    try:
        value = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise ModelResponseError("模型输出不是有效JSON对象") from exc
    if not isinstance(value, dict):
        raise ModelResponseError("模型输出必须是JSON对象")
    return value
