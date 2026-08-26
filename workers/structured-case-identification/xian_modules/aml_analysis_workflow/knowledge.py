from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from datagraph_bank.knowledge_base import (
    KnowledgeBaseUnavailable,
    create_knowledge_repository,
)
from .errors import InputValidationError
from .models import KnowledgeCandidate


@dataclass(frozen=True)
class KnowledgeBase:
    version: str | None
    events: tuple[KnowledgeCandidate, ...]
    repository: Any | None = None

    @classmethod
    def load(
        cls,
        path: Path | None = None,
        *,
        backend: str | None = None,
        qdrant_url: str | None = None,
        qdrant_path: Path | str | None = None,
        qdrant_collection: str | None = None,
        bge_model: str | None = None,
    ) -> "KnowledgeBase":
        if path is None and not backend and not qdrant_url and not qdrant_path:
            return cls(None, ())
        events: list[KnowledgeCandidate] = []
        try:
            repository = create_knowledge_repository(
                json_path=path,
                backend=backend,
                qdrant_url=qdrant_url,
                qdrant_path=qdrant_path,
                qdrant_collection=qdrant_collection,
                bge_model=bge_model,
            )
        except (FileNotFoundError, KnowledgeBaseUnavailable, ValueError) as exc:
            raise InputValidationError(f"风险事件知识库加载失败: {exc}") from exc

        for record in repository.list_records():
            events.append(
                KnowledgeCandidate(
                    id=record.id,
                    name=record.name,
                    category=record.category,
                    rule=_reference_rule(record.rule),
                )
            )
        return cls(repository.version, tuple(events), repository)

    def retrieve(
        self,
        query_values: Iterable[Any],
        preferred_categories: Iterable[str],
        top_k: int,
    ) -> list[KnowledgeCandidate]:
        if not self.events:
            return []
        query = " ".join(str(value) for value in query_values if value is not None)
        if self.repository is not None and getattr(
            self.repository,
            "supports_semantic_search",
            False,
        ):
            hits = self.repository.search(
                query,
                preferred_categories=preferred_categories,
                top_k=top_k,
            )
            return [
                KnowledgeCandidate(
                    id=hit.record.id,
                    name=hit.record.name,
                    category=hit.record.category,
                    rule=_reference_rule(hit.record.rule),
                )
                for hit in hits
            ]

        query_tokens = _tokens(query)
        categories = set(preferred_categories)
        ranked: list[tuple[float, str, KnowledgeCandidate]] = []
        for event in self.events:
            score = 12.0 if event.category in categories else 0.0
            if event.id and event.id.lower() in query.lower():
                score += 100.0
            if event.name and event.name in query:
                score += 60.0
            event_tokens = _tokens(f"{event.name} {event.category} {event.rule}")
            score += min(8.0, len(query_tokens & event_tokens) * 0.4)
            if score > 0:
                ranked.append((score, event.id, event))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        return [item[2] for item in ranked[: min(max(1, top_k), 5)]]


def _reference_rule(rule: str) -> str:
    parts = re.split(r"(?<=[。；;])\s*", rule.strip())
    kept = [part for part in parts if part and "内部数值门槛" not in part]
    return " ".join(kept).strip()


def _tokens(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    latin = set(re.findall(r"[a-z]+\d*|et\d+", normalized))
    chinese = re.findall(r"[\u4e00-\u9fff]+", normalized)
    bigrams = {
        word[index : index + 2]
        for word in chinese
        for index in range(max(0, len(word) - 1))
    }
    return latin | bigrams
