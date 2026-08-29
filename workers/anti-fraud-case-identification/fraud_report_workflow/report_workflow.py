from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any, Iterable

from .config import WorkflowConfig
from .errors import ModelResponseError, ReportReviewRejectedError
from .facts import MOBILE_RISK_TYPES, TRANSACTION_RISK_TYPES, build_fact_index
from .llm import JsonModel
from .models import CaseInput, FactIndex, GeneratedReport, Paragraph, ReportReviewResult, RiskEvent
from .prompt_store import PromptStore


PARAGRAPH_IDS = ("SUMMARY", "CRIME_CHAIN", "ACCOUNT_PROFILE", "TRANSACTION", "MOBILE")


@dataclass(frozen=True)
class NodeSpec:
    node_id: str
    prompt_name: str
    paragraph_id: str
    fact_groups: tuple[str, ...]
    risk_scope: str


INITIAL_NODES = (
    NodeSpec("crime_chain", "report_crime_chain", "CRIME_CHAIN", ("case", "accounts", "customers", "devices", "risk_all"), "all"),
    NodeSpec("account_profile", "report_account_profile", "ACCOUNT_PROFILE", ("case", "accounts", "customers"), "none"),
    NodeSpec("transaction", "report_transaction", "TRANSACTION", ("case", "accounts", "customers", "risk_transaction"), "transaction"),
    NodeSpec("mobile", "report_mobile", "MOBILE", ("case", "devices", "risk_mobile"), "mobile"),
)


