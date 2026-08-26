from __future__ import annotations

import json
from importlib.resources import files
from pathlib import Path
from typing import Any


class PromptStore:
    """Load and render the shared system prompt and node-specific task prompts."""

    def __init__(self, override_dir: Path | None = None) -> None:
        self.override_dir = Path(override_dir).expanduser().resolve() if override_dir else None
        if self.override_dir is not None and not self.override_dir.is_dir():
            raise FileNotFoundError(f"Prompt目录不存在: {self.override_dir}")

    def system(self) -> str:
        return self._read("system")

    def raw(self, name: str) -> str:
        """Return an unrendered node prompt for documentation and review."""
        return self._read(name)

    def render(self, name: str, payload: dict[str, Any]) -> str:
        template = self._read(name)
        placeholder = "{{PAYLOAD_JSON}}"
        if placeholder not in template:
            raise ValueError(f"Prompt {name} 缺少 {placeholder}")
        return template.replace(
            placeholder,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        )

    def _read(self, name: str) -> str:
        filename = f"{name}.txt"
        if self.override_dir is not None:
            path = self.override_dir / filename
            if not path.is_file():
                raise FileNotFoundError(f"Prompt文件不存在: {path}")
            return path.read_text(encoding="utf-8-sig").strip()
        return (
            files("aml_analysis_workflow")
            .joinpath("prompts", filename)
            .read_text(encoding="utf-8")
            .strip()
        )
