from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from .errors import ModelResponseError


JsonObject = dict[str, Any]
ParagraphStatus = Literal["complete", "partial", "not_assessable"]


@dataclass(frozen=True)
class CaseInput:
    csv_path: Path
    row_number: int | None
    case_id: str
    basic_info: JsonObject
    customers: list[JsonObject]
    transaction_features: JsonObject
    warnings: tuple[str, ...] = ()
    raw_transaction_features: JsonObject | None = None

    @property
    def source_id(self) -> str:
        if self.row_number is None:
            return str(self.csv_path)
        return f"{self.csv_path}#row={self.row_number}"


@dataclass(frozen=True)
class FactIndex:
    values: dict[str, Any]
    groups: dict[str, tuple[str, ...]]

    def select(self, *groups: str) -> dict[str, Any]:
        refs: list[str] = []
        for group in groups:
            refs.extend(self.groups.get(group, ()))
        return {ref: self.values[ref] for ref in dict.fromkeys(refs)}

    def resolve(self, refs: list[str] | tuple[str, ...]) -> dict[str, Any]:
        return {ref: self.values[ref] for ref in refs if ref in self.values}


@dataclass(frozen=True)
class KnowledgeCandidate:
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
class Paragraph:
    paragraph_id: str
    text: str
    fact_refs: tuple[str, ...]
    status: ParagraphStatus
    missing_inputs: tuple[str, ...] = ()
    signal_refs: tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, value: Any) -> "Paragraph":
        if not isinstance(value, dict):
            raise ModelResponseError("段落结果必须是JSON对象")
        paragraph_id = str(value.get("paragraph_id") or "").strip()
        text = str(value.get("text") or "").strip()
        status = str(value.get("status") or "").strip()
        if not paragraph_id or not text:
            raise ModelResponseError("段落结果缺少 paragraph_id 或 text")
        if status not in {"complete", "partial", "not_assessable"}:
            raise ModelResponseError(f"段落 {paragraph_id} 的 status 无效")
        return cls(
            paragraph_id=paragraph_id,
            text=text,
            fact_refs=_string_tuple(value.get("fact_refs")),
            status=status,  # type: ignore[arg-type]
            missing_inputs=_string_tuple(value.get("missing_inputs")),
            signal_refs=_string_tuple(value.get("signal_refs")),
        )

    def to_dict(self) -> JsonObject:
        result: JsonObject = {
            "paragraph_id": self.paragraph_id,
            "text": self.text,
            "fact_refs": list(self.fact_refs),
            "status": self.status,
            "missing_inputs": list(self.missing_inputs),
        }
        if self.signal_refs:
            result["signal_refs"] = list(self.signal_refs)
        return result


@dataclass(frozen=True)
class ReviewIssue:
    paragraph_id: str
    code: str
    message: str
    severity: Literal["error", "warning"] = "error"

    @classmethod
    def from_dict(cls, value: Any) -> "ReviewIssue":
        if not isinstance(value, dict):
            raise ModelResponseError("审查问题必须是JSON对象")
        paragraph_id = str(value.get("paragraph_id") or "").strip()
        code = str(value.get("code") or "").strip()
        message = str(value.get("message") or "").strip()
        severity = str(value.get("severity") or "error").strip()
        if not paragraph_id or not code or not message:
            raise ModelResponseError("审查问题字段不完整")
        if severity not in {"error", "warning"}:
            raise ModelResponseError("审查问题 severity 无效")
        return cls(paragraph_id, code, message, severity)  # type: ignore[arg-type]

    def to_dict(self) -> JsonObject:
        return {
            "paragraph_id": self.paragraph_id,
            "code": self.code,
            "message": self.message,
            "severity": self.severity,
        }


@dataclass(frozen=True)
class ReviewResult:
    passed: bool
    issues: tuple[ReviewIssue, ...]
    rewrite_targets: tuple[str, ...]

    @classmethod
    def from_dict(cls, value: Any) -> "ReviewResult":
        if not isinstance(value, dict) or not isinstance(value.get("pass"), bool):
            raise ModelResponseError("审查结果缺少布尔字段 pass")
        issues = tuple(ReviewIssue.from_dict(item) for item in value.get("issues", []))
        reported_targets = _string_tuple(value.get("rewrite_targets"))
        error_ids = tuple(
            dict.fromkeys(
                issue.paragraph_id for issue in issues if issue.severity == "error"
            )
        )
        if value["pass"] and error_ids:
            raise ModelResponseError("审查结果通过但仍包含 error")
        if value["pass"] and reported_targets:
            raise ModelResponseError("审查结果通过但仍包含 rewrite_targets")
        if not error_ids:
            return cls(True, issues, ())
        targets = tuple(
            dict.fromkeys(
                [item for item in reported_targets if item in error_ids] + list(error_ids)
            )
        )
        return cls(False, issues, targets)

    def to_dict(self) -> JsonObject:
        return {
            "pass": self.passed,
            "issues": [issue.to_dict() for issue in self.issues],
            "rewrite_targets": list(self.rewrite_targets),
        }


@dataclass(frozen=True)
class GeneratedCase:
    case_id: str
    paragraphs: dict[str, Paragraph]
    analysis_text1: str
    analysis_text2: str
    review: ReviewResult
    rewrite_counts: dict[str, int] = field(default_factory=dict)
    length_warnings: tuple[str, ...] = ()


def _string_tuple(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise ModelResponseError("引用和缺失项必须是JSON数组")
    return tuple(dict.fromkeys(str(item).strip() for item in value if str(item).strip()))
