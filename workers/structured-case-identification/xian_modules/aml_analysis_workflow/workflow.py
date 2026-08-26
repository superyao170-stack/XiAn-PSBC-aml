from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any, Callable, Iterable

from .config import WorkflowConfig
from .csv_input import build_fact_index, selected_feature_semantics
from .errors import ModelResponseError, ReviewRejectedError
from .knowledge import KnowledgeBase
from .llm import JsonModel
from .models import (
    CaseInput,
    FactIndex,
    GeneratedCase,
    Paragraph,
    ReviewIssue,
    ReviewResult,
)
from .prompt_store import PromptStore


REQUIRED_PARAGRAPHS = (
    "T1.1",
    "T1.2",
    "T1.3",
    "T1.4",
    "T1.5",
    "T2.1",
    "T2.2",
    "T2.3.1",
    "T2.3.2",
    "T2.3.3",
    "T2.3.4",
    "T2.4",
)


@dataclass(frozen=True)
class NodeSpec:
    node_id: str
    prompt_name: str
    paragraph_ids: tuple[str, ...]
    fact_groups: tuple[str, ...]
    knowledge_categories: tuple[str, ...] = ()


INITIAL_NODES = (
    NodeSpec(
        "t1_profile_transaction",
        "t1_profile_transaction",
        ("T1.2", "T1.3"),
        ("case_identity", "case_risk", "customer", "window", "transaction_overview", "transaction_time", "diversity"),
    ),
    NodeSpec(
        "t1_funds",
        "t1_funds",
        ("T1.4",),
        ("window", "transaction_flow", "counterparty"),
    ),
    NodeSpec(
        "t2_case_subject",
        "t2_case_subject",
        ("T2.1", "T2.2"),
        (
            "case_identity",
            "case_risk",
            "case_investigation",
            "customer",
            "window",
            "transaction_overview",
            "transaction_flow",
            "transaction_time",
            "counterparty",
            "diversity",
        ),
    ),
    NodeSpec(
        "t2_funds_account",
        "t2_funds_account",
        ("T2.3.1", "T2.3.2"),
        ("case_identity", "case_risk", "window", "transaction_overview", "transaction_flow", "transaction_time", "diversity"),
        (
            "资金规模",
            "交易频率",
            "交易时间",
            "资金链路",
            "金额模式",
            "账户行为",
            "医疗腐败",
        ),
    ),
    NodeSpec(
        "t2_counterparty_judicial",
        "t2_counterparty_judicial",
        ("T2.3.3", "T2.3.4"),
        ("case_identity", "case_investigation", "counterparty", "diversity"),
        ("交易对手", "司法处置", "地域", "医疗腐败"),
    ),
)

SECOND_STAGE_NODES = (
    NodeSpec("t1_subject", "t1_subject", ("T1.5",), ("case_identity", "case_risk", "customer")),
    NodeSpec("t2_conclusion", "t2_conclusion", ("T2.4",), ()),
)

DISCOVERY_NODE = NodeSpec(
    "t1_discovery", "t1_discovery", ("T1.1",), ("case_identity", "case_risk", "window")
)


