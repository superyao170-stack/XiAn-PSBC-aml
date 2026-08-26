"""Unified command-line adapter for AML text generation and case similarity.

The algorithms in this directory are kept as independent, reviewable Python
sources.  This adapter provides a small JSON-over-stdio boundary so the Spring
Boot application can execute them without coupling Java code to their internal
models.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from aml_analysis_workflow.config import WorkflowConfig
from aml_analysis_workflow.csv_input import read_cases
from aml_analysis_workflow.knowledge import KnowledgeBase
from aml_analysis_workflow.prompt_store import PromptStore
from aml_analysis_workflow.workflow import AnalysisWorkflow
from case_similarity import (
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_RERANKER_MODEL,
    EMBEDDING_RECALL_THRESHOLD,
    MIN_EVENT_COUNT_RATIO,
    MIN_EVENT_OVERLAP,
    MIN_SHARED_EVENT_TYPES,
    load_case,
)
from case_similarity.matcher import SIMILARITY_FINGERPRINT_VERSION, match_query


WORKER_ID = "AML_INTELLIGENCE_WORKER"
WORKER_VERSION = "1.2.0"
TEXT_ALGORITHM_ID = "AML_ANALYSIS_TEXT_GENERATION"
SIMILARITY_ALGORITHM_ID = "CASE_GRAPH_SIMILARITY_GED"

EMBEDDING_MODEL = os.getenv("AML_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)
RERANKER_MODEL = os.getenv("AML_RERANKER_MODEL", DEFAULT_RERANKER_MODEL)
EMBEDDING_DEVICE = os.getenv("AML_EMBEDDING_DEVICE", "cpu")
RERANKER_DEVICE = os.getenv("AML_RERANKER_DEVICE", "cpu")
MODEL_LOCAL_FILES_ONLY = os.getenv("AML_MODEL_LOCAL_FILES_ONLY", "false").lower() in {
    "1",
    "true",
    "yes",
}

def _load_worker_environment(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and not os.environ.get(key):
            os.environ[key] = value


_load_worker_environment(Path(__file__).resolve().parent / ".env")

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "").strip()
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_CHAT_MODEL = os.getenv("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash")

LLM_API_KEY = DEEPSEEK_API_KEY
LLM_API_URL = f"{DEEPSEEK_BASE_URL.rstrip('/')}/chat/completions"
LLM_MODEL = DEEPSEEK_CHAT_MODEL
LLM_TIMEOUT_SECONDS = float(os.getenv("DEEPSEEK_TIMEOUT_SECONDS", "90"))
LLM_TEMPERATURE = float(os.getenv("DEEPSEEK_TEMPERATURE", "0.1"))
LLM_MAX_TOKENS = int(os.getenv("DEEPSEEK_MAX_TOKENS", "4096"))


class OpenAICompatibleJsonModel:
    """Minimal OpenAI-compatible JSON model client used by the workflow."""

    def __init__(self) -> None:
        self.api_url = LLM_API_URL
        self.api_key = LLM_API_KEY
        self.model_name = LLM_MODEL
        self.timeout = LLM_TIMEOUT_SECONDS
        self.temperature = LLM_TEMPERATURE
        if not self.api_key:
            raise ValueError("DEEPSEEK_API_KEY 未在 AML Worker 环境中配置")

    def generate(self, system_prompt: str, user_prompt: str) -> dict[str, Any]:
        payload = {
            "model": self.model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self.temperature,
            "response_format": {"type": "json_object"},
            "max_tokens": LLM_MAX_TOKENS,
            "thinking": {"type": "disabled"},
        }
        request = urllib.request.Request(
            self.api_url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                response_payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:1000]
            raise RuntimeError(f"模型服务返回 HTTP {exc.code}: {detail}") from exc
        content = response_payload["choices"][0]["message"]["content"]
        return _parse_json_content(content)


def _parse_json_content(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    text = str(value or "").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE)
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("模型输出必须是 JSON 对象")
    return parsed


def execute_text_generation(payload: dict[str, Any]) -> dict[str, Any]:
    basic_info = _required_object(payload, "basicInfo")
    customers = payload.get("customers")
    transaction_features = _required_object(payload, "transactionFeatures")
    if not isinstance(customers, list):
        raise ValueError("customers 必须是数组")
    model = OpenAICompatibleJsonModel()

    with tempfile.TemporaryDirectory(prefix="aml-text-") as directory:
        root = Path(directory)
        input_path = root / "case.csv"
        with input_path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=("basic_info", "customers", "transaction_features"),
            )
            writer.writeheader()
            writer.writerow(
                {
                    "basic_info": json.dumps(basic_info, ensure_ascii=False),
                    "customers": json.dumps(customers, ensure_ascii=False),
                    "transaction_features": json.dumps(
                        transaction_features, ensure_ascii=False
                    ),
                }
            )
        case = read_cases(input_path)[0]
        knowledge_path = None
        if isinstance(payload.get("knowledgeBase"), dict):
            knowledge_path = root / "knowledge.json"
            knowledge_path.write_text(
                json.dumps(payload["knowledgeBase"], ensure_ascii=False),
                encoding="utf-8",
            )
        workflow = AnalysisWorkflow(
            model=model,
            prompts=PromptStore(),
            config=WorkflowConfig.load(),
            knowledge_base=KnowledgeBase.load(knowledge_path),
        )
        generated = workflow.run(case)
    return {
        "status": "SUCCEEDED",
        "workerId": WORKER_ID,
        "workerVersion": WORKER_VERSION,
        "algorithmId": TEXT_ALGORITHM_ID,
        "algorithmVersion": "0.1.0",
        "caseId": generated.case_id,
        "analysisText1": generated.analysis_text1,
        "analysisText2": generated.analysis_text2,
        "paragraphs": {
            key: value.to_dict() for key, value in generated.paragraphs.items()
        },
        "review": generated.review.to_dict(),
        "rewriteCounts": generated.rewrite_counts,
        "lengthWarnings": list(generated.length_warnings),
    }


def execute_similarity(payload: dict[str, Any]) -> dict[str, Any]:
    query_case = _required_object(payload, "queryCase")
    history_cases = payload.get("historyCases")
    if not isinstance(history_cases, list) or not history_cases:
        raise ValueError("historyCases 必须是非空数组")
    if not all(isinstance(item, dict) for item in history_cases):
        raise ValueError("historyCases 的每一项必须是案例对象")
    parameters = _optional_object(payload, "parameters")
    history_source_indexes: dict[str, int] = {}
    with tempfile.TemporaryDirectory(prefix="aml-similarity-") as directory:
        root = Path(directory)
        query_path = root / "query.json"
        _write_json(query_path, query_case)
        candidates = []
        for index, item in enumerate(history_cases, start=1):
            path = root / f"history-{index:04d}.json"
            _write_json(path, item)
            graph = load_case(path)
            history_source_indexes.setdefault(graph.case_id, index - 1)
            candidates.append((path, graph))
        recall_threshold = float(
            parameters.get("embeddingRecallThreshold", EMBEDDING_RECALL_THRESHOLD)
        )
        if not 0 <= recall_threshold <= 1:
            raise ValueError("embeddingRecallThreshold 必须在 0 到 1 之间")
        args = argparse.Namespace(
            retrieval_k=max(
                5, min(int(parameters.get("gedCandidateLimit", 25)), 100)
            ),
            min_shared_event_types=int(
                parameters.get("minSharedEventTypes", MIN_SHARED_EVENT_TYPES)
            ),
            min_event_overlap=float(
                parameters.get("minEventOverlap", MIN_EVENT_OVERLAP)
            ),
            min_event_count_ratio=float(
                parameters.get("minEventCountRatio", MIN_EVENT_COUNT_RATIO)
            ),
            embedding_model=EMBEDDING_MODEL,
            embedding_device=EMBEDDING_DEVICE,
            embedding_local_files_only=MODEL_LOCAL_FILES_ONLY,
            embedding_recall_threshold=recall_threshold,
            reranker_model=RERANKER_MODEL,
            reranker_device=RERANKER_DEVICE,
            reranker_local_files_only=MODEL_LOCAL_FILES_ONLY,
            reranker_max_length=int(parameters.get("rerankerMaxLength", 512)),
        )
        result = match_query(query_path, candidates, args)
    result["query_case"]["source_file"] = "request.queryCase"
    for item in result["results"]:
        source_index = history_source_indexes.get(item["case_id"], item["rank"] - 1)
        item["source_file"] = f"request.historyCases[{source_index}]"
    return {
        "status": "SUCCEEDED",
        "workerId": WORKER_ID,
        "workerVersion": WORKER_VERSION,
        "algorithmId": SIMILARITY_ALGORITHM_ID,
        "algorithmVersion": SIMILARITY_FINGERPRINT_VERSION,
        **result,
    }


def execute(request: dict[str, Any]) -> dict[str, Any]:
    action = str(request.get("action") or "").strip()
    payload = _optional_object(request, "payload")
    if action == "health":
        return {
            "status": "UP",
            "workerId": WORKER_ID,
            "workerVersion": WORKER_VERSION,
            "algorithms": [TEXT_ALGORITHM_ID, SIMILARITY_ALGORITHM_ID],
        }
    if action == "text-generation":
        return execute_text_generation(payload)
    if action == "case-similarity":
        return execute_similarity(payload)
    raise ValueError(f"不支持的 action: {action or '<empty>'}")


def _required_object(payload: dict[str, Any], name: str) -> dict[str, Any]:
    value = payload.get(name)
    if not isinstance(value, dict):
        raise ValueError(f"{name} 必须是对象")
    return value


def _optional_object(payload: dict[str, Any], name: str) -> dict[str, Any]:
    value = payload.get(name) or {}
    if not isinstance(value, dict):
        raise ValueError(f"{name} 必须是对象")
    return value


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    try:
        request = json.load(sys.stdin)
        if not isinstance(request, dict):
            raise ValueError("请求顶层必须是 JSON 对象")
        json.dump(execute(request), sys.stdout, ensure_ascii=False)
        sys.stdout.write("\n")
        return 0
    except Exception as exc:
        json.dump(
            {
                "status": "FAILED",
                "errorType": type(exc).__name__,
                "error": str(exc),
            },
            sys.stdout,
            ensure_ascii=False,
        )
        sys.stdout.write("\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
