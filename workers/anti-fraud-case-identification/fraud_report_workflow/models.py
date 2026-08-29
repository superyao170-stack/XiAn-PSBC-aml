from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from .errors import ModelResponseError


JsonObject = dict[str, Any]
ParagraphStatus = Literal["complete", "partial", "not_assessable"]


@dataclass(frozen=True)
class NormalizedEvent:
    source_event_index: int
    occurred_at: str
    event_type: str
    content: str

    def to_dict(self) -> JsonObject:
        return {
            "source_event_index": self.source_event_index,
            "发生时间": self.occurred_at,
            "类型": self.event_type,
            "具体内容": self.content,
        }


@dataclass(frozen=True)
class CaseInput:
    input_dir: Path
    case_id: str
    basic_info: JsonObject
    customers: list[JsonObject]
    accounts: list[JsonObject]
    devices: list[JsonObject]
    events: tuple[NormalizedEvent, ...]
    warnings: tuple[str, ...] = ()

    @property
    def source_id(self) -> str:
        return str(self.input_dir)


@dataclass(frozen=True)
class RuleCandidate:
    id: str
    name: str
    category: str
    rule: str
    reference_only: bool = True

    def to_dict(self) -> JsonObject:
        return {
            "id": self.id,
            "name": self.name,
            "category": self.category,
            "rule": self.rule,
            "reference_only": self.reference_only,
        }


@dataclass(frozen=True)
class RiskEvent:
    risk_event_id: str
    start_time: str
    end_time: str
    risk_type: str
    content: str
    source_event_indexes: tuple[int, ...]

    @classmethod
    def from_dict(cls, value: Any, allowed_types: set[str]) -> "RiskEvent":
        if not isinstance(value, dict):
            raise ModelResponseError("风险事件必须是JSON对象")
        required = {
            "risk_event_id", "start_time", "end_time", "risk_type", "content",
            "source_event_indexes",
        }
        if set(value) != required:
            raise ModelResponseError("风险事件字段必须严格为6个规定字段")
        event_id = str(value.get("risk_event_id") or "").strip()
        start_time = str(value.get("start_time") or "").strip()
        end_time = str(value.get("end_time") or "").strip()
        risk_type = str(value.get("risk_type") or "").strip()
        content = str(value.get("content") or "").strip()
        raw_indexes = value.get("source_event_indexes")
        if not event_id or not start_time or not end_time or not content:
            raise ModelResponseError("风险事件存在空字段")
        if risk_type not in allowed_types:
            raise ModelResponseError(f"风险类型不在允许枚举中: {risk_type}")
        if not isinstance(raw_indexes, list) or not raw_indexes:
            raise ModelResponseError(f"风险事件 {event_id} 缺少原始事件引用")
        try:
            indexes = tuple(dict.fromkeys(int(item) for item in raw_indexes))
        except (TypeError, ValueError) as exc:
            raise ModelResponseError(f"风险事件 {event_id} 的原始事件引用无效") from exc
        if any(item < 1 for item in indexes):
            raise ModelResponseError(f"风险事件 {event_id} 的原始事件序号必须从1开始")
        return cls(event_id, start_time, end_time, risk_type, content, indexes)

    def to_dict(self) -> JsonObject:
        return {
            "risk_event_id": self.risk_event_id,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "risk_type": self.risk_type,
            "content": self.content,
            "source_event_indexes": list(self.source_event_indexes),
        }


@dataclass(frozen=True)
class RiskReviewIssue:
    code: str
    message: str
    severity: Literal["error", "warning"]
    source_event_indexes: tuple[int, ...] = ()

    @classmethod
    def from_dict(cls, value: Any) -> "RiskReviewIssue":
        if not isinstance(value, dict):
            raise ModelResponseError("风险审查问题必须是JSON对象")
        code = str(value.get("code") or "").strip()
        message = str(value.get("message") or "").strip()
        severity = str(value.get("severity") or "error").strip()
        if not code or not message or severity not in {"error", "warning"}:
            raise ModelResponseError("风险审查问题字段无效")
        raw = value.get("source_event_indexes", [])
        if not isinstance(raw, list):
            raise ModelResponseError("风险审查事件引用必须是数组")
        return cls(code, message, severity, tuple(dict.fromkeys(int(item) for item in raw)))  # type: ignore[arg-type]

    def to_dict(self) -> JsonObject:
        return {
            "code": self.code,
            "message": self.message,
            "severity": self.severity,
            "source_event_indexes": list(self.source_event_indexes),
        }


@dataclass(frozen=True)
class RiskReviewResult:
    passed: bool
    issues: tuple[RiskReviewIssue, ...]

    @classmethod
    def from_dict(cls, value: Any) -> "RiskReviewResult":
        if not isinstance(value, dict) or not isinstance(value.get("pass"), bool):
            raise ModelResponseError("风险审查结果缺少布尔字段pass")
        issues = tuple(RiskReviewIssue.from_dict(item) for item in value.get("issues", []))
        has_error = any(item.severity == "error" for item in issues)
        if value["pass"] and has_error:
            raise ModelResponseError("风险审查通过但仍包含error")
        return cls(not has_error, issues)

    def to_dict(self) -> JsonObject:
        return {"pass": self.passed, "issues": [item.to_dict() for item in self.issues]}