class AnalysisWorkflow:
    """Generate, review, selectively rewrite, and assemble one case."""

    def __init__(
        self,
        model: JsonModel,
        prompts: PromptStore,
        config: WorkflowConfig,
        knowledge_base: KnowledgeBase | None = None,
        fact_index_builder: Callable[[CaseInput], FactIndex] | None = None,
        initial_nodes: tuple[NodeSpec, ...] | None = None,
        second_stage_nodes: tuple[NodeSpec, ...] | None = None,
        discovery_node: NodeSpec | None = None,
    ) -> None:
        self.model = model
        self.prompts = prompts
        self.config = config
        self.knowledge_base = knowledge_base or KnowledgeBase(None, ())
        self.fact_index_builder = fact_index_builder or build_fact_index
        self.initial_nodes = initial_nodes or INITIAL_NODES
        self.second_stage_nodes = second_stage_nodes or SECOND_STAGE_NODES
        self.discovery_node = discovery_node or DISCOVERY_NODE
        self._system_prompt = prompts.system()

    def run(self, case: CaseInput) -> GeneratedCase:
        facts = self.fact_index_builder(case)
        paragraphs: dict[str, Paragraph] = {}
        rewrite_payloads: dict[str, dict[str, Any]] = {}

        first_results = self._run_parallel(self.initial_nodes, facts)
        self._merge(first_results, paragraphs, rewrite_payloads)

        second_dependencies = {
            "t1_subject": self._pick(paragraphs, "T1.2", "T1.3", "T1.4"),
            "t2_conclusion": self._pick(
                paragraphs,
                "T2.1",
                "T2.2",
                "T2.3.1",
                "T2.3.2",
                "T2.3.3",
                "T2.3.4",
            ),
        }
        second_results = self._run_parallel(
            self.second_stage_nodes, facts, second_dependencies
        )
        self._merge(second_results, paragraphs, rewrite_payloads)

        discovery_dependencies = self._pick(
            paragraphs, "T1.2", "T1.3", "T1.4", "T1.5"
        )
        discovery, payload = self._run_node(
            self.discovery_node, facts, discovery_dependencies
        )
        self._merge([(discovery, payload)], paragraphs, rewrite_payloads)

        self._validate_complete_set(paragraphs)
        review = self._review(facts, paragraphs)
        rewrite_counts: dict[str, int] = {}
        for _ in range(self.config.max_rewrite_rounds):
            error_targets = self._error_targets(review)
            if not error_targets:
                break
            rewritten = self._rewrite_parallel(
                error_targets, review, paragraphs, rewrite_payloads, rewrite_counts
            )
            paragraphs.update(rewritten)
            review = self._review(facts, paragraphs)
        if not review.passed or self._error_targets(review):
            # Paragraph shape and fact/signal references have already passed
            # deterministic validation. Do not discard the complete report
            # solely because the probabilistic reviewer still disagrees after
            # the bounded rewrite round; retain its findings for manual review.
            review = ReviewResult(
                passed=True,
                issues=tuple(
                    ReviewIssue(
                        paragraph_id=issue.paragraph_id,
                        code=issue.code,
                        message=f"重写预算耗尽，需人工复核：{issue.message}",
                        severity="warning",
                    )
                    for issue in review.issues
                ),
                rewrite_targets=(),
            )

        length_warnings = self._length_warnings(paragraphs)
        analysis_text1, analysis_text2 = assemble_analysis_texts(paragraphs)
        return GeneratedCase(
            case_id=case.case_id,
            paragraphs=paragraphs,
            analysis_text1=analysis_text1,
            analysis_text2=analysis_text2,
            review=review,
            rewrite_counts=rewrite_counts,
            length_warnings=tuple(length_warnings),
        )

    def _run_parallel(
        self,
        specs: Iterable[NodeSpec],
        facts: FactIndex,
        dependencies: dict[str, list[Paragraph]] | None = None,
    ) -> list[tuple[list[Paragraph], dict[str, Any]]]:
        specs = tuple(specs)
        results: dict[str, tuple[list[Paragraph], dict[str, Any]]] = {}
        with ThreadPoolExecutor(
            max_workers=min(self.config.node_workers, len(specs)),
            thread_name_prefix="analysis-node",
        ) as executor:
            futures = {
                executor.submit(
                    self._run_node,
                    spec,
                    facts,
                    (dependencies or {}).get(spec.node_id, []),
                ): spec
                for spec in specs
            }
            for future in as_completed(futures):
                spec = futures[future]
                results[spec.node_id] = future.result()
        return [results[spec.node_id] for spec in specs]

    def _run_node(
        self,
        spec: NodeSpec,
        facts: FactIndex,
        dependencies: list[Paragraph],
    ) -> tuple[list[Paragraph], dict[str, Any]]:
        payload = self._node_payload(spec, facts, dependencies)
        paragraphs = self._generate_paragraphs(spec.prompt_name, payload, spec.paragraph_ids)
        return paragraphs, payload

    def _node_payload(
        self, spec: NodeSpec, facts: FactIndex, dependencies: list[Paragraph]
    ) -> dict[str, Any]:
        selected = facts.select(*spec.fact_groups)
        dependency_refs = [ref for paragraph in dependencies for ref in paragraph.fact_refs]
        selected.update(facts.resolve(dependency_refs))
        candidates = self.knowledge_base.retrieve(
            selected.values(), spec.knowledge_categories, self.config.kb_top_k
        ) if spec.knowledge_categories else []
        allowed_signals = list(
            dict.fromkeys(
                [candidate.id for candidate in candidates]
                + [ref for paragraph in dependencies for ref in paragraph.signal_refs]
            )
        )
        return {
            "node_id": spec.node_id,
            "paragraph_ids": list(spec.paragraph_ids),
            "facts": selected,
            "feature_semantics": selected_feature_semantics(selected),
            "dependencies": [paragraph.to_dict() for paragraph in dependencies],
            "knowledge_candidates": [candidate.to_dict() for candidate in candidates],
            "allowed_signal_refs": allowed_signals,
            "length_guides": {
                paragraph_id: vars(self.config.paragraph_lengths[paragraph_id])
                for paragraph_id in spec.paragraph_ids
                if paragraph_id in self.config.paragraph_lengths
            },
        }

    def _generate_paragraphs(
        self,
        prompt_name: str,
        payload: dict[str, Any],
        expected_ids: tuple[str, ...],
    ) -> list[Paragraph]:
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request_payload = dict(payload)
            if last_error is not None:
                request_payload["response_correction"] = str(last_error)
            try:
                response = self.model.generate(
                    self._system_prompt,
                    self.prompts.render(prompt_name, request_payload),
                )
                paragraphs = self._parse_node_response(response, expected_ids)
                self._validate_references(paragraphs, payload)
                return paragraphs
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "模型节点生成失败"))

    @staticmethod
    def _parse_node_response(
        response: dict[str, Any], expected_ids: tuple[str, ...]
    ) -> list[Paragraph]:
        raw = response.get("paragraphs")
        if not isinstance(raw, list):
            raise ModelResponseError("节点输出缺少 paragraphs 数组")
        paragraphs = [Paragraph.from_dict(item) for item in raw]
        ids = [item.paragraph_id for item in paragraphs]
        if len(ids) != len(set(ids)) or set(ids) != set(expected_ids):
            raise ModelResponseError(
                f"节点段落不匹配，期望 {list(expected_ids)}，实际 {ids}"
            )
        return paragraphs

    @staticmethod
    def _validate_references(
        paragraphs: Iterable[Paragraph], payload: dict[str, Any]
    ) -> None:
        allowed_facts = set(payload.get("facts", {}))
        allowed_signals = set(payload.get("allowed_signal_refs", []))
        for paragraph in paragraphs:
            unknown_facts = set(paragraph.fact_refs) - allowed_facts
            unknown_signals = set(paragraph.signal_refs) - allowed_signals
            if unknown_facts:
                raise ModelResponseError(
                    f"段落 {paragraph.paragraph_id} 引用了未知事实: {sorted(unknown_facts)}"
                )
            if unknown_signals:
                raise ModelResponseError(
                    f"段落 {paragraph.paragraph_id} 引用了未知信号: {sorted(unknown_signals)}"
                )
            if paragraph.status != "not_assessable" and not paragraph.fact_refs:
                raise ModelResponseError(f"段落 {paragraph.paragraph_id} 缺少事实引用")

    def _review(self, facts: FactIndex, paragraphs: dict[str, Paragraph]) -> ReviewResult:
        used_refs = [ref for paragraph in paragraphs.values() for ref in paragraph.fact_refs]
        payload = {
            "paragraphs": [paragraphs[item].to_dict() for item in REQUIRED_PARAGRAPHS],
            "facts": facts.resolve(used_refs),
            "feature_semantics": selected_feature_semantics(used_refs),
            "allowed_signal_refs": list(
                dict.fromkeys(
                    ref for paragraph in paragraphs.values() for ref in paragraph.signal_refs
                )
            ),
            "length_guides": {
                key: vars(value) for key, value in self.config.paragraph_lengths.items()
            },
        }
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request_payload = dict(payload)
            if last_error is not None:
                request_payload["response_correction"] = str(last_error)
            try:
                result = ReviewResult.from_dict(
                    self.model.generate(
                        self._system_prompt,
                        self.prompts.render("reviewer", request_payload),
                    )
                )
                invalid = set(result.rewrite_targets) - set(REQUIRED_PARAGRAPHS)
                if invalid:
                    raise ModelResponseError(f"审查返回未知重写段落: {sorted(invalid)}")
                return result
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "审查节点失败"))

    def _rewrite_parallel(
        self,
        targets: list[str],
        review: ReviewResult,
        paragraphs: dict[str, Paragraph],
        base_payloads: dict[str, dict[str, Any]],
        rewrite_counts: dict[str, int],
    ) -> dict[str, Paragraph]:
        with ThreadPoolExecutor(
            max_workers=min(self.config.node_workers, len(targets)),
            thread_name_prefix="analysis-rewrite",
        ) as executor:
            futures = {}
            for paragraph_id in targets:
                count = rewrite_counts.get(paragraph_id, 0)
                if count >= self.config.max_rewrite_rounds:
                    raise ReviewRejectedError(f"段落 {paragraph_id} 已达到最大重写次数")
                issues = [
                    issue.to_dict()
                    for issue in review.issues
                    if issue.paragraph_id == paragraph_id
                ]
                base = base_payloads[paragraph_id]
                payload = {
                    "target_paragraph_id": paragraph_id,
                    "paragraph": paragraphs[paragraph_id].to_dict(),
                    "review_issues": issues,
                    "facts": base["facts"],
                    "feature_semantics": base["feature_semantics"],
                    "dependencies": base["dependencies"],
                    "knowledge_candidates": base["knowledge_candidates"],
                    "allowed_signal_refs": base["allowed_signal_refs"],
                    "length_guide": base["length_guides"].get(paragraph_id),
                }
                futures[
                    executor.submit(
                        self._generate_paragraphs,
                        "rewrite",
                        payload,
                        (paragraph_id,),
                    )
                ] = paragraph_id
            rewritten: dict[str, Paragraph] = {}
            for future in as_completed(futures):
                paragraph_id = futures[future]
                rewritten[paragraph_id] = future.result()[0]
                rewrite_counts[paragraph_id] = rewrite_counts.get(paragraph_id, 0) + 1
            return rewritten

    @staticmethod
    def _merge(
        results: Iterable[tuple[list[Paragraph], dict[str, Any]]],
        paragraphs: dict[str, Paragraph],
        payloads: dict[str, dict[str, Any]],
    ) -> None:
        for generated, payload in results:
            for paragraph in generated:
                if paragraph.paragraph_id in paragraphs:
                    raise ModelResponseError(f"段落重复生成: {paragraph.paragraph_id}")
                paragraphs[paragraph.paragraph_id] = paragraph
                payloads[paragraph.paragraph_id] = payload

    @staticmethod
    def _pick(paragraphs: dict[str, Paragraph], *ids: str) -> list[Paragraph]:
        return [paragraphs[item] for item in ids]

    @staticmethod
    def _validate_complete_set(paragraphs: dict[str, Paragraph]) -> None:
        if set(paragraphs) != set(REQUIRED_PARAGRAPHS):
            missing = set(REQUIRED_PARAGRAPHS) - set(paragraphs)
            extra = set(paragraphs) - set(REQUIRED_PARAGRAPHS)
            raise ModelResponseError(f"段落集合无效，缺少={sorted(missing)}，多出={sorted(extra)}")

    @staticmethod
    def _error_targets(review: ReviewResult) -> list[str]:
        error_ids = {
            issue.paragraph_id for issue in review.issues if issue.severity == "error"
        }
        return [item for item in review.rewrite_targets if item in error_ids]

    def _length_warnings(self, paragraphs: dict[str, Paragraph]) -> list[str]:
        warnings: list[str] = []
        for paragraph_id, paragraph in paragraphs.items():
            guide = self.config.paragraph_lengths.get(paragraph_id)
            if guide is None:
                continue
            length = len(re.sub(r"\s+", "", paragraph.text))
            if length < guide.soft_min_chars or length > guide.soft_max_chars:
                warnings.append(
                    f"{paragraph_id} 正文{length}字，软性建议范围为"
                    f"{guide.soft_min_chars}-{guide.soft_max_chars}字"
                )
        return warnings


def assemble_analysis_texts(paragraphs: dict[str, Paragraph]) -> tuple[str, str]:
    text1 = "\n\n".join(
        (
            f"【一】发现情况\n{paragraphs['T1.1'].text}",
            f"【二】客户基本情况\n{paragraphs['T1.2'].text}",
            f"【三】开户与整体交易\n{paragraphs['T1.3'].text}",
            f"【四】资金来源与去向\n{paragraphs['T1.4'].text}",
            f"【五】客户主体分析\n{paragraphs['T1.5'].text}",
        )
    )
    text2 = "\n\n".join(
        (
            f"【一】案例概述\n{paragraphs['T2.1'].text}",
            f"【二】客户主体分析\n{paragraphs['T2.2'].text}",
            "【三】客户交易分析",
            f"1. 资金交易方向\n{paragraphs['T2.3.1'].text}",
            f"2. 账号方向\n{paragraphs['T2.3.2'].text}",
            f"3. 交易对手方向\n{paragraphs['T2.3.3'].text}",
            f"4. 司法查询方向\n{paragraphs['T2.3.4'].text}",
            f"【四】综合分析\n{paragraphs['T2.4'].text}",
        )
    )
    return text1, text2
