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
            guide = cls(
                target_chars=int(value["target_chars"]),
                soft_min_chars=int(value["soft_min_chars"]),
                soft_max_chars=int(value["soft_max_chars"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise InputValidationError(f"段落 {paragraph_id} 的长度配置无效") from exc
        if not 0 < guide.soft_min_chars <= guide.target_chars <= guide.soft_max_chars:
            raise InputValidationError(f"段落 {paragraph_id} 的长度配置顺序无效")
        return guide


@dataclass(frozen=True)
class WorkflowConfig:
    kb_top_k: int
    node_workers: int
    model_attempts: int
    max_rewrite_rounds: int
    paragraph_lengths: dict[str, LengthGuide]

    @classmethod
    def load(cls, path: Path | None = None) -> "WorkflowConfig":
        if path is None:
            raw = files("aml_analysis_workflow").joinpath("defaults.json").read_text(
                encoding="utf-8"
            )
        else:
            config_path = Path(path).expanduser().resolve()
            if not config_path.is_file():
                raise FileNotFoundError(f"工作流配置不存在: {config_path}")
            raw = config_path.read_text(encoding="utf-8-sig")
        try:
            value = json.loads(raw)
            lengths = {
                paragraph_id: LengthGuide.from_dict(guide, paragraph_id)
                for paragraph_id, guide in value["paragraph_lengths"].items()
            }
            config = cls(
                kb_top_k=int(value["kb_top_k"]),
                node_workers=int(value["node_workers"]),
                model_attempts=int(value["model_attempts"]),
                max_rewrite_rounds=int(value["max_rewrite_rounds"]),
                paragraph_lengths=lengths,
            )
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise InputValidationError("工作流配置不是有效的JSON配置") from exc
        if not 1 <= config.kb_top_k <= 5:
            raise InputValidationError("kb_top_k 必须在1到5之间")
        if min(
            config.node_workers,
            config.model_attempts,
            config.max_rewrite_rounds,
        ) < 1:
            raise InputValidationError("工作流并发数和重试次数必须大于0")
        return config

