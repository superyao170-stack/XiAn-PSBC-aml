#!/usr/bin/env python3
"""End-to-end CSV pipelines for historical and newly arrived cases."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Sequence

from case_similarity import (
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_RERANKER_MODEL,
    EMBEDDING_RECALL_THRESHOLD,
    MIN_EVENT_COUNT_RATIO,
    MIN_EVENT_OVERLAP,
    MIN_SHARED_EVENT_TYPES,
    TOP_RESULT_COUNT,
    load_case,
    run_batch_queries,
)
from aml_analysis_workflow.csv_input import read_cases as read_analysis_csv_cases
from aml_analysis_workflow.runner import run_csv_generation, run_xlsx_generation
from aml_analysis_workflow.xlsx_input import read_xlsx_cases
from datagraph_bank.analysis_generation import validate_analysis_texts
from datagraph_bank.case_library import CaseLibrary
from datagraph_bank.governed_analysis import GovernedAnalysisTextGenerator
from datagraph_bank.io_utils import (
    CsvCaseInput,
    csv_case_id,
    deduplicate_csv_case_inputs,
    dump_json,
    load_csv_case_inputs,
)
from datagraph_bank.llm import DeepSeekLLMClient
from datagraph_bank.workflow import BankCaseWorkflow


PROJECT_ROOT = Path(__file__).resolve().parent
DEFAULT_OLD_CSV = (
    PROJECT_ROOT
    / "data"
    / "test_csv"
    / "medical_corruption_demo"
    / "historical_medical_and_controls.csv"
)
DEFAULT_NEW_CSV = (
    PROJECT_ROOT
    / "data"
    / "test_csv"
    / "medical_corruption_demo"
    / "new_medical_cases.csv"
)
DEFAULT_LIBRARY_ROOT = PROJECT_ROOT / "data" / "case_library"
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / "output" / "medical_corruption_demo"


def run_old_csv_pipeline(
    input_csv: Path,
    workflow: BankCaseWorkflow,
    library: CaseLibrary,
    max_workers: int = 4,
    batch_dir: Path | None = None,
    resume: bool = False,
) -> Dict[str, Any]:
    """Extract one historical CSV batch and upsert each current case."""

    records = load_csv_case_inputs(input_csv)
    deduplication = deduplicate_csv_case_inputs(records)
    batch_result = workflow.run_batch(
        deduplication.records,
        max_workers=max_workers,
        resume=resume,
        batch_dir=batch_dir,
        case_library=library,
        library_on_conflict="replace",
    )
    library_status_counts = {
        status: sum(item.library_status == status for item in batch_result.results)
        for status in ("added", "replaced", "unchanged")
    }
    summary = {
        "status": "success" if not batch_result.failure_count else "partial_failure",
        "pipeline": "historical_csv_to_case_library",
        "input_csv": str(Path(input_csv).expanduser().absolute()),
        "extraction_batch_dir": str(batch_result.run_dir.resolve()),
        "case_library_root": str(library.root),
        "input_row_count": len(records),
        "extracted_case_count": len(deduplication.records),
        "success_count": batch_result.success_count,
        "failure_count": batch_result.failure_count,
        "deduplication": deduplication.to_dict(),
        "library_conflict_policy": "replace",
        "library_status_counts": library_status_counts,
        "case_library_count": library.manifest()["case_count"],
        "results": [item.to_dict() for item in batch_result.results],
    }
    dump_json(batch_result.run_dir / "old_pipeline_summary.json", summary)
    return summary


def successful_final_case_paths(summary: Dict[str, Any]) -> list[Path]:
    """Return this historical batch's completed final cases in input order."""

    paths: list[Path] = []
    for item in summary.get("results") or []:
        if item.get("status") not in {"success", "skipped"}:
            continue
        final_case_path = Path(str(item.get("run_dir") or "")) / "final_case.json"
        if final_case_path.is_file():
            paths.append(final_case_path.resolve())
    return paths


def current_history_library_paths(
    input_csv: Path,
    library: CaseLibrary,
) -> list[Path]:
    """Resolve only the case IDs declared by the current historical CSV."""

    records = load_csv_case_inputs(input_csv)
    current_records = deduplicate_csv_case_inputs(records).records
    current_case_ids = {csv_case_id(record) for record in current_records}
    manifest = library.manifest()
    paths_by_id = {
        str(item.get("case_id") or ""): (
            library.root / str(item.get("relative_path") or "")
        ).resolve()
        for item in manifest.get("cases") or []
    }
    missing = sorted(case_id for case_id in current_case_ids if case_id not in paths_by_id)
    if missing:
        raise ValueError(
            "本批历史案例尚未全部写入原案例库: " + ", ".join(missing)
        )
    return [
        paths_by_id[csv_case_id(record)]
        for record in current_records
    ]


