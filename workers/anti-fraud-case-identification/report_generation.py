"""Fraud-specific adapter around the retained aml-analysis-workflow."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any


WORKER_ROOT = Path(__file__).resolve().parent
SHARED_WORKER = WORKER_ROOT.parent / "structured-case-identification"
XIAN_MODULE_ROOT = SHARED_WORKER / "xian_modules"
RISK_KB_PATH = WORKER_ROOT / "data" / "kb" / "fraud_risk_event_knowledge_base.json"
RISK_KB_COLLECTION = os.environ.get(
    "ANTI_FRAUD_QDRANT_COLLECTION", "fraud_risk_event_knowledge_base"
)
if str(XIAN_MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(XIAN_MODULE_ROOT))

from datagraph_bank.governed_analysis import (  # noqa: E402
    DeepSeekGovernedJsonModel,
    GovernedAnalysisTextGenerator,
)
from datagraph_bank.llm import DeepSeekLLMClient  # noqa: E402
from fraud_analysis_workflow import FraudAnalysisWorkflow  # noqa: E402


class FraudGovernedJsonModel(DeepSeekGovernedJsonModel):
    """Keep model-produced references inside the current node's fact boundary.

    Some compatible chat models return human-friendly placeholders such as
    ``fact_1`` even after a correction retry. The AML workflow correctly
    rejects those values. This adapter does not relax validation: it discards
    unknown references and rebinds a paragraph only to facts whose literal
    values are actually present in its text. If no value can be bound, the
    original response is left untouched so the workflow still fails closed.
    """

    def generate(self, system_prompt: str, user_prompt: str) -> dict[str, Any]:
        response = super().generate(system_prompt, user_prompt)
        payload = self._payload(user_prompt)
        facts = payload.get("facts") if isinstance(payload.get("facts"), dict) else {}
        allowed_signals = {
            str(value) for value in payload.get("allowed_signal_refs") or []
        }
        paragraphs = response.get("paragraphs")
        if not isinstance(paragraphs, list) or not facts:
            return response
        allowed_facts = set(facts)
        for paragraph in paragraphs:
            if not isinstance(paragraph, dict):
                continue
            refs = [str(value) for value in paragraph.get("fact_refs") or []]
            if refs and set(refs) <= allowed_facts:
                continue
            text = re.sub(r"\s+", "", str(paragraph.get("text") or ""))
            rebound = [
                key
                for key, value in facts.items()
                if cls_fact_value(value) and cls_fact_value(value) in text
            ]
            if rebound:
                paragraph["fact_refs"] = rebound
            paragraph["signal_refs"] = [
                str(value)
                for value in paragraph.get("signal_refs") or []
                if str(value) in allowed_signals
            ]
        return response

    @staticmethod
    def _payload(user_prompt: str) -> dict[str, Any]:
        candidate = user_prompt.rsplit("\n", 1)[-1].strip()
        try:
            value = json.loads(candidate)
        except json.JSONDecodeError:
            return {}
        return value if isinstance(value, dict) else {}


def cls_fact_value(value: Any) -> str:
    """Return a literal fact token suitable for conservative text rebinding."""
    if isinstance(value, bool) or value is None:
        return ""
    text = re.sub(r"\s+", "", str(value)).strip()
    return text if len(text) >= 2 else ""


def existing_analysis(record: dict[str, Any]) -> dict[str, Any] | None:
    value = record.get("text_analysis")
    if isinstance(value, dict):
        text = str(value.get("text") or value.get("analysis_text") or "").strip()
        if text:
            return {
                "analysisTexts": {"analysis_text1": text},
                "analysisText": text,
                "generated": False,
                "source": "HISTORICAL_TEXT_ANALYSIS",
                "algorithm": "existing-input",
                "algorithmVersion": "source-data",
            }
    return None


def generate_analysis(record: dict[str, Any]) -> dict[str, Any]:
    current = existing_analysis(record)
    if current is not None:
        return current
    basic = dict(record["basic_info"])
    basic["risk_event_chain"] = record.get("risk_event_chain") or []
    basic["account_facts"] = record.get("accounts") or []
    basic["device_facts"] = record.get("devices") or []
    features = {
        "customer_transaction_features": [
            {"customer_id": str(customer["entity_id"]), "has_transaction_flag": 0}
            for customer in record["customers"]
        ]
    }
    client = DeepSeekLLMClient(enabled=True)
    try:
        model = FraudGovernedJsonModel(client)
        generator = GovernedAnalysisTextGenerator(
            llm_client=client,
            knowledge_base_path=RISK_KB_PATH,
            qdrant_collection=RISK_KB_COLLECTION,
            prompts_dir=WORKER_ROOT / "data" / "analysis_prompts",
            model=model,
            workflow_factory=FraudAnalysisWorkflow,
        )
        generated, audit = generator.generate_artifact(
            {"basic_info": basic, "customers": record["customers"]}, features
        )
    finally:
        client.close()
    texts = {
        "analysis_text1": generated.analysis_text1,
        "analysis_text2": generated.analysis_text2,
    }
    return {
        "analysisTexts": texts,
        "analysisText": "\n\n".join(texts.values()),
        "generated": True,
        "source": "RISK_EVENT_CHAIN_GENERATED",
        "algorithm": "aml-analysis-workflow/anti-fraud",
        "algorithmVersion": "1.0.0",
        "review": generated.review.to_dict(),
        "audit": audit,
    }
