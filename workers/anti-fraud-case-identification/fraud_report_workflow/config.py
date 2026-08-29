from __future__ import annotations

import json
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

from .errors import InputValidationError


@dataclass(frozen=True)
class LengthGuide:
    target_chars: int
    soft_min_chars: int
    soft_max_chars: int

    @classmethod
    def from_dict(cls, value: dict[str, Any], paragraph_id: str) -> "LengthGuide":
        try:
            item = cls(int(value["target_chars"]), int(value["soft_min_chars"]), int(value["soft_max_chars"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise InputValidationError(f"段落{paragraph_id}长度配置无效") from exc
        if not 0 < item.soft_min_chars <= item.target_chars <= item.soft_max_chars:
            raise InputValidationError(f"段落{paragraph_id}长度配置顺序无效")
        return item


@dataclass(frozen=True)
class WorkflowConfig:
    rule_top_k: int
    node_workers: int
    model_attempts: int
    max_risk_revision_rounds: int
    max_rewrite_rounds: int
    allowed_risk_types: tuple[str, ...]
    paragraph_lengths: dict[str, LengthGuide]

    @classmethod
    def load(cls, path: Path | None = None) -> "WorkflowConfig":
        raw = (
            files(__package__ or "fraud_report_workflow").joinpath("defaults.json").read_text(encoding="utf-8")
            if path is None
            else Path(path).expanduser().resolve().read_text(encoding="utf-8-sig")
        )
        try:
            value = json.loads(raw)
            config = cls(
                rule_top_k=int(value["rule_top_k"]),
                node_workers=int(value["node_workers"]),
                model_attempts=int(value["model_attempts"]),
                max_risk_revision_rounds=int(value["max_risk_revision_rounds"]),
                max_rewrite_rounds=int(value["max_rewrite_rounds"]),
                allowed_risk_types=tuple(str(item) for item in value["allowed_risk_types"]),
                paragraph_lengths={key: LengthGuide.from_dict(item, key) for key, item in value["paragraph_lengths"].items()},
            )
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise InputValidationError("工作流配置不是有效JSON") from exc
        if not 1 <= config.rule_top_k <= 10:
            raise InputValidationError("rule_top_k必须在1到10之间")
        if min(config.node_workers, config.model_attempts, config.max_risk_revision_rounds, config.max_rewrite_rounds) < 1:
            raise InputValidationError("并发数与重试次数必须大于0")
        if not config.allowed_risk_types or len(config.allowed_risk_types) != len(set(config.allowed_risk_types)):
            raise InputValidationError("allowed_risk_types不能为空或重复")
        return config
