"""Case-similarity matching and visualization package."""

from case_similarity.matcher import (
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_RERANKER_MODEL,
    EMBEDDING_RECALL_THRESHOLD,
    MIN_EVENT_COUNT_RATIO,
    MIN_EVENT_OVERLAP,
    MIN_SHARED_EVENT_TYPES,
    TOP_RESULT_COUNT,
    load_case,
    run_batch_queries,
)

__all__ = [
    "DEFAULT_EMBEDDING_MODEL",
    "DEFAULT_RERANKER_MODEL",
    "EMBEDDING_RECALL_THRESHOLD",
    "MIN_EVENT_COUNT_RATIO",
    "MIN_EVENT_OVERLAP",
    "MIN_SHARED_EVENT_TYPES",
    "TOP_RESULT_COUNT",
    "load_case",
    "run_batch_queries",
]