def _similarity_arguments(query_root: Path, output_dir: Path) -> argparse.Namespace:
    return argparse.Namespace(
        query_root=str(query_root),
        batch_output_dir=str(output_dir),
        top_k=TOP_RESULT_COUNT,
        retrieval_k=25,
        min_shared_event_types=MIN_SHARED_EVENT_TYPES,
        min_event_overlap=MIN_EVENT_OVERLAP,
        min_event_count_ratio=MIN_EVENT_COUNT_RATIO,
        no_add_to_library=False,
        library_on_conflict="replace",
        rolling_library=False,
        embedding_model=DEFAULT_EMBEDDING_MODEL,
        embedding_device="cpu",
        embedding_local_files_only=False,
        embedding_recall_threshold=EMBEDDING_RECALL_THRESHOLD,
        reranker_model=DEFAULT_RERANKER_MODEL,
        reranker_device="cpu",
        reranker_local_files_only=False,
        reranker_max_length=512,
    )


def run_new_batch_pipeline(
    input_batch: Path,
    workflow: BankCaseWorkflow,
    report_generator: GovernedAnalysisTextGenerator | None,
    library: CaseLibrary,
    run_root: Path,
    case_workers: int = 4,
    analysis_workers: int = 2,
    generate_reports: bool = True,
    resume: bool = True,
    similarity_candidate_paths: Sequence[Path] | None = None,
) -> Dict[str, Any]:
    """Generate governed text, extract it, match history, then store new cases."""

    input_path = Path(input_batch)
    suffix = input_path.suffix.lower()
    if suffix == ".csv":
        records = read_analysis_csv_cases(input_path)
        generation_function = run_csv_generation
        generation_input_name = "input_csv"
    elif suffix == ".xlsx":
        records = read_xlsx_cases(input_path)
        generation_function = run_xlsx_generation
        generation_input_name = "input_xlsx"
    else:
        raise ValueError("新增案例批处理文件必须为 .csv 或 .xlsx")
    root = Path(run_root)
    root.mkdir(parents=True, exist_ok=True)

    if not generate_reports:
        raise ValueError("新增案例不含分析文本，不能跳过分析文本生成")
    if report_generator is None:
        raise ValueError("新增案例分析文本生成必须提供 report_generator")
    generated_summary = generation_function(
        **{
            generation_input_name: input_path,
            "batch_output_dir": root / "generated_analysis",
            "generator": report_generator,
            "max_workers": analysis_workers,
        }
    )

    generated_by_row = {
        int(item["input_row_number"]): item
        for item in generated_summary["results"]
        if item.get("status") in {"success", "skipped"}
    }
    extraction_inputs: list[CsvCaseInput] = []
    extraction_input_manifest = []
    for record in records:
        assert record.row_number is not None
        generated_result = generated_by_row.get(record.row_number)
        if generated_result is None:
            continue
        analysis_path = Path(str(generated_result["output_path"]))
        analysis_texts = json.loads(analysis_path.read_text(encoding="utf-8-sig"))
        validate_analysis_texts(analysis_texts)
        extraction_inputs.append(
            CsvCaseInput(
                csv_path=record.csv_path,
                row_number=record.row_number,
                values={
                    "basic_info": record.basic_info,
                    "customers": record.customers,
                    "transaction_features": (
                        record.raw_transaction_features
                        or record.transaction_features
                    ),
                    "analysis_texts": analysis_texts,
                },
            )
        )
        extraction_input_manifest.append(
            {
                "input_path": record.source_id,
                "case_id": generated_result.get("case_id") or "",
                "generated_analysis_path": str(analysis_path.resolve()),
                "framework_extraction_analysis_source": "generated_analysis",
            }
        )
    if not extraction_inputs:
        raise ValueError("没有成功生成可用于框架抽取的分析文本")
    dump_json(root / "extraction_input_manifest.json", extraction_input_manifest)

    # Do not pass the case library here. New final cases enter the library only
    # after matching against the history snapshot captured below.
    extraction_result = workflow.run_batch(
        extraction_inputs,
        max_workers=case_workers,
        resume=resume,
        batch_dir=root / "extraction",
        case_library=None,
    )

    similarity_dir = root / "similarity"
    similarity_summary: Dict[str, Any]
    try:
        candidate_paths = (
            [Path(path).expanduser().absolute() for path in similarity_candidate_paths]
            if similarity_candidate_paths is not None
            else library.case_paths()
        )
        history_candidates = [(path, load_case(path)) for path in candidate_paths]
        if not history_candidates:
            raise ValueError("相似度匹配候选案例为空，请先执行历史案例建库批处理")
        candidate_case_ids = [graph.case_id for _, graph in history_candidates]
        duplicate_candidate_ids = sorted(
            {
                case_id
                for case_id in candidate_case_ids
                if candidate_case_ids.count(case_id) > 1
            }
        )
        if duplicate_candidate_ids:
            raise ValueError(
                "相似度匹配候选存在重复 case_id: "
                + ", ".join(duplicate_candidate_ids)
            )
        candidate_scope = (
            "explicit_current_historical_batch"
            if similarity_candidate_paths is not None
            else "original_case_library_snapshot"
        )
        dump_json(
            root / "similarity_candidate_manifest.json",
            {
                "scope": candidate_scope,
                "case_count": len(history_candidates),
                "excluded_preexisting_library_cases": (
                    similarity_candidate_paths is not None
                ),
                "cases": [
                    {
                        "case_id": graph.case_id,
                        "path": str(path.resolve()),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    }
                    for path, graph in history_candidates
                ],
            },
        )
        similarity_summary = run_batch_queries(
            _similarity_arguments(extraction_result.run_dir, similarity_dir),
            library,
            history_candidates,
        )
    except Exception as exc:
        similarity_summary = {
            "status": "failed",
            "query_root": str(extraction_result.run_dir.resolve()),
            "output_dir": str(similarity_dir.resolve()),
            "error_type": type(exc).__name__,
            "error": str(exc),
            "success_count": 0,
            "failure_count": extraction_result.success_count,
            "results": [],
        }
        dump_json(similarity_dir / "batch_similarity_summary.json", similarity_summary)

    stage_statuses = (
        generated_summary["status"],
        "success" if not extraction_result.failure_count else "partial_failure",
        similarity_summary["status"],
    )
    summary = {
        "status": (
            "success"
            if all(item in {"success", "skipped"} for item in stage_statuses)
            else "partial_failure"
        ),
        "pipeline": "new_csv_report_extract_match_and_store",
        "input_path": str(input_path.expanduser().absolute()),
        "run_root": str(root.resolve()),
        "case_library_root": str(library.root),
        "input_row_count": len(records),
        "data_flow_contract": {
            "case_information_source": ["basic_info", "customers"],
            "transaction_feature_source": "transaction_features",
            "analysis_generation_algorithm": "aml-analysis-workflow",
            "analysis_generation_input_fields": [
                "basic_info",
                "customers",
                "transaction_features",
            ],
            "input_analysis_text_present": False,
            "generated_report_usage": "framework_extraction_input",
            "framework_extraction_analysis_source": (
                "generated_analysis/analysis_texts.json"
            ),
            "similarity_input": "extraction/final_case.json",
            "similarity_processing": "sequential_one_new_case_at_a_time",
            "similarity_history": (
                "fixed_current_historical_batch_snapshot"
                if similarity_candidate_paths is not None
                else "fixed_case_library_snapshot"
            ),
            "preexisting_library_cases_in_similarity": (
                similarity_candidate_paths is None
            ),
            "similarity_candidate_manifest": (
                "similarity_candidate_manifest.json"
            ),
            "similarity_output": "top_at_5_with_matched_nodes_edges_and_visualization",
            "library_update_timing": "after_each_case_similarity_result_is_written",
            "library_conflict_policy": "replace",
        },
        "generated_analysis": generated_summary,
        "extraction": {
            "status": (
                "success" if not extraction_result.failure_count else "partial_failure"
            ),
            "batch_dir": str(extraction_result.run_dir.resolve()),
            **extraction_result.to_dict(),
        },
        "similarity": similarity_summary,
        "case_library_count": library.manifest()["case_count"],
    }
    dump_json(root / "new_pipeline_summary.json", summary)
    return summary


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="运行 old 历史建库或 new 新增案例 CSV/XLSX 全链路。"
    )
    parser.add_argument("mode", choices=("old", "new", "all"))
    parser.add_argument("--old-csv", type=Path, default=DEFAULT_OLD_CSV)
    parser.add_argument(
        "--new-batch",
        "--new-csv",
        dest="new_batch",
        type=Path,
        default=DEFAULT_NEW_CSV,
        help="新增案例三字段 CSV 或 XLSX",
    )
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--case-workers", type=int, default=4)
    parser.add_argument("--analysis-workers", type=int, default=2)
    parser.add_argument("--chat-model", default=None)
    parser.add_argument(
        "--fresh-run",
        action="store_true",
        help="新建时间戳任务目录，不复用自动检查点。",
    )
    parser.add_argument(
        "--disable-llm",
        action="store_true",
        help="仅关闭框架抽取中的 LLM；new 的分析文本生成仍真实调用 DeepSeek。",
    )
    parser.add_argument("--disable-corenlp", action="store_true")
    return parser.parse_args(argv)


