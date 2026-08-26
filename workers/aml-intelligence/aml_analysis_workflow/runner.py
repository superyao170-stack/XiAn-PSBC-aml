from __future__ import annotations

import hashlib
import json
import os
import tempfile
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from pathlib import Path
from time import perf_counter
from typing import Any, Iterable

from .csv_input import read_case, read_cases, safe_case_name
from .models import CaseInput, GeneratedCase
from .workflow import AnalysisWorkflow, REQUIRED_PARAGRAPHS
from .xlsx_input import read_xlsx_cases


FINGERPRINT_VERSION = "aml-analysis-workflow-v1"


class BatchRunner:
    """Run a single case or a tabular batch with restartable artifacts."""

    def __init__(
        self,
        workflow: AnalysisWorkflow | None = None,
        *,
        generator: Any | None = None,
    ) -> None:
        if (workflow is None) == (generator is None):
            raise ValueError("workflow 和 generator 必须且只能提供一个")
        self.workflow = workflow
        self.generator = generator

    def run(
        self,
        cases: Iterable[CaseInput],
        output_dir: Path,
        case_workers: int,
        overwrite: bool,
    ) -> dict[str, Any]:
        items = list(cases)
        if not items:
            raise ValueError("没有可处理的案例")
        if case_workers < 1:
            raise ValueError("case_workers 必须大于0")
        root = Path(output_dir).expanduser().resolve()
        root.mkdir(parents=True, exist_ok=True)
        started = perf_counter()
        results: dict[int, dict[str, Any]] = {}
        worker_count = min(case_workers, len(items))

        def summary(status: str) -> dict[str, Any]:
            ordered = [results[index] for index in sorted(results)]
            source_path = items[0].csv_path
            return {
                "status": status,
                "input_path": str(source_path),
                "input_format": source_path.suffix.lower().lstrip("."),
                "batch_output_dir": str(root),
                "case_count": len(items),
                "completed_count": len(ordered),
                "pending_count": len(items) - len(ordered),
                "success_count": sum(
                    item["status"] in {"success", "skipped"} for item in ordered
                ),
                "failure_count": sum(item["status"] == "failed" for item in ordered),
                "skipped_count": sum(item["status"] == "skipped" for item in ordered),
                "max_workers": worker_count,
                "duration_seconds": round(perf_counter() - started, 6),
                "generation_input_fields": [
                    "basic_info",
                    "customers",
                    "transaction_features",
                ],
                "excluded_generation_field": "analysis_texts",
                "generated_text_usage": "framework_extraction_input",
                "framework_extraction_analysis_source": (
                    "generated_analysis/analysis_texts.json"
                ),
                "results": ordered,
            }

        dump_json(root / "batch_summary.json", summary("running"))
        with ThreadPoolExecutor(
            max_workers=worker_count,
            thread_name_prefix="analysis-case",
        ) as executor:
            futures: dict[Future[dict[str, Any]], int] = {
                executor.submit(
                    self._run_case,
                    case,
                    root / f"{index:04d}_{safe_case_name(case.case_id)}",
                    overwrite,
                ): index
                for index, case in enumerate(items, start=1)
            }
            for future in as_completed(futures):
                index = futures[future]
                results[index] = future.result()
                dump_json(root / "batch_summary.json", summary("running"))

        status = (
            "success"
            if all(item["status"] in {"success", "skipped"} for item in results.values())
            else "partial_failure"
        )
        value = summary(status)
        dump_json(root / "batch_summary.json", value)
        return value

    def run_single(
        self,
        case: CaseInput,
        output_path: Path,
        overwrite: bool,
    ) -> dict[str, Any]:
        destination = Path(output_path).expanduser().resolve()
        result = self._run_case(
            case,
            destination.parent,
            overwrite,
            analysis_path=destination,
        )
        if result["status"] == "failed":
            raise RuntimeError(str(result.get("error") or "单案例分析生成失败"))
        return result

    def _run_case(
        self,
        case: CaseInput,
        case_dir: Path,
        overwrite: bool,
        *,
        analysis_path: Path | None = None,
    ) -> dict[str, Any]:
        started = perf_counter()
        output_path = analysis_path or case_dir / "analysis_texts.json"
        audit_path = case_dir / "generation_audit.json"
        status_path = case_dir / "case_status.json"
        workflow_path = case_dir / "workflow_generation_result.json"
        fingerprint = self._fingerprint(case)
        common = {
            "input_path": case.source_id,
            "input_row_number": case.row_number,
            "case_id": case.case_id,
            "output_path": str(output_path),
            "input_fingerprint": fingerprint,
            "generation_input_fields": [
                "basic_info",
                "customers",
                "transaction_features",
            ],
            "analysis_texts_input_present": False,
        }
        try:
            if not overwrite and _reusable(output_path, audit_path, status_path, fingerprint):
                result = {
                    **common,
                    "status": "skipped",
                    "duration_seconds": round(perf_counter() - started, 6),
                }
                dump_json(status_path, result)
                return result
            if analysis_path is not None and output_path.exists() and not overwrite:
                raise FileExistsError(
                    f"输出文件已存在: {output_path}；如确认覆盖，请增加 --overwrite"
                )
            previous_hash = (
                hashlib.sha256(output_path.read_bytes()).hexdigest()
                if output_path.is_file()
                else ""
            )
            dump_json(
                status_path,
                {
                    **common,
                    "status": "running",
                    "previous_output_sha256": previous_hash,
                },
            )
            generated, audit = self._generate(case, started)
            analysis = {
                "analysis_text1": generated.analysis_text1,
                "analysis_text2": generated.analysis_text2,
            }
            _validate_analysis(analysis)
            dump_json(output_path, analysis)
            dump_json(audit_path, audit)
            dump_json(
                workflow_path,
                {
                    "case_id": generated.case_id,
                    "paragraphs": {
                        key: paragraph.to_dict()
                        for key, paragraph in generated.paragraphs.items()
                    },
                    "review": generated.review.to_dict(),
                    "rewrite_counts": dict(generated.rewrite_counts),
                    "length_warnings": list(generated.length_warnings),
                    "assembled_analysis_texts": analysis,
                },
            )
            result = {
                **common,
                "status": "success",
                "duration_seconds": round(perf_counter() - started, 6),
            }
            dump_json(status_path, result)
            return result
        except Exception as exc:
            trace = getattr(self.generator, "generation_trace", None)
            if callable(trace):
                dump_json(
                    case_dir / "generation_error_trace.json",
                    {
                        "case_id": case.case_id,
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                        "model_calls": trace(case.case_id),
                    },
                )
            result = {
                **common,
                "status": "failed",
                "error_type": type(exc).__name__,
                "error": str(exc),
                "duration_seconds": round(perf_counter() - started, 6),
            }
            dump_json(status_path, result)
            return result

    def _generate(
        self,
        case: CaseInput,
        started: float,
    ) -> tuple[GeneratedCase, dict[str, Any]]:
        if self.generator is not None:
            return self.generator.generate_artifact(
                {"basic_info": case.basic_info, "customers": case.customers},
                case.raw_transaction_features or case.transaction_features,
            )
        assert self.workflow is not None
        generated = self.workflow.run(case)
        return generated, self._audit(case, generated, perf_counter() - started)

    def _fingerprint(self, case: CaseInput) -> str:
        model = (
            getattr(self.generator, "llm_client", None)
            if self.generator is not None
            else getattr(self.workflow, "model", None)
        )
        payload = {
            "version": FINGERPRINT_VERSION,
            "case": {
                "basic_info": case.basic_info,
                "customers": case.customers,
                "transaction_features": (
                    case.raw_transaction_features or case.transaction_features
                ),
            },
            "prompt": getattr(self.generator, "prompt_template", None),
            "model": getattr(model, "chat_model", None)
            or getattr(model, "model_name", None),
            "base_url": getattr(model, "base_url", None),
            "temperature": getattr(model, "temperature", None),
            "top_p": getattr(model, "top_p", None),
            "seed": getattr(model, "seed", None),
        }
        encoded = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def _audit(
        self,
        case: CaseInput,
        generated: GeneratedCase,
        duration_seconds: float,
    ) -> dict[str, Any]:
        assert self.workflow is not None
        return {
            "case_id": case.case_id,
            "input_source": case.source_id,
            "model": self.workflow.model.model_name,
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
            "duration_seconds": round(duration_seconds, 6),
        }


