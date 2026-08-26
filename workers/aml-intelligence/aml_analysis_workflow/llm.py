"""Model protocol used by the integrated analysis workflow."""

from __future__ import annotations

from typing import Any, Protocol


class JsonModel(Protocol):
    model_name: str

    def generate(
        self,
        system_prompt: str,
        user_prompt: str,
    ) -> dict[str, Any]: ...
