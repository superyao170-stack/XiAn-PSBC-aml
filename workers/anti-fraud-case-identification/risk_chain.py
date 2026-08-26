"""Turn a raw event chain into an auditable anti-fraud risk-event chain."""
from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any

from channel import normalize_channel


class FraudRuleEngine:
    def __init__(self, rule_path: Path) -> None:
        value = json.loads(rule_path.read_text(encoding="utf-8-sig"))
        self.version = str(value.get("metadata", {}).get("version") or "")
        self.rules = tuple(value.get("event_types") or ())
        if not self.rules:
            raise ValueError("反欺诈规则库缺少 event_types")

    def build(
        self, channel: str, event_chain: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        source_channel = normalize_channel(channel)
        grouped: dict[str, list[tuple[int, dict[str, Any]]]] = defaultdict(list)
        for index, raw in enumerate(event_chain):
            if not isinstance(raw, dict):
                continue
            text = self._event_text(raw)
            event_type = str(raw.get("类型") or raw.get("type") or "")
            for rule in self.rules:
                if self._matches(rule, source_channel, event_type, text):
                    grouped[str(rule["id"])].append((index, raw))

        output: list[dict[str, Any]] = []
        rules_by_id = {str(rule["id"]): rule for rule in self.rules}
        for sequence, rule_id in enumerate(
            sorted(grouped, key=lambda item: grouped[item][0][0]), start=1
        ):
            rule = rules_by_id[rule_id]
            matches = grouped[rule_id]
            times = [self._event_time(item) for _, item in matches]
            times = [value for value in times if value]
            evidence = [
                {
                    "source_index": index,
                    "occurred_at": self._event_time(item),
                    "event_type": str(item.get("类型") or item.get("type") or ""),
                    "content": str(item.get("具体内容") or item.get("content") or ""),
                }
                for index, item in matches
            ]
            output.append(
                {
                    "risk_event_id": f"RISK-{sequence:04d}",
                    "rule_id": rule_id,
                    "rule_name": rule["name"],
                    "category": rule["category"],
                    "source_channel": source_channel,
                    "event_start": min(times) if times else "",
                    "event_end": max(times) if times else "",
                    "risk_level": rule.get("risk_level", "中"),
                    "score": rule.get("score", 0.6),
                    "reason": rule["rule"],
                    "evidence_count": len(evidence),
                    "evidence": evidence,
                    "conclusion_boundary": "规则命中仅表示待核验风险，不构成诈骗或犯罪认定。",
                }
            )
        return output

    @staticmethod
    def _event_text(item: dict[str, Any]) -> str:
        return " ".join(
            str(item.get(key) or "")
            for key in ("类型", "具体内容", "type", "content", "rule_code")
        ).lower()

    @staticmethod
    def _event_time(item: dict[str, Any]) -> str:
        return str(item.get("发生时间") or item.get("occurred_at") or "").strip()

    @staticmethod
    def _matches(
        rule: dict[str, Any], channel: str, event_type: str, text: str
    ) -> bool:
        channels = {str(value) for value in rule.get("channels") or []}
        if channels and channel not in channels and "通用" not in channels:
            # Transaction and operation rules embedded in a police-issued clue
            # remain applicable when the row itself clearly identifies that source.
            row_channels = {str(value) for value in rule.get("row_channels") or []}
            if not any(value in event_type or value.lower() in text for value in row_channels):
                return False
        excluded = rule.get("exclude_any") or []
        if any(str(token).lower() in text for token in excluded):
            return False
        required_all = rule.get("match_all") or []
        if required_all and not all(str(token).lower() in text for token in required_all):
            return False
        patterns = rule.get("match_any") or []
        if not patterns:
            return bool(required_all)
        return any(re.search(str(pattern), text, flags=re.IGNORECASE) for pattern in patterns)
