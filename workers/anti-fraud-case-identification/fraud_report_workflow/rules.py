from __future__ import annotations

import json
import re
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any, Iterable

from .errors import InputValidationError
from .models import RuleCandidate


@dataclass(frozen=True)
class FraudRuleLibrary:
    version: str | None
    rules: tuple[RuleCandidate, ...]

    @classmethod
    def load(cls, path: Path | None = None) -> "FraudRuleLibrary":
        if path is None:
            raw = files(__package__ or "fraud_report_workflow").joinpath("fraud_risk_rules.json").read_text(encoding="utf-8")
            source = "内置反欺诈规则库"
        else:
            rule_path = Path(path).expanduser().resolve()
            if not rule_path.is_file():
                raise FileNotFoundError(f"反欺诈规则库不存在: {rule_path}")
            raw = rule_path.read_text(encoding="utf-8-sig")
            source = str(rule_path)
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise InputValidationError(f"反欺诈规则库不是有效JSON: {source}") from exc
        entries = value.get("event_types") if isinstance(value, dict) else None
        if not isinstance(entries, list) or not entries:
            raise InputValidationError("反欺诈规则库缺少event_types数组")
        rules: list[RuleCandidate] = []
        seen: set[str] = set()
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict) or set(entry) != {"id", "name", "rule", "category"}:
                raise InputValidationError(f"规则event_types[{index}]字段不符合四字段契约")
            item = RuleCandidate(
                id=str(entry["id"]).strip(),
                name=str(entry["name"]).strip(),
                rule=str(entry["rule"]).strip(),
                category=str(entry["category"]).strip(),
            )
            if not all((item.id, item.name, item.rule, item.category)) or item.id in seen:
                raise InputValidationError(f"规则event_types[{index}]为空或ID重复")
            seen.add(item.id)
            rules.append(item)
        metadata = value.get("metadata") if isinstance(value.get("metadata"), dict) else {}
        return cls(str(metadata.get("version") or "").strip() or None, tuple(rules))

    def retrieve(self, query_values: Iterable[Any], top_k: int) -> list[RuleCandidate]:
        query = " ".join(str(item) for item in query_values if item is not None)
        query_tokens = _tokens(query)
        ranked: list[tuple[float, str, RuleCandidate]] = []
        for rule in self.rules:
            score = 0.0
            if rule.id.lower() in query.lower():
                score += 100.0
            if rule.name in query:
                score += 60.0
            overlap = query_tokens & _tokens(f"{rule.name} {rule.category} {rule.rule}")
            score += min(20.0, len(overlap) * 0.5)
            if score > 0:
                ranked.append((score, rule.id, rule))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        return [item[2] for item in ranked[: min(max(1, top_k), 10)]]


def _tokens(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    latin = set(re.findall(r"[a-z]+\d*|fr\d+", normalized))
    chinese = re.findall(r"[\u4e00-\u9fff]+", normalized)
    bigrams = {word[index:index + 2] for word in chinese for index in range(max(0, len(word) - 1))}
    return latin | bigrams
