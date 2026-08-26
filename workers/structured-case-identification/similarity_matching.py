#!/usr/bin/env python3
"""Adapter for the original Xi'an heterogeneous-graph GED matcher."""
from __future__ import annotations

import argparse
import json
import multiprocessing
import os
import sys
import tempfile
from pathlib import Path
from time import monotonic
from typing import Any, Sequence


WORKER_ROOT = Path(__file__).resolve().parent
XIAN_MODULE_ROOT = WORKER_ROOT / "xian_modules"


def _load_local_environment(path: Path) -> None:
    """Make the standalone matcher use the same worker-local configuration."""
    if not path.is_file():
        return
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.removeprefix("export ").strip()
        if not key or not key.replace("_", "A").isalnum() or key[0].isdigit():
            continue
        value = value.strip().strip("\"'")
        if not os.environ.get(key, "").strip():
            os.environ[key] = value


_load_local_environment(WORKER_ROOT / ".env")
if str(XIAN_MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(XIAN_MODULE_ROOT))

from case_similarity import (  # noqa: E402
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_RERANKER_MODEL,
    EMBEDDING_RECALL_THRESHOLD,
    MIN_EVENT_COUNT_RATIO,
    MIN_EVENT_OVERLAP,
    MIN_SHARED_EVENT_TYPES,
    TOP_RESULT_COUNT,
    load_case,
)
from case_similarity.matcher import (  # noqa: E402
    SIMILARITY_FINGERPRINT_VERSION,
    clear_reranker,
    clear_semantic_embedding,
    match_query,
)


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _arguments(settings: dict[str, Any] | None = None) -> argparse.Namespace:
    """Build the same settings object as xian ``csv_case_pipeline.py``."""
    values = settings or {}
    return argparse.Namespace(
        top_k=TOP_RESULT_COUNT,
        retrieval_k=max(5, min(int(values.get("gedCandidateLimit", 25)), 100)),
        min_shared_event_types=int(
            values.get("minSharedEventTypes", MIN_SHARED_EVENT_TYPES)
        ),
        min_event_overlap=float(values.get("minEventOverlap", MIN_EVENT_OVERLAP)),
        min_event_count_ratio=float(
            values.get("minEventCountRatio", MIN_EVENT_COUNT_RATIO)
        ),
        no_add_to_library=False,
        library_on_conflict="replace",
        rolling_library=False,
        embedding_model=str(
            values.get("embeddingModel")
            or os.environ.get("STRUCTURED_CASE_EMBEDDING_MODEL")
            or os.environ.get("AML_EMBEDDING_MODEL")
            or DEFAULT_EMBEDDING_MODEL
        ),
        embedding_device=str(values.get("embeddingDevice", "cpu")),
        embedding_local_files_only=bool(
            values.get(
                "embeddingLocalFilesOnly",
                _env_bool("STRUCTURED_CASE_MODEL_LOCAL_FILES_ONLY", True),
            )
        ),
        embedding_recall_threshold=float(
            values.get("embeddingRecallThreshold", EMBEDDING_RECALL_THRESHOLD)
        ),
        reranker_model=str(
            values.get("rerankerModel")
            or os.environ.get("STRUCTURED_CASE_RERANKER_MODEL")
            or os.environ.get("AML_RERANKER_MODEL")
            or DEFAULT_RERANKER_MODEL
        ),
        reranker_device=str(values.get("rerankerDevice", "cpu")),
        reranker_local_files_only=bool(
            values.get(
                "rerankerLocalFilesOnly",
                _env_bool("STRUCTURED_CASE_MODEL_LOCAL_FILES_ONLY", True),
            )
        ),
        reranker_max_length=int(values.get("rerankerMaxLength", 512)),
    )


def _bounded_seconds(
    settings: dict[str, Any], key: str, environment: str, default: float
) -> float:
    raw = settings.get(key, os.environ.get(environment, default))
    try:
        return max(5.0, min(float(raw), 300.0))
    except (TypeError, ValueError):
        return default