def _new_workflow(args: argparse.Namespace, output_root: Path) -> BankCaseWorkflow:
    return BankCaseWorkflow(
        kb_path=PROJECT_ROOT / "data" / "kb" / "risk_event_knowledge_base.json",
        output_root=output_root,
        llm_enabled=not args.disable_llm,
        chat_model=args.chat_model,
        corenlp_enabled=not args.disable_corenlp,
    )


def _stable_run_root(output_root: Path, stage: str, input_csv: Path) -> Path:
    source = Path(input_csv).expanduser().absolute()
    safe_name = re.sub(r"[^0-9A-Za-z._-]+", "_", source.stem).strip("._")
    digest = hashlib.sha256(str(source).encode("utf-8")).hexdigest()[:10]
    return Path(output_root) / stage / f"{safe_name or 'cases'}_{digest}"


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.case_workers < 1 or args.analysis_workers < 1:
        raise SystemExit("--case-workers/--analysis-workers 必须大于0")
    library = CaseLibrary(args.library_root)
    failed = False
    current_history_candidates: list[Path] | None = None

    if args.mode in {"old", "all"}:
        workflow = _new_workflow(args, args.output_root / "old")
        old_run_root = _stable_run_root(args.output_root, "old", args.old_csv)
        if args.fresh_run:
            old_run_root = old_run_root.with_name(
                f"{old_run_root.name}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}"
            )
        try:
            old_summary = run_old_csv_pipeline(
                input_csv=args.old_csv,
                workflow=workflow,
                library=library,
                max_workers=args.case_workers,
                batch_dir=old_run_root,
                resume=not args.fresh_run,
            )
        finally:
            workflow.close()
        failed = failed or old_summary["failure_count"] > 0
        current_history_candidates = successful_final_case_paths(old_summary)
        print(f"old 历史案例处理完成: {old_summary['extraction_batch_dir']}")
        print(f"案例库当前总数: {old_summary['case_library_count']}")

    if args.mode in {"new", "all"}:
        if current_history_candidates is None:
            current_history_candidates = current_history_library_paths(
                args.old_csv,
                library,
            )
        workflow = _new_workflow(args, args.output_root / "new")
        new_run_root = _stable_run_root(args.output_root, "new", args.new_batch)
        if args.fresh_run:
            new_run_root = new_run_root.with_name(
                f"{new_run_root.name}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}"
            )
        llm_client = DeepSeekLLMClient(enabled=True, chat_model=args.chat_model)
        try:
            generator = GovernedAnalysisTextGenerator(
                llm_client=llm_client,
                knowledge_base_path=(
                    PROJECT_ROOT / "data" / "kb" / "risk_event_knowledge_base.json"
                ),
            )
            new_summary = run_new_batch_pipeline(
                input_batch=args.new_batch,
                workflow=workflow,
                report_generator=generator,
                library=library,
                run_root=new_run_root,
                case_workers=args.case_workers,
                analysis_workers=args.analysis_workers,
                generate_reports=True,
                resume=not args.fresh_run,
                similarity_candidate_paths=current_history_candidates,
            )
        finally:
            workflow.close()
            llm_client.close()
        failed = failed or new_summary["status"] != "success"
        print(f"new 新增案例全链路完成: {new_summary['run_root']}")
        print(f"案例库当前总数: {new_summary['case_library_count']}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
