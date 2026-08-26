"""Evidence-governed analysis generation adapted from aml-analysis-workflow."""

from __future__ import annotations

import hashlib
import json
import re
import threading
from pathlib import Path
from time import perf_counter
from typing import Any, Callable, Dict

from aml_analysis_workflow.config import WorkflowConfig
from aml_analysis_workflow.csv_input import _validate_customers, _validate_features
from aml_analysis_workflow.errors import ModelResponseError
from aml_analysis_workflow.knowledge import KnowledgeBase
from aml_analysis_workflow.models import CaseInput, GeneratedCase
from aml_analysis_workflow.prompt_store import PromptStore
from aml_analysis_workflow.workflow import AnalysisWorkflow, REQUIRED_PARAGRAPHS
from datagraph_bank.analysis_generation import AnalysisGenerationError
from datagraph_bank.llm import DeepSeekLLMClient


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ALGORITHM_NAME = "aml-analysis-workflow"
ALGORITHM_VERSION = "0.1.0-integrated"
FORBIDDEN_REPORT_MARKERS = (
    "模拟",
    "演示",
    "虚构",
    "样例",
    "示例",
    "不可直接使用",
    "不对应真实主体",
    "不对应任何真实",
)


class DeepSeekGovernedJsonModel:
    """Expose the current project's DeepSeek client as the workflow JSON model."""

    def __init__(self, llm_client: DeepSeekLLMClient) -> None:
        self.llm_client = llm_client
        self.model_name = str(llm_client.chat_model or "deepseek")
        self._trace_lock = threading.Lock()
        self._trace_sequence = 0
        self._traces: list[Dict[str, Any]] = []

    def generate(self, system_prompt: str, user_prompt: str) -> Dict[str, Any]:
        started = perf_counter()
        case_match = re.search(
            r'"basic_info\.case_id":"([^"]+)"',
            user_prompt,
        )
        node_match = re.search(r'"node_id":"([^"]+)"', user_prompt)
        response = self.llm_client.chat_json(
            user_prompt,
            system_prompt=system_prompt,
        )
        payload = response.get("payload")
        trace = {
            "case_id": case_match.group(1) if case_match else "",
            "node_id": node_match.group(1) if node_match else "review_or_rewrite",
            "duration_seconds": round(perf_counter() - started, 6),
            "available": bool(response.get("available")),
            "payload_received": isinstance(payload, dict),
            "reason": str(response.get("reason") or ""),
        }
        with self._trace_lock:
            self._trace_sequence += 1
            self._traces.append({"sequence": self._trace_sequence, **trace})
        if not isinstance(payload, dict):
            reason = str(response.get("reason") or "大模型未返回合法 JSON 对象")
            raise ModelResponseError(reason)
        return payload

    def traces_for_case(self, case_id: str) -> list[Dict[str, Any]]:
        with self._trace_lock:
            return [
                dict(item)
                for item in self._traces
                if item.get("case_id") == case_id
            ]


