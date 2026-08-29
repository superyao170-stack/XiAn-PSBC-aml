"""Adapter for the dedicated fraud risk-chain and report workflow."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from fraud_report_workflow.case_input import read_case
from fraud_report_workflow.config import WorkflowConfig
from fraud_report_workflow.llm import DeepSeekModel
from fraud_report_workflow.prompt_store import PromptStore
from fraud_report_workflow.report_workflow import FraudReportWorkflow
from fraud_report_workflow.risk_extraction import RiskExtractor
from fraud_report_workflow.rules import FraudRuleLibrary


WORKFLOW_VERSION = "fraud-analysis-workflow/0.1.0"


def generate_fraud_report(source: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Generate one reviewed risk chain and the fixed five-section report."""
    case = read_case(source)
    config = WorkflowConfig.load()
    prompts = PromptStore()
    model = DeepSeekModel()
    extractor = RiskExtractor(model, prompts, config, FraudRuleLibrary.load())
    risk = extractor.run(case)
    report = FraudReportWorkflow(model, prompts, config).run(case, risk.events)
    risk_events = [item.to_dict() for item in risk.events]
    analysis = {
        "analysisTexts": {"fraud_analysis_report": report.text},
        "analysisText": report.text,
        # Keep the reviewed intermediate result inside the report as well as at
        # the worker-result top level.  Older running backends persist only the
        # suspiciousReport object; embedding it here keeps the risk-chain tab
        # compatible without requiring an immediate backend restart.
        "riskEventChain": risk_events,
        "generated": True,
        "source": "REVIEWED_RISK_EVENT_CHAIN_GENERATED",
        "algorithm": "fraud-analysis-workflow",
        "algorithmVersion": WORKFLOW_VERSION,
        "riskReview": risk.review.to_dict(),
        "reportReview": report.review.to_dict(),
        "riskRevisionCount": risk.revision_count,
        "rewriteCounts": report.rewrite_counts,
        "ruleCandidateIds": list(risk.rule_candidate_ids),
    }
    return analysis, risk_events