class FraudReportWorkflow:
    """Generate, review, selectively rewrite, and assemble the five-section report."""

    def __init__(self, model: JsonModel, prompts: PromptStore, config: WorkflowConfig) -> None:
        self.model = model
        self.prompts = prompts
        self.config = config
        self._system_prompt = prompts.system()

    def run(self, case: CaseInput, risk_events: tuple[RiskEvent, ...]) -> GeneratedReport:
        facts = build_fact_index(case, risk_events)
        paragraphs: dict[str, Paragraph] = {}
        base_payloads: dict[str, dict[str, Any]] = {}
        results = self._run_parallel(INITIAL_NODES, facts, risk_events)
        self._merge(results, paragraphs, base_payloads)

        summary_spec = NodeSpec("summary", "report_summary", "SUMMARY", ("case", "risk_all"), "all")
        summary_dependencies = [paragraphs[item] for item in ("CRIME_CHAIN", "ACCOUNT_PROFILE", "TRANSACTION", "MOBILE")]
        summary_result = self._run_node(summary_spec, facts, risk_events, summary_dependencies)
        self._merge([summary_result], paragraphs, base_payloads)
        self._validate_complete(paragraphs)

        review = self._review(facts, risk_events, paragraphs)
        rewrite_counts: dict[str, int] = {}
        for _ in range(self.config.max_rewrite_rounds):
            targets = [item for item in review.rewrite_targets if any(issue.paragraph_id == item and issue.severity == "error" for issue in review.issues)]
            if not targets:
                break
            rewritten = self._rewrite(targets, review, paragraphs, base_payloads, rewrite_counts)
            paragraphs.update(rewritten)
            review = self._review(facts, risk_events, paragraphs)
        if not review.passed:
            details = "; ".join(item.message for item in review.issues if item.severity == "error")
            raise ReportReviewRejectedError(f"案例{case.case_id}报告审查未通过: {details or '未给出原因'}")
        warnings = tuple(self._length_warnings(paragraphs))
        return GeneratedReport(assemble_report(paragraphs), paragraphs, review, rewrite_counts, warnings)

    def _run_parallel(self, specs: tuple[NodeSpec, ...], facts: FactIndex, risk_events: tuple[RiskEvent, ...]) -> list[tuple[Paragraph, dict[str, Any]]]:
        results: dict[str, tuple[Paragraph, dict[str, Any]]] = {}
        with ThreadPoolExecutor(max_workers=min(self.config.node_workers, len(specs)), thread_name_prefix="fraud-report-node") as executor:
            futures = {executor.submit(self._run_node, spec, facts, risk_events, []): spec for spec in specs}
            for future in as_completed(futures):
                spec = futures[future]
                results[spec.node_id] = future.result()
        return [results[item.node_id] for item in specs]

    def _run_node(self, spec: NodeSpec, facts: FactIndex, risk_events: tuple[RiskEvent, ...], dependencies: list[Paragraph]) -> tuple[Paragraph, dict[str, Any]]:
        scoped_events = self._scope_risks(risk_events, spec.risk_scope)
        selected_facts = facts.select(*spec.fact_groups)
        dependency_fact_refs = tuple(
            dict.fromkeys(ref for paragraph in dependencies for ref in paragraph.fact_refs)
        )
        selected_facts.update(facts.resolve(dependency_fact_refs))
        payload = {
            "task": "report_section",
            "node_id": spec.node_id,
            "paragraph_id": spec.paragraph_id,
            "facts": selected_facts,
            "risk_events": [item.to_dict() for item in scoped_events],
            "allowed_risk_event_refs": [item.risk_event_id for item in scoped_events],
            "dependencies": [item.to_dict() for item in dependencies],
            "length_guide": vars(self.config.paragraph_lengths[spec.paragraph_id]),
        }
        return self._generate_paragraph(spec.prompt_name, payload, spec.paragraph_id), payload

    def _generate_paragraph(self, prompt_name: str, payload: dict[str, Any], expected_id: str) -> Paragraph:
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request = dict(payload)
            if last_error is not None:
                request["response_correction"] = str(last_error)
            try:
                response = self.model.generate(self._system_prompt, self.prompts.render(prompt_name, request))
                raw = response.get("paragraphs")
                if not isinstance(raw, list) or len(raw) != 1:
                    raise ModelResponseError("报告节点必须返回一个paragraphs元素")
                paragraph = Paragraph.from_dict(raw[0])
                if paragraph.paragraph_id != expected_id:
                    raise ModelResponseError(f"报告节点期望{expected_id}，实际{paragraph.paragraph_id}")
                self._validate_paragraph_refs(paragraph, payload)
                return paragraph
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "报告段落生成失败"))

    @staticmethod
    def _validate_paragraph_refs(paragraph: Paragraph, payload: dict[str, Any]) -> None:
        unknown_facts = set(paragraph.fact_refs) - set(payload.get("facts", {}))
        unknown_risks = set(paragraph.risk_event_refs) - set(payload.get("allowed_risk_event_refs", []))
        if unknown_facts:
            raise ModelResponseError(f"段落{paragraph.paragraph_id}引用未知事实: {sorted(unknown_facts)}")
        if unknown_risks:
            raise ModelResponseError(f"段落{paragraph.paragraph_id}引用未知风险事件: {sorted(unknown_risks)}")
        if paragraph.status != "not_assessable" and not paragraph.fact_refs and not paragraph.risk_event_refs:
            raise ModelResponseError(f"段落{paragraph.paragraph_id}缺少事实或风险事件引用")

    def _review(self, facts: FactIndex, risk_events: tuple[RiskEvent, ...], paragraphs: dict[str, Paragraph]) -> ReportReviewResult:
        used_facts = tuple(dict.fromkeys(ref for paragraph in paragraphs.values() for ref in paragraph.fact_refs))
        used_risks = tuple(dict.fromkeys(ref for paragraph in paragraphs.values() for ref in paragraph.risk_event_refs))
        risk_map = {item.risk_event_id: item for item in risk_events}
        payload = {
            "task": "report_review",
            "paragraphs": [paragraphs[item].to_dict() for item in PARAGRAPH_IDS],
            "facts": facts.resolve(used_facts),
            "risk_events": [risk_map[item].to_dict() for item in used_risks if item in risk_map],
            "allowed_risk_event_refs": list(used_risks),
        }
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request = dict(payload)
            if last_error is not None:
                request["response_correction"] = str(last_error)
            try:
                result = ReportReviewResult.from_dict(self.model.generate(self._system_prompt, self.prompts.render("report_reviewer", request)))
                if set(result.rewrite_targets) - set(PARAGRAPH_IDS):
                    raise ModelResponseError("报告审查返回未知重写段落")
                return result
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "报告审查失败"))

    def _rewrite(self, targets: list[str], review: ReportReviewResult, paragraphs: dict[str, Paragraph], base_payloads: dict[str, dict[str, Any]], counts: dict[str, int]) -> dict[str, Paragraph]:
        rewritten: dict[str, Paragraph] = {}
        with ThreadPoolExecutor(max_workers=min(self.config.node_workers, len(targets)), thread_name_prefix="fraud-report-rewrite") as executor:
            futures = {}
            for paragraph_id in targets:
                if counts.get(paragraph_id, 0) >= self.config.max_rewrite_rounds:
                    raise ReportReviewRejectedError(f"段落{paragraph_id}已达到最大重写次数")
                base = base_payloads[paragraph_id]
                payload = {
                    "task": "report_rewrite",
                    "target_paragraph_id": paragraph_id,
                    "paragraph": paragraphs[paragraph_id].to_dict(),
                    "review_issues": [item.to_dict() for item in review.issues if item.paragraph_id == paragraph_id],
                    "facts": base["facts"],
                    "risk_events": base["risk_events"],
                    "allowed_risk_event_refs": base["allowed_risk_event_refs"],
                    "dependencies": base["dependencies"],
                    "length_guide": base["length_guide"],
                }
                futures[executor.submit(self._generate_paragraph, "report_rewrite", payload, paragraph_id)] = paragraph_id
            for future in as_completed(futures):
                paragraph_id = futures[future]
                rewritten[paragraph_id] = future.result()
                counts[paragraph_id] = counts.get(paragraph_id, 0) + 1
        return rewritten

    @staticmethod
    def _scope_risks(events: tuple[RiskEvent, ...], scope: str) -> tuple[RiskEvent, ...]:
        if scope == "none":
            return ()
        if scope == "transaction":
            return tuple(item for item in events if item.risk_type in TRANSACTION_RISK_TYPES)
        if scope == "mobile":
            return tuple(item for item in events if item.risk_type in MOBILE_RISK_TYPES)
        return events

    @staticmethod
    def _merge(results: Iterable[tuple[Paragraph, dict[str, Any]]], paragraphs: dict[str, Paragraph], payloads: dict[str, dict[str, Any]]) -> None:
        for paragraph, payload in results:
            if paragraph.paragraph_id in paragraphs:
                raise ModelResponseError(f"段落{paragraph.paragraph_id}重复生成")
            paragraphs[paragraph.paragraph_id] = paragraph
            payloads[paragraph.paragraph_id] = payload

    @staticmethod
    def _validate_complete(paragraphs: dict[str, Paragraph]) -> None:
        if set(paragraphs) != set(PARAGRAPH_IDS):
            raise ModelResponseError("最终报告段落集合不完整")

    def _length_warnings(self, paragraphs: dict[str, Paragraph]) -> list[str]:
        warnings: list[str] = []
        for paragraph_id, paragraph in paragraphs.items():
            guide = self.config.paragraph_lengths[paragraph_id]
            length = len(re.sub(r"\s+", "", paragraph.text))
            if length < guide.soft_min_chars or length > guide.soft_max_chars:
                warnings.append(f"{paragraph_id}正文{length}字，软性建议范围为{guide.soft_min_chars}-{guide.soft_max_chars}字")
        return warnings


def assemble_report(paragraphs: dict[str, Paragraph]) -> str:
    return "\n".join((
        "风险点总结：", paragraphs["SUMMARY"].text,
        "一、犯罪链路分析", paragraphs["CRIME_CHAIN"].text,
        "二、账户基础画像", paragraphs["ACCOUNT_PROFILE"].text,
        "三、交易流水可疑特征", paragraphs["TRANSACTION"].text,
        "四、手机银行操作日志情况分析", paragraphs["MOBILE"].text,
    ))
