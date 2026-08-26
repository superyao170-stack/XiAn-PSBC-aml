"""CLI entry for the Xi'an Postal Savings Bank data graph workflow."""

from __future__ import annotations

import argparse
import hashlib
import re
from datetime import datetime
from pathlib import Path

from datagraph_bank.case_library import CaseLibrary
from datagraph_bank.io_utils import discover_case_inputs
from datagraph_bank.workflow import BankCaseWorkflow


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _automatic_batch_dir(output_dir: Path, input_path: Path) -> Path:
    """Return a stable checkpoint directory for one logical input package."""

    source = Path(input_path).expanduser().absolute()
    safe_name = re.sub(r"[^0-9A-Za-z._-]+", "_", source.stem).strip("._")
    source_hash = hashlib.sha256(str(source).encode("utf-8")).hexdigest()[:10]
    return Path(output_dir) / f"batch_{safe_name or 'cases'}_{source_hash}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the structured + unstructured bank case graph workflow."
    )
    input_group = parser.add_mutually_exclusive_group()
    input_group.add_argument(
        "--input-dir",
        "--input-file",
        "--input-csv",
        dest="input_path",
        type=Path,
        default=None,
        help=(
            "案例输入：生产 CSV 文件（每行一例，包含 basic_info、customers、"
            "analysis_texts 三列）；也兼容现有案例目录和完整 JSON 文件。"
        ),
    )
    input_group.add_argument(
        "--input-root",
        type=Path,
        default=None,
        help="批量案例根目录；并发处理其下所有包含案例信息和分析文本的子目录。",
    )
    parser.add_argument(
        "--kb-path",
        type=Path,
        default=PROJECT_ROOT / "data" / "kb" / "risk_event_knowledge_base.json",
        help="Risk event knowledge base JSON path.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=PROJECT_ROOT / "output",
        help="Directory used to store workflow outputs.",
    )
    parser.add_argument(
        "--case-library-dir",
        type=Path,
        default=None,
        help=(
            "可选：将成功抽取的 final_case.json 注册到指定案例库。"
            "构建历史600案例时启用；新增案例应先匹配、再由相似度脚本入库。"
        ),
    )
    parser.add_argument(
        "--library-on-conflict",
        choices=("error", "skip", "replace"),
        default="error",
        help="注册案例库时遇到相同 case_id、不同内容的处理方式。",
    )
    parser.add_argument(
        "--case-workers",
        "--max-workers",
        dest="case_workers",
        type=int,
        default=4,
        help="批量案例最大并发数，默认 4。--max-workers 为兼容别名。",
    )
    parser.add_argument(
        "--llm-concurrency",
        type=int,
        default=2,
        help="所有案例共享的 LLM API 最大同时请求数，默认 2。",
    )
    parser.add_argument(
        "--corenlp-concurrency",
        type=int,
        default=2,
        help="所有案例共享的本地 CoreNLP 最大同时请求数，默认 2。",
    )
    parser.add_argument(
        "--service-max-attempts",
        type=int,
        default=3,
        help="LLM/CoreNLP 临时故障最大尝试次数，默认 3。",
    )
    parser.add_argument(
        "--service-retry-delay",
        type=float,
        default=0.5,
        help="本地服务指数退避初始秒数，默认 0.5。",
    )
    parser.add_argument(
        "--corenlp-timeout",
        type=float,
        default=120.0,
        help="单次 CoreNLP 请求超时秒数，默认 120。",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="兼容选项：续跑旧版时间戳批次；新版批处理默认自动断点续跑。",
    )
    parser.add_argument(
        "--resume-batch-dir",
        type=Path,
        default=None,
        help="续跑指定批次目录，并自动启用 --resume。",
    )
    parser.add_argument(
        "--fresh-run",
        action="store_true",
        help="忽略自动续跑目录并新建一个时间戳批次，不删除旧结果。",
    )
    parser.add_argument(
        "--risk-threshold",
        type=float,
        default=0.12,
        help="Reference similarity threshold for knowledge-base recall and local review.",
    )
    parser.add_argument(
        "--dedup-threshold",
        type=float,
        default=0.82,
        help="Event text similarity threshold for deterministic deduplication.",
    )
    parser.add_argument(
        "--disable-llm",
        action="store_true",
        help="Disable DeepSeek calls and use deterministic local fallback only.",
    )
    parser.add_argument(
        "--chat-model",
        default=None,
        help="DeepSeek 对话模型；默认读取 Worker 环境中的 DEEPSEEK_CHAT_MODEL。",
    )
    parser.add_argument(
        "--embedding-model",
        default=None,
        help="可选 embedding 模型；默认读取 datagraph_bank/llm.py 顶部常量。",
    )
    parser.add_argument(
        "--prompt-dir",
        type=Path,
        default=PROJECT_ROOT / "data" / "prompts",
        help="Directory containing event and category-specific relationship prompts.",
    )
    parser.add_argument(
        "--corenlp-url",
        default=None,
        help="CoreNLP Server URL，默认 http://localhost:9000。",
    )
    parser.add_argument(
        "--coreference-threshold",
        type=float,
        default=0.80,
        help=(
            "Coreference confidence above this value is applied directly; "
            "the boundary value itself is reviewed by DeepSeek."
        ),
    )
    parser.add_argument(
        "--coreference-review-threshold",
        type=float,
        default=0.55,
        help=(
            "Inclusive lower bound for DeepSeek review; lower-confidence "
            "references are retained as standalone entities."
        ),
    )
    parser.add_argument(
        "--disable-corenlp",
        action="store_true",
        help="Disable CoreNLP calls and use conservative local candidate generation.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    positive_options = {
        "--case-workers": args.case_workers,
        "--llm-concurrency": args.llm_concurrency,
        "--corenlp-concurrency": args.corenlp_concurrency,
        "--service-max-attempts": args.service_max_attempts,
        "--corenlp-timeout": args.corenlp_timeout,
    }
    for option_name, value in positive_options.items():
        if value <= 0:
            raise SystemExit(f"{option_name} 必须大于 0")
    if args.service_retry_delay < 0:
        raise SystemExit("--service-retry-delay 不能小于 0")
    if args.fresh_run and (args.resume or args.resume_batch_dir is not None):
        raise SystemExit("--fresh-run 不能与 --resume/--resume-batch-dir 同时使用")
    csv_input = bool(
        args.input_path is not None
        and args.input_path.suffix.lower() == ".csv"
    )
    if (
        (args.resume or args.resume_batch_dir is not None)
        and args.input_root is None
        and not csv_input
    ):
        raise SystemExit(
            "--resume/--resume-batch-dir 只能与 --input-root 或 CSV 输入一起使用"
        )
    workflow = BankCaseWorkflow(
        kb_path=args.kb_path,
        output_root=args.output_dir,
        risk_threshold=args.risk_threshold,
        dedup_threshold=args.dedup_threshold,
        llm_enabled=not args.disable_llm,
        chat_model=args.chat_model,
        embedding_model=args.embedding_model,
        prompt_dir=args.prompt_dir,
        corenlp_url=args.corenlp_url,
        coreference_threshold=args.coreference_threshold,
        coreference_review_threshold=args.coreference_review_threshold,
        corenlp_enabled=not args.disable_corenlp,
        llm_concurrency=args.llm_concurrency,
        corenlp_concurrency=args.corenlp_concurrency,
        service_max_attempts=args.service_max_attempts,
        service_retry_delay=args.service_retry_delay,
        corenlp_timeout=args.corenlp_timeout,
    )
    try:
        if args.input_root is not None or csv_input:
            batch_input = args.input_path if csv_input else args.input_root
            assert batch_input is not None
            if args.resume_batch_dir is not None:
                batch_dir = args.resume_batch_dir
                resume = True
            elif args.resume:
                batch_dir = None
                resume = True
            elif args.fresh_run:
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
                batch_dir = args.output_dir / f"batch_{timestamp}"
                resume = False
            else:
                batch_dir = _automatic_batch_dir(args.output_dir, batch_input)
                resume = True
            case_library = (
                CaseLibrary(args.case_library_dir)
                if args.case_library_dir is not None
                else None
            )
            if csv_input:
                batch_result = workflow.run_csv_batch(
                    args.input_path,
                    max_workers=args.case_workers,
                    resume=resume,
                    batch_dir=batch_dir,
                    case_library=case_library,
                    library_on_conflict=args.library_on_conflict,
                )
            else:
                batch_result = workflow.run_batch(
                    discover_case_inputs(args.input_root),
                    max_workers=args.case_workers,
                    resume=resume,
                    batch_dir=batch_dir,
                    case_library=case_library,
                    library_on_conflict=args.library_on_conflict,
                )
            print("批量工作流执行完成")
            print(f"案例总数: {len(batch_result.results)}")
            print(f"成功数: {batch_result.success_count}")
            print(f"失败数: {batch_result.failure_count}")
            print(f"续跑跳过数: {batch_result.skipped_count}")
            print(f"输出目录: {batch_result.run_dir}")
            for item in batch_result.results:
                if item.status == "failed":
                    print(f"失败案例: {item.input_path} - {item.error_type}: {item.error}")
            if case_library is not None:
                library_manifest = case_library.manifest()
                added_count = sum(
                    item.library_status == "added" for item in batch_result.results
                )
                unchanged_count = sum(
                    item.library_status == "unchanged"
                    for item in batch_result.results
                )
                print(f"案例库目录: {case_library.root}")
                print(f"本批新增数: {added_count}")
                print(f"本批已存在数: {unchanged_count}")
                print(f"案例库总数: {library_manifest['case_count']}")
            return 1 if batch_result.failure_count else 0

        input_path = args.input_path or PROJECT_ROOT / "data" / "cases" / "case2"
        final_case, run_dir = workflow.run(input_path)

        print("工作流执行完成")
        print(f"案例ID: {final_case.basic_info.case_id}")
        print(f"客户数: {len(final_case.customers)}")
        print(f"账户数: {len(final_case.accounts)}")
        print(f"事件数: {len(final_case.events)}")
        print(f"关系数: {len(final_case.relationships)}")
        print(f"输出目录: {run_dir}")
        if args.case_library_dir is not None:
            library_result = CaseLibrary(args.case_library_dir).add(
                run_dir / "final_case.json",
                on_conflict=args.library_on_conflict,
            )
            print(f"案例库目录: {Path(args.case_library_dir).resolve()}")
            print(f"入库状态: {library_result.status}")
        return 0
    finally:
        workflow.close()


if __name__ == "__main__":
    raise SystemExit(main())