def _match_worker(
    query_path: str,
    history_paths: list[str],
    settings: dict[str, Any],
    semantic_enabled: bool,
    output_path: str,
) -> None:
    """Execute one matching attempt in an independently terminable process."""
    destination = Path(output_path)
    temporary = destination.with_suffix(".tmp")
    try:
        query = Path(query_path).resolve()
        candidates = [
            (Path(path).resolve(), load_case(Path(path).resolve()))
            for path in history_paths
            if Path(path).is_file()
        ]
        if semantic_enabled:
            args = _arguments(settings)
        else:
            clear_semantic_embedding()
            clear_reranker()
            fallback_settings = dict(settings)
            fallback_settings["embeddingModel"] = ""
            fallback_settings["rerankerModel"] = ""
            args = _arguments(fallback_settings)
            args.embedding_model = ""
            args.reranker_model = ""
        raw = match_query(query, candidates, args)
        payload = {"ok": True, "raw": raw}
    except Exception as exc:
        payload = {
            "ok": False,
            "errorType": type(exc).__name__,
            "error": str(exc),
        }
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, destination)


def _run_attempt(
    query: Path,
    candidates: Sequence[tuple[Path, Any]],
    settings: dict[str, Any],
    semantic_enabled: bool,
    timeout_seconds: float,
) -> tuple[dict[str, Any] | None, str | None]:
    """Return a raw result or a concise reason suitable for GED fallback."""
    with tempfile.TemporaryDirectory(prefix="structured-similarity-") as directory:
        output_path = Path(directory) / "result.json"
        context = multiprocessing.get_context("spawn")
        process = context.Process(
            target=_match_worker,
            args=(
                str(query),
                [str(path) for path, _ in candidates],
                settings,
                semantic_enabled,
                str(output_path),
            ),
        )
        process.start()
        process.join(timeout_seconds)
        if process.is_alive():
            process.terminate()
            process.join(5)
            if process.is_alive():
                process.kill()
                process.join(2)
            return None, f"相似度子进程超过 {timeout_seconds:.0f} 秒"
        if not output_path.is_file():
            return None, f"相似度子进程异常退出（exit={process.exitcode}）"
        payload = json.loads(output_path.read_text(encoding="utf-8"))
        if payload.get("ok") and isinstance(payload.get("raw"), dict):
            return payload["raw"], None
        return None, str(payload.get("error") or "相似度子进程执行失败")


def _presentation_results(raw: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "rank": item.get("rank"),
            "caseId": item.get("case_id"),
            "caseName": item.get("case_name"),
            "similarity": item.get("similarity"),
            "normalizedGed": item.get("normalized_ged"),
            "ged": item.get("ged"),
            "vectorSimilarity": item.get("vector_similarity"),
            "sharedEventTypeCount": item.get("shared_event_type_count"),
            "eventTypeOverlap": item.get("event_type_overlap"),
            "eventCountRatio": item.get("event_count_ratio"),
            "matchedSubgraph": item.get("matched_subgraph"),
            "eventSimilaritySummary": item.get("event_similarity_summary"),
            "sourceFile": item.get("source_file"),
        }
        for item in raw.get("results", [])
    ]


