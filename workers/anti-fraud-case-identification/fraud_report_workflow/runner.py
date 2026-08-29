from __future__ import annotations

import json
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from time import perf_counter
from typing import Any, Iterable

from .case_input import safe_case_name
from .models import CaseInput, GeneratedReport, RiskExtractionResult
from .report_workflow import FraudReportWorkflow, PARAGRAPH_IDS
from .risk_extraction import RiskExtractor


class BatchRunner:
    def __init__(self, extractor: RiskExtractor, reporter: FraudReportWorkflow) -> None:
        self.extractor = extractor
        self.reporter = reporter

    def run(self, cases: Iterable[CaseInput], output_dir: Path, case_workers: int, overwrite: bool) -> dict[str, Any]:
        items = list(cases)
        if case_workers < 1:
            raise ValueError("case_workers必须大于0")
        root = Path(output_dir).expanduser().resolve()
        started = perf_counter()
        results: dict[int, dict[str, Any]] = {}
        with ThreadPoolExecutor(max_workers=min(case_workers, len(items)), thread_name_prefix="fraud-case") as executor:
            futures = {executor.submit(self._run_case, index, case, root, overwrite): index for index, case in enumerate(items, start=1)}
            for future in as_completed(futures):
                index = futures[future]
                try:
                    results[index] = future.result()
                except Exception as exc:
                    case = items[index - 1]
                    results[index] = {
                        "case_id": case.case_id,
                        "input_source": case.source_id,
                        "status": "failed",
                        "stage": "risk_extraction",
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                    }
        ordered = [results[index] for index in range(1, len(items) + 1)]
        failed = sum(item["status"] == "failed" for item in ordered)
        summary = {
            "status": "success" if failed == 0 else "partial_failure",
            "case_count": len(ordered),
            "success_count": sum(item["status"] in {"success", "skipped"} for item in ordered),
            "failure_count": failed,
            "skipped_count": sum(item["status"] == "skipped" for item in ordered),
            "risk_chain_only_count": sum(item.get("stage") == "report_generation" for item in ordered),
            "duration_seconds": round(perf_counter() - started, 6),
            "results": ordered,
        }
        dump_json(root / "batch_summary.json", summary)
        return summary

    def _run_case(self, index: int, case: CaseInput, output_root: Path, overwrite: bool) -> dict[str, Any]:
        started = perf_counter()
        case_dir = output_root / f"{index:04d}_{safe_case_name(case.case_id)}"
        risk_path = case_dir / "risk_event_chain.json"
        report_path = case_dir / "fraud_analysis_report.json"
        audit_path = case_dir / "generation_audit.json"
        if all(path.exists() for path in (risk_path, report_path, audit_path)) and not overwrite:
            return {
                "case_id": case.case_id,
                "input_source": case.source_id,
                "status": "skipped",
                "output_path": str(report_path),
                "duration_seconds": round(perf_counter() - started, 6),
            }
        risk = self.extractor.run(case)
        dump_json(risk_path, [item.to_dict() for item in risk.events])
        try:
            report = self.reporter.run(case, risk.events)
        except Exception as exc:
            dump_json(audit_path, self._failure_audit(case, risk, exc, perf_counter() - started))
            return {
                "case_id": case.case_id,
                "input_source": case.source_id,
                "status": "failed",
                "stage": "report_generation",
                "risk_event_path": str(risk_path),
                "error_type": type(exc).__name__,
                "error": str(exc),
                "duration_seconds": round(perf_counter() - started, 6),
            }
        dump_json(report_path, {"text": report.text})
        dump_json(audit_path, self._success_audit(case, risk, report, perf_counter() - started))
        return {
            "case_id": case.case_id,
            "input_source": case.source_id,
            "status": "success",
            "risk_event_path": str(risk_path),
            "output_path": str(report_path),
            "duration_seconds": round(perf_counter() - started, 6),
        }

    def _risk_audit(self, risk: RiskExtractionResult) -> dict[str, Any]:
        return {
            "rule_library_version": self.extractor.rule_library.version,
            "candidate_rule_ids": list(risk.rule_candidate_ids),
            "risk_event_count": len(risk.events),
            "risk_events": {item.risk_event_id: {"risk_type": item.risk_type, "source_event_indexes": list(item.source_event_indexes)} for item in risk.events},
            "review": risk.review.to_dict(),
            "revision_count": risk.revision_count,
        }

    def _success_audit(self, case: CaseInput, risk: RiskExtractionResult, report: GeneratedReport, duration: float) -> dict[str, Any]:
        return {
            "case_id": case.case_id,
            "input_source": case.source_id,
            "status": "success",
            "model": self.extractor.model.model_name,
            "input_warnings": list(case.warnings),
            "risk_extraction": self._risk_audit(risk),
            "paragraphs": {
                item: {
                    "status": report.paragraphs[item].status,
                    "fact_refs": list(report.paragraphs[item].fact_refs),
                    "risk_event_refs": list(report.paragraphs[item].risk_event_refs),
                    "missing_inputs": list(report.paragraphs[item].missing_inputs),
                    "rewrite_count": report.rewrite_counts.get(item, 0),
                }
                for item in PARAGRAPH_IDS
            },
            "report_review": report.review.to_dict(),
            "length_warnings": list(report.length_warnings),
            "duration_seconds": round(duration, 6),
        }

    def _failure_audit(self, case: CaseInput, risk: RiskExtractionResult, exc: Exception, duration: float) -> dict[str, Any]:
        return {
            "case_id": case.case_id,
            "input_source": case.source_id,
            "status": "report_failed_risk_chain_preserved",
            "model": self.extractor.model.model_name,
            "input_warnings": list(case.warnings),
            "risk_extraction": self._risk_audit(risk),
            "error_type": type(exc).__name__,
            "error": str(exc),
            "duration_seconds": round(duration, 6),
        }


def dump_json(path: Path, value: Any) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(dir=str(destination.parent), prefix=f".{destination.name}.", suffix=".tmp")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, destination)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