def run_csv_generation(
    input_csv: Path,
    batch_output_dir: Path,
    generator: Any,
    max_workers: int = 2,
    overwrite: bool = False,
) -> dict[str, Any]:
    return BatchRunner(generator=generator).run(
        read_cases(input_csv),
        batch_output_dir,
        case_workers=max_workers,
        overwrite=overwrite,
    )


def run_xlsx_generation(
    input_xlsx: Path,
    batch_output_dir: Path,
    generator: Any,
    max_workers: int = 2,
    overwrite: bool = False,
) -> dict[str, Any]:
    return BatchRunner(generator=generator).run(
        read_xlsx_cases(input_xlsx),
        batch_output_dir,
        case_workers=max_workers,
        overwrite=overwrite,
    )


def run_single_generation(
    input_file: Path,
    output_path: Path,
    generator: Any,
    overwrite: bool = False,
) -> dict[str, Any]:
    return BatchRunner(generator=generator).run_single(
        read_case(input_file),
        output_path,
        overwrite=overwrite,
    )


def _validate_analysis(value: dict[str, Any]) -> None:
    if set(value) != {"analysis_text1", "analysis_text2"}:
        raise ValueError("分析结果必须且只能包含 analysis_text1、analysis_text2")
    texts = [str(value[key]).strip() for key in ("analysis_text1", "analysis_text2")]
    if not all(texts) or texts[0] == texts[1]:
        raise ValueError("analysis_text1、analysis_text2 必须非空且内容不同")


def _reusable(
    output_path: Path,
    audit_path: Path,
    status_path: Path,
    fingerprint: str,
) -> bool:
    if not output_path.is_file() or not audit_path.is_file() or not status_path.is_file():
        return False
    try:
        analysis = json.loads(output_path.read_text(encoding="utf-8-sig"))
        status = json.loads(status_path.read_text(encoding="utf-8-sig"))
        _validate_analysis(analysis)
    except (json.JSONDecodeError, OSError, ValueError):
        return False
    if not isinstance(status, dict) or status.get("input_fingerprint") != fingerprint:
        return False
    if status.get("status") in {"success", "skipped"}:
        return True
    if status.get("status") != "running":
        return False
    previous_hash = str(status.get("previous_output_sha256") or "")
    return hashlib.sha256(output_path.read_bytes()).hexdigest() != previous_hash


def dump_json(path: Path, value: Any) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=str(destination.parent), prefix=f".{destination.name}.", suffix=".tmp"
    )
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