@dataclass(frozen=True)
class RiskExtractionResult:
    events: tuple[RiskEvent, ...]
    review: RiskReviewResult
    rule_candidate_ids: tuple[str, ...]
    revision_count: int = 0


@dataclass(frozen=True)
class FactIndex:
    values: dict[str, Any]
    groups: dict[str, tuple[str, ...]]

    def select(self, *groups: str) -> dict[str, Any]:
        refs: list[str] = []
        for group in groups:
            refs.extend(self.groups.get(group, ()))
        return {ref: self.values[ref] for ref in dict.fromkeys(refs)}

    def resolve(self, refs: tuple[str, ...] | list[str]) -> dict[str, Any]:
        return {ref: self.values[ref] for ref in refs if ref in self.values}


@dataclass(frozen=True)
class Paragraph:
    paragraph_id: str
    text: str
    fact_refs: tuple[str, ...]
    risk_event_refs: tuple[str, ...]
    status: ParagraphStatus
    missing_inputs: tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, value: Any) -> "Paragraph":
        if not isinstance(value, dict):
            raise ModelResponseError("报告段落必须是JSON对象")
        paragraph_id = str(value.get("paragraph_id") or "").strip()
        text = str(value.get("text") or "").strip()
        status = str(value.get("status") or "").strip()
        if not paragraph_id or not text or status not in {"complete", "partial", "not_assessable"}:
            raise ModelResponseError("报告段落字段无效")
        return cls(
            paragraph_id,
            text,
            _string_tuple(value.get("fact_refs")),
            _string_tuple(value.get("risk_event_refs")),
            status,  # type: ignore[arg-type]
            _string_tuple(value.get("missing_inputs")),
        )

    def to_dict(self) -> JsonObject:
        return {
            "paragraph_id": self.paragraph_id,
            "text": self.text,
            "fact_refs": list(self.fact_refs),
            "risk_event_refs": list(self.risk_event_refs),
            "missing_inputs": list(self.missing_inputs),
            "status": self.status,
        }


@dataclass(frozen=True)
class ReportReviewIssue:
    paragraph_id: str
    code: str
    message: str
    severity: Literal["error", "warning"] = "error"

    @classmethod
    def from_dict(cls, value: Any) -> "ReportReviewIssue":
        if not isinstance(value, dict):
            raise ModelResponseError("报告审查问题必须是JSON对象")
        paragraph_id = str(value.get("paragraph_id") or "").strip()
        code = str(value.get("code") or "").strip()
        message = str(value.get("message") or "").strip()
        severity = str(value.get("severity") or "error").strip()
        if not paragraph_id or not code or not message or severity not in {"error", "warning"}:
            raise ModelResponseError("报告审查问题字段无效")
        return cls(paragraph_id, code, message, severity)  # type: ignore[arg-type]

    def to_dict(self) -> JsonObject:
        return {
            "paragraph_id": self.paragraph_id,
            "code": self.code,
            "message": self.message,
            "severity": self.severity,
        }


@dataclass(frozen=True)
class ReportReviewResult:
    passed: bool
    issues: tuple[ReportReviewIssue, ...]
    rewrite_targets: tuple[str, ...]

    @classmethod
    def from_dict(cls, value: Any) -> "ReportReviewResult":
        if not isinstance(value, dict) or not isinstance(value.get("pass"), bool):
            raise ModelResponseError("报告审查结果缺少布尔字段pass")
        issues = tuple(ReportReviewIssue.from_dict(item) for item in value.get("issues", []))
        error_ids = tuple(dict.fromkeys(item.paragraph_id for item in issues if item.severity == "error"))
        targets = _string_tuple(value.get("rewrite_targets"))
        if value["pass"] and error_ids:
            raise ModelResponseError("报告审查通过但仍包含error")
        if not error_ids:
            return cls(True, issues, ())
        repaired = tuple(dict.fromkeys([item for item in targets if item in error_ids] + list(error_ids)))
        return cls(False, issues, repaired)

    def to_dict(self) -> JsonObject:
        return {
            "pass": self.passed,
            "issues": [item.to_dict() for item in self.issues],
            "rewrite_targets": list(self.rewrite_targets),
        }


@dataclass(frozen=True)
class GeneratedReport:
    text: str
    paragraphs: dict[str, Paragraph]
    review: ReportReviewResult
    rewrite_counts: dict[str, int] = field(default_factory=dict)
    length_warnings: tuple[str, ...] = ()


def _string_tuple(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise ModelResponseError("引用和缺失项必须是JSON数组")
    return tuple(dict.fromkeys(str(item).strip() for item in value if str(item).strip()))
