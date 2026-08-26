#!/usr/bin/env python3
"""Adapter for the Xi'an governed analysis-text workflow.

The generation algorithm lives unchanged under ``xian_modules``. This worker
only detects existing analysis fields, normalizes their shape, and exposes the
original generator through a JSON-over-stdio contract.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


WORKER_ROOT = Path(__file__).resolve().parent
XIAN_MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(XIAN_MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(XIAN_MODULE_ROOT))

from datagraph_bank.governed_analysis import (  # noqa: E402
    ALGORITHM_NAME,
    ALGORITHM_VERSION,
    GovernedAnalysisTextGenerator,
)
from datagraph_bank.llm import DeepSeekLLMClient  # noqa: E402


ANALYSIS_FIELDS = (
    "analysis_text",
    "analysis_texts",
    "analysisText",
    "analysisTexts",
)


def _object(record: dict[str, Any], *names: str) -> dict[str, Any]:
    for name in names:
        value = record.get(name)
        if isinstance(value, dict):
            return dict(value)
    return {}


def _list(record: dict[str, Any], *names: str) -> list[Any]:
    for name in names:
        value = record.get(name)
        if isinstance(value, list):
            return list(value)
    return []


def _normalize_analysis_value(value: Any) -> dict[str, str]:
    if isinstance(value, str):
        text = value.strip()
        return {"analysis_text1": text} if text else {}
    if isinstance(value, dict):
        normalized = {
            str(key): item.strip()
            for key, item in value.items()
            if isinstance(key, str) and isinstance(item, str) and item.strip()
        }
        if normalized:
            return normalized
    if isinstance(value, list):
        values = [str(item).strip() for item in value if str(item).strip()]
        return {
            f"analysis_text{index}": text
            for index, text in enumerate(values, start=1)
        }
    return {}


def existing_analysis_texts(record: dict[str, Any]) -> dict[str, str]:
    """Return supplied analysis text, prioritizing the requested singular field."""
    for field in ANALYSIS_FIELDS:
        normalized = _normalize_analysis_value(record.get(field))
        if normalized:
            return normalized
    return {}


def existing_analysis_text(record: dict[str, Any]) -> str:
    return "\n\n".join(existing_analysis_texts(record).values())


def _case_information(record: dict[str, Any]) -> dict[str, Any]:
    basic_info = _object(record, "basic_info", "basicInfo")
    customers = _list(record, "customers", "customer_info", "customerInfo")
    return {"basic_info": basic_info, "customers": customers}


def _transaction_features(record: dict[str, Any]) -> dict[str, Any]:
    supplied = _object(
        record,
        "transaction_features",
        "transactionFeatures",
        "feature_analysis",
        "featureAnalysis",
        "transactions",
    )
    if supplied:
        return supplied

    # The new single-case contract only requires basic_info + customers.  The
    # unchanged Xi'an generator requires one feature row for every customer,
    # so the adapter explicitly represents the absent transaction source as
    # "no transaction record" instead of inventing transaction statistics.
    customers = _list(record, "customers", "customer_info", "customerInfo")
    return {
        "customer_transaction_features": [
            {
                "customer_id": str(customer.get("entity_id") or "").strip(),
                "has_transaction_flag": 0,
            }
            for customer in customers
            if isinstance(customer, dict)
            and str(customer.get("entity_id") or "").strip()
        ]
    }


def generate_analysis_text(record: dict[str, Any]) -> dict[str, Any]:
    """Call the existing Xi'an aml_analysis_workflow generator."""
    supplied_features = bool(
        _object(
            record,
            "transaction_features",
            "transactionFeatures",
            "feature_analysis",
            "featureAnalysis",
            "transactions",
        )
    )
    client = DeepSeekLLMClient(enabled=True)
    try:
        generator = GovernedAnalysisTextGenerator(
            llm_client=client,
            knowledge_base_path=XIAN_MODULE_ROOT
            / "data"
            / "kb"
            / "risk_event_knowledge_base.json",
        )
        generated, audit = generator.generate_artifact(
            _case_information(record),
            _transaction_features(record),
        )
    finally:
        client.close()

    analysis_texts = {
        "analysis_text1": generated.analysis_text1,
        "analysis_text2": generated.analysis_text2,
    }
    return {
        "analysisTexts": analysis_texts,
        "analysisText": "\n\n".join(analysis_texts.values()),
        "source": "GENERATED_ANALYSIS_TEXT",
        "generated": True,
        "paragraphs": {
            key: paragraph.to_dict()
            for key, paragraph in generated.paragraphs.items()
        },
        "review": generated.review.to_dict(),
        "rewriteCounts": dict(generated.rewrite_counts),
        "lengthWarnings": list(generated.length_warnings),
        "audit": audit,
        "transactionFeatureSource": (
            "SUPPLIED" if supplied_features else "SYNTHESIZED_NO_TRANSACTION_RECORDS"
        ),
        "algorithm": ALGORITHM_NAME,
        "algorithmVersion": ALGORITHM_VERSION,
    }


def ensure_analysis_text(record: dict[str, Any]) -> dict[str, Any]:
    """Reuse an existing analysis field, otherwise run the original generator."""
    current = existing_analysis_texts(record)
    if current:
        return {
            "analysisTexts": current,
            "analysisText": "\n\n".join(current.values()),
            "source": "EXISTING_ANALYSIS_TEXT",
            "generated": False,
            "algorithm": "existing-input",
            "algorithmVersion": "source-data",
        }
    return generate_analysis_text(record)


def main() -> int:
    try:
        request = json.load(sys.stdin)
        record = request.get("record") if isinstance(request, dict) else None
        if not isinstance(record, dict):
            raise ValueError("请求必须包含 record JSON 对象")
        json.dump(ensure_analysis_text(record), sys.stdout, ensure_ascii=False)
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
