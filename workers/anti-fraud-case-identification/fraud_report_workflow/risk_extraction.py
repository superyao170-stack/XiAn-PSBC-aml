from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from .config import WorkflowConfig
from .derived import derive_event_observations
from .errors import ModelResponseError, RiskExtractionRejectedError
from .llm import JsonModel
from .models import CaseInput, RiskEvent, RiskExtractionResult, RiskReviewResult
from .prompt_store import PromptStore
from .rules import FraudRuleLibrary


class RiskExtractor:
    """Turn a complete raw subject-account event chain into reviewed risk episodes."""

    def __init__(
        self,
        model: JsonModel,
        prompts: PromptStore,
        config: WorkflowConfig,
        rule_library: FraudRuleLibrary,
    ) -> None:
        self.model = model
        self.prompts = prompts
        self.config = config
        self.rule_library = rule_library
        self._system_prompt = prompts.system()

    def run(self, case: CaseInput) -> RiskExtractionResult:
        derived = derive_event_observations(case)
        candidates = self.rule_library.retrieve(
            [case.basic_info, *[event.content for event in case.events], derived],
            self.config.rule_top_k,
        )
        base_payload: dict[str, Any] = {
            "task": "risk_extract",
            "case_id": case.case_id,
            "raw_events": [item.to_dict() for item in case.events],
            "derived_observations": derived,
            "risk_rule_candidates": [item.to_dict() for item in candidates],
            "allowed_risk_types": list(self.config.allowed_risk_types),
            "rule_library_is_auxiliary": True,
        }
        review: RiskReviewResult | None = None
        last_events: tuple[RiskEvent, ...] = ()
        for revision in range(self.config.max_risk_revision_rounds + 1):
            payload = dict(base_payload)
            if review is not None:
                payload["previous_risk_events"] = [item.to_dict() for item in last_events]
                payload["review_issues_to_fix"] = [item.to_dict() for item in review.issues if item.severity == "error"]
            last_events = self._generate(case, payload)
            review = self._review(case, last_events, candidates)
            if review.passed:
                return RiskExtractionResult(
                    last_events,
                    review,
                    tuple(item.id for item in candidates),
                    revision,
                )
        details = "; ".join(item.message for item in (review.issues if review else ()) if item.severity == "error")
        raise RiskExtractionRejectedError(f"案例{case.case_id}风险事件链审查未通过: {details or '未给出原因'}")

    def _generate(self, case: CaseInput, payload: dict[str, Any]) -> tuple[RiskEvent, ...]:
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request = dict(payload)
            if last_error is not None:
                request["response_correction"] = str(last_error)
            try:
                response = self.model.generate(self._system_prompt, self.prompts.render("risk_extract", request))
                raw = response.get("risk_events")
                if not isinstance(raw, list):
                    raise ModelResponseError("风险提取输出缺少risk_events数组")
                events = tuple(RiskEvent.from_dict(item, set(self.config.allowed_risk_types)) for item in raw)
                self._validate_events(case, events)
                return events
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "风险事件生成失败"))

    def _review(self, case: CaseInput, events: tuple[RiskEvent, ...], candidates: list[Any]) -> RiskReviewResult:
        payload = {
            "task": "risk_review",
            "case_id": case.case_id,
            "raw_events": [item.to_dict() for item in case.events],
            "risk_events": [item.to_dict() for item in events],
            "risk_rule_candidates": [item.to_dict() for item in candidates],
            "allowed_risk_types": list(self.config.allowed_risk_types),
            "rule_library_is_auxiliary": True,
        }
        last_error: Exception | None = None
        for attempt in range(self.config.model_attempts):
            request = dict(payload)
            if last_error is not None:
                request["response_correction"] = str(last_error)
            try:
                result = RiskReviewResult.from_dict(
                    self.model.generate(self._system_prompt, self.prompts.render("risk_reviewer", request))
                )
                valid_indexes = {item.source_event_index for item in case.events}
                for issue in result.issues:
                    if not set(issue.source_event_indexes) <= valid_indexes:
                        raise ModelResponseError("风险审查引用了未知原始事件序号")
                return result
            except ModelResponseError as exc:
                last_error = exc
                if attempt + 1 == self.config.model_attempts:
                    raise
        raise ModelResponseError(str(last_error or "风险审查失败"))

    @staticmethod
    def _validate_events(case: CaseInput, events: tuple[RiskEvent, ...]) -> None:
        if not events:
            raise ModelResponseError("风险事件链不能为空；若确无风险应由审查流程明确处理")
        ids = [item.risk_event_id for item in events]
        expected_ids = [f"R{index:03d}" for index in range(1, len(events) + 1)]
        if ids != expected_ids or any(not re.fullmatch(r"R\d{3}", item) for item in ids):
            raise ModelResponseError("risk_event_id必须按R001开始连续编号")
        by_index = {item.source_event_index: item for item in case.events}
        previous_start: datetime | None = None
        seen_signatures: set[tuple[int, ...]] = set()
        for event in events:
            if not set(event.source_event_indexes) <= set(by_index):
                raise ModelResponseError(f"风险事件{event.risk_event_id}引用未知原始事件")
            signature = tuple(sorted(event.source_event_indexes))
            if signature in seen_signatures:
                raise ModelResponseError(f"风险事件{event.risk_event_id}与其他风险事件引用完全重复")
            seen_signatures.add(signature)
            cited_times = [datetime.strptime(by_index[index].occurred_at, "%Y-%m-%d %H:%M:%S") for index in event.source_event_indexes]
            expected_start = min(cited_times).strftime("%Y-%m-%d %H:%M:%S")
            expected_end = max(cited_times).strftime("%Y-%m-%d %H:%M:%S")
            if event.start_time != expected_start or event.end_time != expected_end:
                raise ModelResponseError(f"风险事件{event.risk_event_id}起止时间必须由引用事件计算")
            start = min(cited_times)
            if previous_start is not None and start < previous_start:
                raise ModelResponseError("风险事件必须按start_time升序排列")
            previous_start = start
