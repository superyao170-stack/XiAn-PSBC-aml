"""Concurrent workflow orchestration for the bank data graph project."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from time import perf_counter
from typing import Any, Callable, Dict, Iterable, Tuple

from datagraph_bank.case_library import CaseLibrary, ConflictMode
from datagraph_bank.concurrency import LocalServiceController
from datagraph_bank.io_utils import (
    CsvCaseInput,
    case_input_identity,
    case_input_name,
    dump_json,
    load_case_input,
    load_csv_case_inputs,
    load_json,
)
from datagraph_bank.llm import DeepSeekLLMClient
from datagraph_bank.models import IllegalBehaviorCase
from datagraph_bank.nodes import (
    ContextEnhancer,
    CoreferenceResolver,
    EntityEnhancer,
    EventExtractor,
    RelationshipExtractor,
    ResultMerger,
    StructuredDataProcessor,
    TransactionDataProcessor,
    UnstructuredTextProcessor,
)


@dataclass(frozen=True)
class CaseRunSummary:
    """Serializable outcome of one case inside a batch run."""

    input_path: str
    status: str
    run_dir: str
    duration_seconds: float
    input_fingerprint: str = ""
    case_id: str = ""
    event_count: int = 0
    relationship_count: int = 0
    library_status: str = ""
    library_path: str = ""
    error_type: str = ""
    error: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "input_path": self.input_path,
            "status": self.status,
            "run_dir": self.run_dir,
            "duration_seconds": round(self.duration_seconds, 6),
            "input_fingerprint": self.input_fingerprint,
            "case_id": self.case_id,
            "event_count": self.event_count,
            "relationship_count": self.relationship_count,
            "library_status": self.library_status,
            "library_path": self.library_path,
            "error_type": self.error_type,
            "error": self.error,
        }


@dataclass(frozen=True)
class BatchRunResult:
    """Batch output directory and ordered per-case results."""

    run_dir: Path
    results: Tuple[CaseRunSummary, ...]
    duration_seconds: float
    max_workers: int
    config_fingerprint: str
    service_statistics: Dict[str, Any]

    @property
    def success_count(self) -> int:
        return sum(item.status in {"success", "skipped"} for item in self.results)

    @property
    def failure_count(self) -> int:
        return sum(item.status == "failed" for item in self.results)

    @property
    def skipped_count(self) -> int:
        return sum(item.status == "skipped" for item in self.results)

    @property
    def processed_count(self) -> int:
        return len(self.results) - self.skipped_count

    def to_dict(self) -> Dict[str, Any]:
        if self.failure_count == len(self.results):
            status = "failed"
        elif self.failure_count:
            status = "partial_failure"
        else:
            status = "success"
        return {
            "status": status,
            "case_count": len(self.results),
            "completed_count": len(self.results),
            "pending_count": 0,
            "success_count": self.success_count,
            "failure_count": self.failure_count,
            "skipped_count": self.skipped_count,
            "processed_count": self.processed_count,
            "max_workers": self.max_workers,
            "duration_seconds": round(self.duration_seconds, 6),
            "config_fingerprint": self.config_fingerprint,
            "service_statistics": self.service_statistics,
            "results": [item.to_dict() for item in self.results],
        }


class BankCaseWorkflow:
    """Run structured, unstructured, event, relationship and merge nodes."""

    FINGERPRINT_VERSION = "bank-case-workflow-v3"
    CASE_INPUT_FILE_NAMES = (
        "basic_info.json",
        "customers.json",
        "accounts.json",
        "other_entities.json",
        "evidences.json",
        "feature_analysis.json",
        "analysis_texts.json",
        "analysis_text1.txt",
    )

    def __init__(
        self,
        kb_path: Path,
        output_root: Path,
        risk_threshold: float = 0.12,
        dedup_threshold: float = 0.82,
        llm_enabled: bool = True,
        chat_model: str | None = None,
        embedding_model: str | None = None,
        prompt_dir: Path | None = None,
        corenlp_url: str | None = None,
        coreference_threshold: float = 0.80,
        coreference_review_threshold: float = 0.55,
        corenlp_enabled: bool = True,
        llm_concurrency: int = 2,
        corenlp_concurrency: int = 2,
        service_max_attempts: int = 3,
        service_retry_delay: float = 0.5,
        corenlp_timeout: float = 120.0,
        service_controller: LocalServiceController | None = None,
        kb_collection: str | None = None,
    ) -> None:
        self.kb_path = Path(kb_path)
        self.output_root = Path(output_root)
        self.prompt_dir = (
            Path(prompt_dir)
            if prompt_dir
            else Path(__file__).resolve().parents[1] / "data" / "prompts"
        )
        self.service_controller = service_controller or LocalServiceController(
            llm_concurrency=llm_concurrency,
            corenlp_concurrency=corenlp_concurrency,
            max_attempts=service_max_attempts,
            retry_delay=service_retry_delay,
        )
        self._constructor_options: Dict[str, Any] = {
            "kb_path": self.kb_path,
            "output_root": self.output_root,
            "risk_threshold": risk_threshold,
            "dedup_threshold": dedup_threshold,
            "llm_enabled": llm_enabled,
            "chat_model": chat_model,
            "embedding_model": embedding_model,
            "kb_collection": kb_collection,
            "prompt_dir": self.prompt_dir,
            "corenlp_url": corenlp_url,
            "coreference_threshold": coreference_threshold,
            "coreference_review_threshold": coreference_review_threshold,
            "corenlp_enabled": corenlp_enabled,
            "llm_concurrency": llm_concurrency,
            "corenlp_concurrency": corenlp_concurrency,
            "service_max_attempts": service_max_attempts,
            "service_retry_delay": service_retry_delay,
            "corenlp_timeout": corenlp_timeout,
            "service_controller": self.service_controller,
        }
        self.llm_client = DeepSeekLLMClient(
            enabled=llm_enabled,
            chat_model=chat_model,
            embedding_model=embedding_model,
            service_gate=self.service_controller.llm,
        )
        self.structured_processor = StructuredDataProcessor()
        self.transaction_processor = TransactionDataProcessor()
        self.text_processor = UnstructuredTextProcessor()
        self.coreference_resolver = CoreferenceResolver(
            llm_client=self.llm_client,
            corenlp_url=corenlp_url,
            auto_threshold=coreference_threshold,
            review_threshold=coreference_review_threshold,
            enabled=corenlp_enabled,
            timeout=corenlp_timeout,
            service_gate=self.service_controller.corenlp,
        )
        self.entity_enhancer = EntityEnhancer()
        self.context_enhancer = ContextEnhancer()
        self.event_extractor = EventExtractor(
            kb_path=self.kb_path,
            risk_threshold=risk_threshold,
            dedup_threshold=dedup_threshold,
            llm_client=self.llm_client,
            prompt_path=self.prompt_dir / "event_extraction.txt",
            qdrant_collection=kb_collection,
        )
        self.relationship_extractor = RelationshipExtractor(
            llm_client=self.llm_client,
            prompt_paths={
                "entity_entity": self.prompt_dir / "relationship_entity_entity.txt",
                "entity_event": self.prompt_dir / "relationship_entity_event.txt",
                "event_event": self.prompt_dir / "relationship_event_event.txt",
            },
        )
        self.merger = ResultMerger()

    def run(
        self,
        input_path: Path | str | Mapping[str, Any] | CsvCaseInput,
        run_dir: Path | None = None,
        on_progress: Callable[[str, str, Dict[str, Any]], None] | None = None,
    ) -> Tuple[IllegalBehaviorCase, Path]:
        """Run one case, parallelizing its independent preprocessing branches."""

        if run_dir is None:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            run_dir = self.output_root / timestamp
        else:
            run_dir = Path(run_dir)
        run_dir.mkdir(parents=True, exist_ok=True)

        final_result = self._execute_case(
            input_path,
            on_step=lambda step_name, state: self._save_step(
                run_dir,
                step_name,
                state,
            ),
            on_progress=on_progress,
        )
        dump_json(run_dir / "final_case.json", final_result)
        return final_result, run_dir

    def extract(
        self,
        input_data: Path | str | Mapping[str, Any] | CsvCaseInput,
    ) -> IllegalBehaviorCase:
        """Return one final case without writing input or intermediate snapshots."""

        return self._execute_case(input_data)

    def _execute_case(
        self,
        input_data: Path | str | Mapping[str, Any] | CsvCaseInput,
        on_step: Callable[[str, Dict[str, Any]], None] | None = None,
        on_progress: Callable[[str, str, Dict[str, Any]], None] | None = None,
    ) -> IllegalBehaviorCase:
        """Run the shared extraction graph and optionally persist each step."""

        state: Dict[str, Any] = load_case_input(input_data)
        state["risk_event_knowledge_base"] = (
            self.event_extractor.knowledge_base_reference
        )
        state["llm_status"] = self.llm_client.status()
        if on_step is not None:
            on_step("00_input", state)

        state.update(self._run_parallel_preprocessing(state))
        if on_step is not None:
            on_step("01_structured_processing", state)
            on_step("02_text_processing", state)

        for step_name, node in (
            ("03_coreference_resolution", self.coreference_resolver),
            ("04_entity_enhancement", self.entity_enhancer),
            ("05_context_enhancement", self.context_enhancer),
            ("06_event_extraction", self.event_extractor),
            ("07_relationship_extraction", self.relationship_extractor),
            ("08_merge", self.merger),
        ):
            if on_progress is not None and step_name in {
                "06_event_extraction", "07_relationship_extraction"
            }:
                on_progress(step_name, "RUNNING", state)
            state.update(node.process(state))
            if on_step is not None:
                on_step(step_name, state)
            if on_progress is not None and step_name in {
                "06_event_extraction", "07_relationship_extraction"
            }:
                on_progress(step_name, "SUCCEEDED", state)

        return state["final_result"]

    def _run_parallel_preprocessing(self, state: Dict[str, Any]) -> Dict[str, Any]:
        tasks = (
            ("case_structured_data", self.structured_processor),
            ("transaction_structured_data", self.transaction_processor),
            ("analysis_text", self.text_processor),
        )

        def execute(task_name: str, node: Any) -> Tuple[str, Dict[str, Any], float]:
            started = perf_counter()
            result = node.process(state)
            return task_name, result, perf_counter() - started

        task_results: Dict[str, Dict[str, Any]] = {}
        durations: Dict[str, float] = {}
        parallel_started = perf_counter()
        with ThreadPoolExecutor(
            max_workers=len(tasks),
            thread_name_prefix="case-preprocess",
        ) as executor:
            futures = {
                name: executor.submit(execute, name, node)
                for name, node in tasks
            }
            # Resolve in declaration order so merged state and errors are deterministic.
            for name, _ in tasks:
                task_name, result, duration = futures[name].result()
                task_results[task_name] = result
                durations[task_name] = duration
        parallel_duration = perf_counter() - parallel_started

        merged: Dict[str, Any] = {}
        for name, _ in tasks:
            merged.update(task_results[name])
        merged["concurrency_report"] = {
            "strategy": "bounded_thread_pool",
            "parallel_stage": [name for name, _ in tasks],
            "max_workers": len(tasks),
            "parallel_duration_seconds": round(parallel_duration, 6),
            "estimated_serial_duration_seconds": round(sum(durations.values()), 6),
            "task_duration_seconds": {
                name: round(durations[name], 6) for name, _ in tasks
            },
        }
        return merged

    def run_csv_batch(
        self,
        csv_path: Path | str,
        max_workers: int = 4,
        resume: bool = False,
        batch_dir: Path | None = None,
        case_library: CaseLibrary | None = None,
        library_on_conflict: ConflictMode = "error",
    ) -> BatchRunResult:
        """Process every CSV row as one isolated case in a batch run."""

        return self.run_batch(
            load_csv_case_inputs(csv_path),
            max_workers=max_workers,
            resume=resume,
            batch_dir=batch_dir,
            case_library=case_library,
            library_on_conflict=library_on_conflict,
        )

    def run_batch(
        self,
        input_paths: Iterable[Path | CsvCaseInput],
        max_workers: int = 4,
        resume: bool = False,
        batch_dir: Path | None = None,
        case_library: CaseLibrary | None = None,
        library_on_conflict: ConflictMode = "error",
    ) -> BatchRunResult:
        """Process cases and atomically register each completed case if requested."""

        paths = tuple(
            path
            if isinstance(path, CsvCaseInput)
            else Path(path).expanduser().absolute()
            for path in input_paths
        )
        if not paths:
            raise ValueError("批量处理至少需要一个案例输入")
        input_identities = tuple(case_input_identity(path) for path in paths)
        if len(set(input_identities)) != len(paths):
            raise ValueError("批量输入包含重复案例路径")
        if max_workers < 1:
            raise ValueError("max_workers 必须大于等于 1")

        worker_count = min(max_workers, len(paths))
        started = perf_counter()
        if resume:
            batch_dir = self._resolve_resume_batch_dir(batch_dir, paths)
        elif batch_dir is None:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            batch_dir = self.output_root / f"batch_{timestamp}"
        else:
            batch_dir = Path(batch_dir)
        batch_dir.mkdir(parents=True, exist_ok=True)

        indexed_paths = tuple(enumerate(paths, start=1))
        config_fingerprint = self._configuration_fingerprint()
        dump_json(
            batch_dir / "batch_manifest.json",
            {
                "input_paths": list(input_identities),
                "config_fingerprint": config_fingerprint,
                "max_workers": worker_count,
                "service_limits": self.service_controller.snapshot(),
                "case_library_root": (
                    str(case_library.root) if case_library is not None else ""
                ),
                "library_on_conflict": library_on_conflict,
            },
        )
        previous_results = self._load_previous_results(batch_dir) if resume else {}
        ordered_results: Dict[int, CaseRunSummary] = {}
        pending_tasks: list[tuple[int, Path | CsvCaseInput, str, Path]] = []
        for index, input_path in indexed_paths:
            fingerprint = self._input_fingerprint(input_path, config_fingerprint)
            previous = previous_results.get(case_input_identity(input_path))
            if self._can_resume(previous, fingerprint):
                ordered_results[index] = self._resume_case_summary(
                    input_path=input_path,
                    fingerprint=fingerprint,
                    previous=previous,
                    case_library=case_library,
                    library_on_conflict=library_on_conflict,
                )
                continue
            case_run_dir = self._case_run_dir(
                batch_dir,
                index,
                input_path,
                fingerprint,
                previous,
            )
            pending_tasks.append((index, input_path, fingerprint, case_run_dir))

        self._write_batch_progress(
            batch_dir=batch_dir,
            results=ordered_results,
            total_case_count=len(paths),
            worker_count=worker_count,
            config_fingerprint=config_fingerprint,
            started=started,
            status="running",
        )

        executor = ThreadPoolExecutor(
            max_workers=worker_count,
            thread_name_prefix="case-batch",
        )
        future_indexes: Dict[Future[CaseRunSummary], int] = {}
        task_iterator = iter(pending_tasks)

        def submit_next() -> bool:
            try:
                index, input_path, fingerprint, case_run_dir = next(task_iterator)
            except StopIteration:
                return False
            future = executor.submit(
                self._run_batch_case,
                input_path,
                fingerprint,
                case_run_dir,
                case_library,
                library_on_conflict,
            )
            future_indexes[future] = index
            return True

        try:
            for _ in range(worker_count):
                if not submit_next():
                    break
            while future_indexes:
                completed, _ = wait(
                    tuple(future_indexes),
                    return_when=FIRST_COMPLETED,
                )
                for future in completed:
                    index = future_indexes.pop(future)
                    ordered_results[index] = future.result()
                    self._write_batch_progress(
                        batch_dir=batch_dir,
                        results=ordered_results,
                        total_case_count=len(paths),
                        worker_count=worker_count,
                        config_fingerprint=config_fingerprint,
                        started=started,
                        status="running",
                    )
                    submit_next()
        except BaseException:
            for future in future_indexes:
                future.cancel()
            executor.shutdown(wait=False, cancel_futures=True)
            self._write_batch_progress(
                batch_dir=batch_dir,
                results=ordered_results,
                total_case_count=len(paths),
                worker_count=worker_count,
                config_fingerprint=config_fingerprint,
                started=started,
                status="interrupted",
            )
            raise
        else:
            executor.shutdown(wait=True)

        result = BatchRunResult(
            run_dir=batch_dir,
            results=tuple(ordered_results[index] for index, _ in indexed_paths),
            duration_seconds=perf_counter() - started,
            max_workers=worker_count,
            config_fingerprint=config_fingerprint,
            service_statistics=self.service_controller.snapshot(),
        )
        dump_json(batch_dir / "batch_summary.json", result.to_dict())
        return result

    def _run_batch_case(
        self,
        input_path: Path | CsvCaseInput,
        input_fingerprint: str,
        case_run_dir: Path,
        case_library: CaseLibrary | None = None,
        library_on_conflict: ConflictMode = "error",
    ) -> CaseRunSummary:
        started = perf_counter()
        worker = self._new_worker()
        final_case: IllegalBehaviorCase | None = None
        input_identity = case_input_identity(input_path)
        dump_json(
            case_run_dir / "case_status.json",
            {
                "status": "running",
                "input_path": input_identity,
                "input_fingerprint": input_fingerprint,
            },
        )
        try:
            final_case, _ = worker.run(input_path, run_dir=case_run_dir)
            library_result = (
                case_library.add(
                    case_run_dir / "final_case.json",
                    on_conflict=library_on_conflict,
                )
                if case_library is not None
                else None
            )
            result = CaseRunSummary(
                input_path=input_identity,
                status="success",
                run_dir=str(case_run_dir),
                duration_seconds=perf_counter() - started,
                input_fingerprint=input_fingerprint,
                case_id=final_case.basic_info.case_id,
                event_count=len(final_case.events),
                relationship_count=len(final_case.relationships),
                library_status=(library_result.status if library_result else ""),
                library_path=(library_result.library_path if library_result else ""),
            )
            dump_json(case_run_dir / "case_status.json", result.to_dict())
            return result
        except Exception as exc:
            result = CaseRunSummary(
                input_path=input_identity,
                status="failed",
                run_dir=str(case_run_dir),
                duration_seconds=perf_counter() - started,
                input_fingerprint=input_fingerprint,
                case_id=(final_case.basic_info.case_id if final_case else ""),
                event_count=(len(final_case.events) if final_case else 0),
                relationship_count=(
                    len(final_case.relationships) if final_case else 0
                ),
                library_status=("failed" if final_case is not None else ""),
                error_type=type(exc).__name__,
                error=str(exc),
            )
            dump_json(case_run_dir / "case_status.json", result.to_dict())
            return result
        finally:
            worker.close()

    def _resume_case_summary(
        self,
        input_path: Path | CsvCaseInput,
        fingerprint: str,
        previous: Dict[str, Any],
        case_library: CaseLibrary | None,
        library_on_conflict: ConflictMode,
    ) -> CaseRunSummary:
        """Backfill a completed case into the library without rerunning extraction."""

        run_dir = Path(str(previous.get("run_dir") or ""))
        try:
            library_result = (
                case_library.add(
                    run_dir / "final_case.json",
                    on_conflict=library_on_conflict,
                )
                if case_library is not None
                else None
            )
            return CaseRunSummary(
                input_path=case_input_identity(input_path),
                status="skipped",
                run_dir=str(run_dir),
                duration_seconds=0.0,
                input_fingerprint=fingerprint,
                case_id=str(previous.get("case_id") or ""),
                event_count=int(previous.get("event_count") or 0),
                relationship_count=int(previous.get("relationship_count") or 0),
                library_status=(library_result.status if library_result else ""),
                library_path=(library_result.library_path if library_result else ""),
            )
        except Exception as exc:
            result = CaseRunSummary(
                input_path=case_input_identity(input_path),
                status="failed",
                run_dir=str(run_dir),
                duration_seconds=0.0,
                input_fingerprint=fingerprint,
                case_id=str(previous.get("case_id") or ""),
                event_count=int(previous.get("event_count") or 0),
                relationship_count=int(previous.get("relationship_count") or 0),
                library_status="failed",
                error_type=type(exc).__name__,
                error=str(exc),
            )
            dump_json(run_dir / "case_status.json", result.to_dict())
            return result

    def _configuration_fingerprint(self) -> str:
        payload = {
            "version": self.FINGERPRINT_VERSION,
            "risk_threshold": self.event_extractor.risk_threshold,
            "dedup_threshold": self.event_extractor.dedup_threshold,
            "chat_model": self.llm_client.chat_model,
            "llm_enabled": self.llm_client.enabled,
            "llm_chat_available": self.llm_client.chat_available,
            "embedding_model": self.llm_client.embedding_model,
            "embedding_available": self.llm_client.embedding_available,
            "llm_base_url": self.llm_client.base_url,
            "llm_temperature": self.llm_client.temperature,
            "llm_top_p": self.llm_client.top_p,
            "llm_seed": self.llm_client.seed,
            "corenlp_url": self.coreference_resolver.corenlp_url,
            "corenlp_enabled": self.coreference_resolver.enabled,
            "corenlp_version": self.coreference_resolver.corenlp_version,
            "coreference_threshold": self.coreference_resolver.auto_threshold,
            "coreference_review_threshold": (
                self.coreference_resolver.review_threshold
            ),
            "knowledge_base_backend": self.event_extractor.knowledge_base_backend,
            "knowledge_base_reference": self.event_extractor.knowledge_base_reference,
            "knowledge_base_version": self.event_extractor.knowledge_repository.version,
        }
        digest = hashlib.sha256(
            json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")
        )
        for path in [
            self.kb_path,
            *sorted(self.prompt_dir.glob("*.txt")),
        ]:
            digest.update(path.name.encode("utf-8"))
            if path.is_file():
                digest.update(path.read_bytes())
        return digest.hexdigest()

    def _input_fingerprint(
        self,
        input_path: Path | CsvCaseInput,
        config_fingerprint: str,
    ) -> str:
        digest = hashlib.sha256(config_fingerprint.encode("ascii"))
        if isinstance(input_path, CsvCaseInput):
            digest.update(input_path.identity.encode("utf-8"))
            digest.update(
                json.dumps(
                    input_path.values,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            )
            return digest.hexdigest()
        if input_path.is_file():
            files = (input_path,)
        else:
            files = tuple(
                input_path / name
                for name in self.CASE_INPUT_FILE_NAMES
                if (input_path / name).is_file()
            )
        for path in files:
            digest.update(path.name.encode("utf-8"))
            digest.update(path.read_bytes())
        return digest.hexdigest()

    def _resolve_resume_batch_dir(
        self,
        batch_dir: Path | None,
        input_paths: Tuple[Path | CsvCaseInput, ...],
    ) -> Path:
        if batch_dir is not None:
            resolved = Path(batch_dir)
            if not resolved.exists():
                return resolved
            if not (
                (resolved / "batch_summary.json").is_file()
                or (resolved / "batch_manifest.json").is_file()
            ):
                if any(resolved.iterdir()):
                    raise FileNotFoundError(
                        f"目录不是可续跑批次且非空: {resolved}"
                    )
            return resolved
        candidates = sorted(
            (
                path.parent
                for path in self.output_root.glob("batch_*/batch_summary.json")
            ),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        expected_paths = [case_input_identity(path) for path in input_paths]
        for candidate in candidates:
            manifest = load_json(candidate / "batch_manifest.json", default={}) or {}
            previous_paths = manifest.get("input_paths") or []
            if not previous_paths:
                summary = load_json(
                    candidate / "batch_summary.json",
                    default={},
                ) or {}
                previous_paths = [
                    str(item["input_path"])
                    for item in (summary.get("results") or [])
                    if isinstance(item, dict) and item.get("input_path")
                ]
            if previous_paths == expected_paths:
                return candidate
        raise FileNotFoundError(
            f"输出目录中没有输入列表匹配的可续跑批次: {self.output_root}"
        )

    def _load_previous_results(self, batch_dir: Path) -> Dict[str, Dict[str, Any]]:
        payload = load_json(batch_dir / "batch_summary.json", default={}) or {}
        previous = {
            str(item.get("input_path")): item
            for item in (payload.get("results") or [])
            if isinstance(item, dict) and item.get("input_path")
        }
        # A process can be interrupted after one case wrote case_status.json but
        # before the coordinator copied that result into batch_summary.json.
        # Recover those completed cases as well so the last finished case is not
        # needlessly extracted again.
        for status_path in batch_dir.glob("*/case_status.json"):
            status = load_json(status_path, default={}) or {}
            input_path = str(status.get("input_path") or "")
            final_path = status_path.parent / "final_case.json"
            if (
                input_path
                and status.get("status") == "running"
                and final_path.is_file()
            ):
                try:
                    recovered_case = IllegalBehaviorCase.model_validate(
                        load_json(final_path, default={}) or {}
                    )
                except Exception:
                    pass
                else:
                    status = {
                        **status,
                        "status": "success",
                        "run_dir": str(status_path.parent),
                        "case_id": recovered_case.basic_info.case_id,
                        "event_count": len(recovered_case.events),
                        "relationship_count": len(recovered_case.relationships),
                        "duration_seconds": 0.0,
                    }
                    dump_json(status_path, status)
            if input_path and status.get("status") in {"success", "skipped"}:
                previous[input_path] = status
        return previous

    def _can_resume(
        self,
        previous: Dict[str, Any] | None,
        fingerprint: str,
    ) -> bool:
        if not previous:
            return False
        run_dir = Path(str(previous.get("run_dir") or ""))
        return bool(
            previous.get("status") in {"success", "skipped"}
            and previous.get("input_fingerprint") == fingerprint
            and (run_dir / "final_case.json").is_file()
        )

    def _case_run_dir(
        self,
        batch_dir: Path,
        index: int,
        input_path: Path | CsvCaseInput,
        fingerprint: str,
        previous: Dict[str, Any] | None,
    ) -> Path:
        if (
            previous
            and previous.get("input_fingerprint") == fingerprint
            and previous.get("run_dir")
        ):
            return Path(str(previous["run_dir"]))
        source_name = case_input_name(input_path)
        safe_name = re.sub(r"[^0-9A-Za-z._-]+", "_", source_name).strip("._")
        return batch_dir / f"{index:04d}_{safe_name or 'case'}_{fingerprint[:8]}"

    def _write_batch_progress(
        self,
        batch_dir: Path,
        results: Dict[int, CaseRunSummary],
        total_case_count: int,
        worker_count: int,
        config_fingerprint: str,
        started: float,
        status: str,
    ) -> None:
        ordered = [results[index] for index in sorted(results)]
        failure_count = sum(item.status == "failed" for item in ordered)
        skipped_count = sum(item.status == "skipped" for item in ordered)
        success_count = sum(
            item.status in {"success", "skipped"} for item in ordered
        )
        dump_json(
            batch_dir / "batch_summary.json",
            {
                "status": status,
                "case_count": total_case_count,
                "completed_count": len(ordered),
                "pending_count": total_case_count - len(ordered),
                "success_count": success_count,
                "failure_count": failure_count,
                "skipped_count": skipped_count,
                "processed_count": len(ordered) - skipped_count,
                "max_workers": worker_count,
                "duration_seconds": round(perf_counter() - started, 6),
                "config_fingerprint": config_fingerprint,
                "service_statistics": self.service_controller.snapshot(),
                "results": [item.to_dict() for item in ordered],
            },
        )

    def _new_worker(self) -> "BankCaseWorkflow":
        return type(self)(**self._constructor_options)

    def close(self) -> None:
        close = getattr(self.llm_client, "close", None)
        if callable(close):
            close()

    def _save_step(self, run_dir: Path, step_name: str, state: Dict[str, Any]) -> None:
        snapshots = {
            "00_input": [
                "input_dir",
                "input_file",
                "basic_info",
                "customers",
                "accounts",
                "other_entities",
                "evidences",
                "transaction_features",
                "analysis_texts",
                "llm_status",
            ],
            "01_structured_processing": [
                "basic_info",
                "customers",
                "accounts",
                "enum_mapping_report",
                "structured_backfill_report",
                "structured_result",
                "processed_transaction_features",
                "transaction_processing_report",
                "concurrency_report",
            ],
            "02_text_processing": [
                "text_cleaning_report",
                "preprocessed_texts",
                "structured_sections",
                "text_chunks",
                "transaction_processing_report",
                "concurrency_report",
            ],
            "03_coreference_resolution": [
                "coreference_engine_status",
                "coreference_report",
                "coreference_chains",
                "coreference_resolutions",
                "coreference_standalone_entities",
                "coreference_review_queue",
                "coreference_review_calls",
                "coreference_resolved_texts",
                "coreference_resolved_sections",
            ],
            "04_entity_enhancement": [
                "entity_mapping_report",
                "entity_enhanced_texts",
                "entity_enhanced_sections",
            ],
            "05_context_enhancement": [
                "context_metadata",
                "context_metadata_text",
                "context_enhanced_texts",
                "context_enhanced_sections",
            ],
            "06_event_extraction": [
                "event_candidates",
                "knowledge_base_recall_results",
                "event_extraction_calls",
                "event_extraction_mode",
                "event_deduplication_report",
                "events",
            ],
            "07_relationship_extraction": [
                "relationship_extraction_order",
                "relationship_extraction_calls",
                "relationship_candidates",
                "relationship_candidates_by_category",
                "relationship_candidates_by_type",
                "relationship_deduplication_report",
                "relationships",
            ],
            "08_merge": ["final_result"],
        }
        keys = snapshots.get(step_name, [])
        payload = {key: state.get(key) for key in keys}
        if step_name == "01_structured_processing":
            structured_result = state.get("structured_result")
            if structured_result is not None:
                public_structured = structured_result.model_dump()
                payload["customers"] = public_structured.get("customers") or []
                payload["accounts"] = public_structured.get("accounts") or []
        if step_name == "06_event_extraction":
            payload["events"] = self.merger.public_events(state)
        if step_name == "07_relationship_extraction":
            payload["relationships"] = self.merger.public_relationships(state)
        dump_json(run_dir / f"{step_name}.json", payload)