def match_similar_cases(
    query_path: Path,
    history_paths: Sequence[Path],
    settings: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Match within a stage deadline, degrading to structural GED when needed."""
    values = dict(settings or {})
    stage_timeout = _bounded_seconds(
        values,
        "timeoutSeconds",
        "STRUCTURED_CASE_SIMILARITY_TIMEOUT_SECONDS",
        180.0,
    )
    semantic_timeout = min(
        _bounded_seconds(
            values,
            "semanticTimeoutSeconds",
            "STRUCTURED_CASE_SEMANTIC_TIMEOUT_SECONDS",
            90.0,
        ),
        max(5.0, stage_timeout * 0.6),
    )
    stage_started = monotonic()
    query = Path(query_path).resolve()
    candidates = [
        (Path(path).resolve(), load_case(Path(path).resolve()))
        for path in history_paths
        if Path(path).is_file()
    ]
    query_graph = load_case(query)
    candidates = [
        (path, graph)
        for path, graph in candidates
        if path != query and graph.case_id != query_graph.case_id
    ]
    if not candidates:
        raw = {
            "query_case": {
                "case_id": query_graph.case_id,
                "case_name": query_graph.case_name,
                "source_file": str(query),
            },
            "history_case_count": 0,
            "history_source_mode": "case_library_snapshot",
            "matching_mode": "full_cartesian_all_history_cases",
            "pair_match_count": 0,
            "output_format": "top_at_5_with_full_graphs_and_similarity_points",
            "top_k": TOP_RESULT_COUNT,
            "anchored_history_case_count": 0,
            "match_summary": "历史案例库为空，未执行 GED 配对。",
            "results": [],
        }
        degraded = False
        degradation_reason = None
    else:
        raw, semantic_error = _run_attempt(
            query,
            candidates,
            values,
            semantic_enabled=True,
            timeout_seconds=semantic_timeout,
        )
        degraded = raw is None
        degradation_reason = semantic_error
        if raw is None:
            remaining = stage_timeout - (monotonic() - stage_started)
            if remaining < 5.0:
                raise TimeoutError(
                    f"相似度匹配超过独立时限 {stage_timeout:.0f} 秒；"
                    f"语义模型失败原因：{semantic_error}"
                )
            raw, fallback_error = _run_attempt(
                query,
                candidates,
                values,
                semantic_enabled=False,
                timeout_seconds=remaining,
            )
            if raw is None:
                raise TimeoutError(
                    f"结构化/GED 降级匹配未能在独立时限 {stage_timeout:.0f} 秒内完成；"
                    f"语义模型失败原因：{semantic_error}；"
                    f"GED 失败原因：{fallback_error}"
                )
            raw["matching_mode"] = "event_type_vector_recall_then_structural_ged_fallback"
            raw["match_summary"] = (
                str(raw.get("match_summary") or "")
                + f" 语义模型不可用，已自动降级为结构化/GED 匹配：{semantic_error}"
            ).strip()
    raw["execution_control"] = {
        "stage_timeout_seconds": stage_timeout,
        "semantic_timeout_seconds": semantic_timeout,
        "elapsed_seconds": round(monotonic() - stage_started, 3),
        "degraded": degraded,
        "degradation_reason": degradation_reason,
        "fallback_mode": "event_type_vector_recall_then_structural_ged",
    }
    return {
        "algorithm": "case_similarity.match_query",
        "algorithmVersion": SIMILARITY_FINGERPRINT_VERSION,
        "degraded": degraded,
        "degradationReason": degradation_reason,
        "timeoutSeconds": stage_timeout,
        "similarCases": _presentation_results(raw),
        "rawResult": raw,
    }


def main() -> int:
    try:
        request = json.load(sys.stdin)
        if not isinstance(request, dict):
            raise ValueError("请求必须是 JSON 对象")
        query_path = Path(str(request.get("queryFile") or ""))
        history_value = request.get("historyFiles") or []
        if not query_path.is_file() or not isinstance(history_value, list):
            raise ValueError("请求必须包含 queryFile 和 historyFiles")
        result = match_similar_cases(
            query_path,
            [Path(str(path)) for path in history_value],
            request.get("settings") if isinstance(request.get("settings"), dict) else None,
        )
        json.dump(result, sys.stdout, ensure_ascii=False)
        sys.stdout.write("\n")
        return 0
    except Exception as exc:
        json.dump(
            {"status": "FAILED", "errorType": type(exc).__name__, "error": str(exc)},
            sys.stdout,
            ensure_ascii=False,
        )
        sys.stdout.write("\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
