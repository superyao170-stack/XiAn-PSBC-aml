from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
from typing import Sequence

from datagraph_bank.governed_analysis import GovernedAnalysisTextGenerator
from datagraph_bank.llm import DeepSeekLLMClient

from .runner import (
    run_csv_generation,
    run_single_generation,
    run_xlsx_generation,
)


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="使用 AML 分段审查工作流生成可疑报告分析文本"
    )
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--input-file", type=Path, help="单案例三字段JSON")
    inputs.add_argument("--input-csv", type=Path, help="三字段多行CSV")
    inputs.add_argument("--input-xlsx", type=Path, help="三字段多行XLSX")
    parser.add_argument("--output-path", type=Path, help="单案例输出JSON")
    parser.add_argument("--output-dir", type=Path, help="批处理输出目录")
    parser.add_argument("--knowledge-base", type=Path, help="风险事件知识库JSON")
    parser.add_argument("--config", type=Path, help="工作流配置JSON")
    parser.add_argument("--prompts-dir", type=Path, help="Prompt目录")
    parser.add_argument("--case-workers", type=int, default=2)
    parser.add_argument("--chat-model")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.case_workers < 1:
        raise SystemExit("--case-workers 必须大于0")
    if args.input_file is not None and args.output_dir is not None:
        raise SystemExit("--output-dir 只能用于CSV或XLSX批处理")
    if args.input_file is None and args.output_path is not None:
        raise SystemExit("--output-path 只能用于单案例JSON")

    llm_client = DeepSeekLLMClient(enabled=True, chat_model=args.chat_model)
    try:
        generator = GovernedAnalysisTextGenerator(
            llm_client=llm_client,
            knowledge_base_path=args.knowledge_base,
            config_path=args.config,
            prompts_dir=args.prompts_dir,
        )
        if args.input_file is not None:
            output_path = args.output_path or (
                args.input_file.expanduser().resolve().parent / "analysis_texts.json"
            )
            result = run_single_generation(
                args.input_file,
                output_path,
                generator,
                overwrite=args.overwrite,
            )
            print(f"案例 {result['case_id']} 分析完成: {result['output_path']}")
            return 0

        input_path = args.input_csv or args.input_xlsx
        assert input_path is not None
        output_dir = args.output_dir or _default_batch_output_dir(input_path)
        function = (
            run_csv_generation if args.input_csv is not None else run_xlsx_generation
        )
        argument_name = "input_csv" if args.input_csv is not None else "input_xlsx"
        summary = function(
            **{
                argument_name: input_path,
                "batch_output_dir": output_dir,
                "generator": generator,
                "max_workers": args.case_workers,
                "overwrite": args.overwrite,
            }
        )
    except (FileExistsError, FileNotFoundError, RuntimeError, ValueError) as exc:
        raise SystemExit(str(exc)) from exc
    finally:
        llm_client.close()

    print(f"案例总数: {summary['case_count']}")
    print(f"成功: {summary['success_count']}，失败: {summary['failure_count']}")
    print(f"输出目录: {summary['batch_output_dir']}")
    return 1 if summary["failure_count"] else 0


def _default_batch_output_dir(input_path: Path) -> Path:
    source = input_path.expanduser().resolve()
    digest = hashlib.sha256(str(source).encode("utf-8")).hexdigest()[:10]
    return PROJECT_ROOT / "output" / f"analysis_{source.stem}_{digest}"