class GovernedAnalysisTextGenerator:
    """Run segmented generation, review, selective rewrite, and assembly."""

    require_transaction_features = True

    def __init__(
        self,
        llm_client: DeepSeekLLMClient,
        *,
        knowledge_base_path: Path | None = None,
        knowledge_base_backend: str | None = None,
        qdrant_url: str | None = None,
        qdrant_path: Path | str | None = None,
        qdrant_collection: str | None = None,
        bge_model: str | None = None,
        config_path: Path | None = None,
        prompts_dir: Path | None = None,
        model: Any | None = None,
        workflow_factory: Callable[..., AnalysisWorkflow] | None = None,
    ) -> None:
        self.llm_client = llm_client
        self.knowledge_base_path = Path(
            knowledge_base_path
            or PROJECT_ROOT / "data" / "kb" / "risk_event_knowledge_base.json"
        )
        self.knowledge_base_backend = knowledge_base_backend
        self.qdrant_url = qdrant_url
        self.qdrant_path = Path(qdrant_path) if qdrant_path is not None else None
        self.qdrant_collection = qdrant_collection
        self.bge_model = bge_model
        self.config = WorkflowConfig.load(config_path)
        self.prompts = PromptStore(prompts_dir)
        self.model = model or DeepSeekGovernedJsonModel(llm_client)
        self.workflow = (workflow_factory or AnalysisWorkflow)(
            model=self.model,
            prompts=self.prompts,
            config=self.config,
            knowledge_base=KnowledgeBase.load(
                self.knowledge_base_path,
                backend=self.knowledge_base_backend,
                qdrant_url=self.qdrant_url,
                qdrant_path=self.qdrant_path,
                qdrant_collection=self.qdrant_collection,
                bge_model=self.bge_model,
            ),
        )
        self.prompt_template = self._prompt_fingerprint_material()

    def generate(
        self,
        case_information: Dict[str, Any],
        transaction_features: Any = None,
    ) -> Dict[str, str]:
        generated, _ = self.generate_artifact(case_information, transaction_features)
        return {
            "analysis_text1": generated.analysis_text1,
            "analysis_text2": generated.analysis_text2,
        }

    def generate_artifact(
        self,
        case_information: Dict[str, Any],
        transaction_features: Any,
    ) -> tuple[GeneratedCase, Dict[str, Any]]:
        if not getattr(self.llm_client, "chat_available", False) and isinstance(
            self.model,
            DeepSeekGovernedJsonModel,
        ):
            status = self.llm_client.status()
            reason = status.get("init_error") or "大模型不可用"
            raise AnalysisGenerationError(f"无法生成分析文本: {reason}")

        case = self._case_input(case_information, transaction_features)
        generated = self.workflow.run(case)
        self._validate_report_style(generated, case)
        return generated, self._audit(case, generated)

    @staticmethod
    def _validate_report_style(generated: GeneratedCase, case: CaseInput) -> None:
        source_text = json.dumps(
            {
                "basic_info": case.basic_info,
                "customers": case.customers,
                "transaction_features": case.raw_transaction_features
                or case.transaction_features,
            },
            ensure_ascii=False,
        ).lower()
        source_markers = {
            marker for marker in FORBIDDEN_REPORT_MARKERS if marker in source_text
        }
        for text_name, text_value in (
            ("analysis_text1", generated.analysis_text1),
            ("analysis_text2", generated.analysis_text2),
        ):
            normalized = text_value.lower()
            matched = [
                marker
                for marker in FORBIDDEN_REPORT_MARKERS
                if marker in normalized and marker not in source_markers
            ]
            if matched:
                raise ModelResponseError(
                    f"{text_name} 含有非正式场景表述: {', '.join(matched)}"
                )

    def _case_input(
        self,
        case_information: Dict[str, Any],
        transaction_features: Any,
    ) -> CaseInput:
        if not isinstance(case_information, dict):
            raise ValueError("案例信息必须是 JSON 对象")
        basic_info = case_information.get("basic_info")
        customers = case_information.get("customers")
        if not isinstance(basic_info, dict):
            raise ValueError("basic_info 必须是 JSON 对象")
        if not isinstance(customers, list):
            raise ValueError("customers 必须是 JSON 数组")
        if not isinstance(transaction_features, dict):
            raise ValueError("新增案例必须提供 transaction_features JSON 对象")

        case_id = str(basic_info.get("case_id") or "").strip()
        if not case_id:
            raise ValueError("basic_info.case_id 不能为空")
        source = f"<generated-case:{case_id}>"
        feature_case_id = str(transaction_features.get("case_id") or "").strip()
        if feature_case_id and feature_case_id != case_id:
            raise ValueError(
                "transaction_features.case_id 与 basic_info.case_id 不一致: "
                f"{feature_case_id} != {case_id}"
            )
        customer_ids = _validate_customers(customers, source)
        features, warnings = _validate_features(
            transaction_features,
            customer_ids,
            source,
        )
        return CaseInput(
            csv_path=Path(source),
            row_number=1,
            case_id=case_id,
            basic_info=basic_info,
            customers=customers,
            transaction_features=features,
            warnings=tuple(warnings),
        )

    def _audit(self, case: CaseInput, generated: GeneratedCase) -> Dict[str, Any]:
        audit = {
            "algorithm": ALGORITHM_NAME,
            "algorithm_version": ALGORITHM_VERSION,
            "case_id": case.case_id,
            "model": self.model.model_name,
            "knowledge_base_version": self.workflow.knowledge_base.version,
            "input_warnings": list(case.warnings),
            "paragraphs": {
                paragraph_id: {
                    "status": generated.paragraphs[paragraph_id].status,
                    "fact_refs": list(generated.paragraphs[paragraph_id].fact_refs),
                    "signal_refs": list(generated.paragraphs[paragraph_id].signal_refs),
                    "missing_inputs": list(
                        generated.paragraphs[paragraph_id].missing_inputs
                    ),
                    "rewrite_count": generated.rewrite_counts.get(paragraph_id, 0),
                }
                for paragraph_id in REQUIRED_PARAGRAPHS
            },
            "review": generated.review.to_dict(),
            "length_warnings": list(generated.length_warnings),
            "data_flow": {
                "analysis_input_used": False,
                "transaction_features_used": True,
                "generated_text_for_framework_extraction": True,
            },
        }
        traces_for_case = getattr(self.model, "traces_for_case", None)
        if callable(traces_for_case):
            audit["model_call_trace"] = traces_for_case(case.case_id)
        return audit

    def generation_trace(self, case_id: str) -> list[Dict[str, Any]]:
        traces_for_case = getattr(self.model, "traces_for_case", None)
        if not callable(traces_for_case):
            return []
        return traces_for_case(case_id)

    def _prompt_fingerprint_material(self) -> str:
        parts = [ALGORITHM_VERSION, repr(self.config)]
        for name in (
            "system",
            "t1_profile_transaction",
            "t1_funds",
            "t2_case_subject",
            "t2_funds_account",
            "t2_counterparty_judicial",
            "t1_subject",
            "t2_conclusion",
            "t1_discovery",
            "reviewer",
            "rewrite",
        ):
            parts.append(self.prompts.raw(name))
        parts.append(
            str(self.workflow.knowledge_base.version or "")
            + ":"
            + str(self.knowledge_base_backend or "auto")
        )
        if self.knowledge_base_path.is_file() and (
            self.workflow.knowledge_base.repository is None
            or getattr(self.workflow.knowledge_base.repository, "backend", "json")
            == "json"
        ):
            parts.append(
                hashlib.sha256(self.knowledge_base_path.read_bytes()).hexdigest()
            )
        return "\n".join(parts)
