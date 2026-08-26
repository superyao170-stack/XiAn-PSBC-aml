"""Anti-fraud clue-channel normalization.

The source channel is deliberately separated from the event's operation type.
For example, a police-issued clue may still contain mobile-banking operations.
"""
from __future__ import annotations

from typing import Any


CHANNELS = (
    "公安机关下发",
    "手机银行",
    "网上银行",
    "交易监测",
    "客服投诉/客户举报",
    "柜面/ATM",
    "同业/支付机构协查",
    "监管/反诈平台",
    "其他",
)

ALIASES = {
    "警察下发": "公安机关下发",
    "公安下发": "公安机关下发",
    "公安机关": "公安机关下发",
    "涉案名单": "公安机关下发",
    "app": "手机银行",
    "手机app": "手机银行",
    "网银": "网上银行",
    "模型": "交易监测",
    "规则命中": "交易监测",
    "投诉": "客服投诉/客户举报",
    "举报": "客服投诉/客户举报",
    "柜台": "柜面/ATM",
    "atm": "柜面/ATM",
    "支付机构": "同业/支付机构协查",
    "反诈中心": "监管/反诈平台",
}


def normalize_channel(value: Any) -> str:
    text = str(value or "").strip()
    if text in CHANNELS:
        return text
    lowered = text.lower()
    for alias, channel in ALIASES.items():
        if alias.lower() in lowered:
            return channel
    return "其他"


def infer_channel(basic_info: dict[str, Any], event_chain: list[dict[str, Any]]) -> str:
    for key in ("渠道", "channel", "source_channel", "case_channel"):
        if str(basic_info.get(key) or "").strip():
            return normalize_channel(basic_info[key])
    source = " ".join(
        str(basic_info.get(key) or "")
        for key in ("case_trigger", "case_description", "submission_direction")
    )
    inferred = normalize_channel(source)
    if inferred != "其他":
        return inferred
    event_text = " ".join(
        f"{item.get('类型', '')} {item.get('具体内容', '')}"
        for item in event_chain
        if isinstance(item, dict)
    )
    return normalize_channel(event_text)
