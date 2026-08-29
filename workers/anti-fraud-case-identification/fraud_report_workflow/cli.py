from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
from typing import Sequence

from .case_input import discover_cases
from .config import WorkflowConfig
from .errors import FraudWorkflowError
from .llm import DeepSeekModel
from .prompt_store import PromptStore
from .report_workflow import FraudReportWorkflow
from .risk_extraction import RiskExtractor
from .rules import FraudRuleLibrary
from .runner import BatchRunner


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="从案例目录提取反欺诈风险事件链并生成可疑报告")
    parser.add_argument("--input-dir", type=Path, required=True, help="单个案例目录或包含多个案例子目录的批量目录")
    parser.add_argument("--output-dir", type=Path, help="输出目录")
    parser.add_argument("--rule-library", type=Path, help="覆盖内置反欺诈风险规则库")
    parser.add_argument("--config", type=Path, help="覆盖默认工作流配置JSON")
    parser.add_argument("--prompts-dir", type=Path, help="覆盖内置Prompt目录")
    parser.add_argument("--case-workers", type=int, default=2)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.case_workers < 1:
        raise SystemExit("case-workers必须大于0")
    try:
        config = WorkflowConfig.load(args.config)
        prompts = PromptStore(args.prompts_dir)
        rules = FraudRuleLibrary.load(args.rule_library)
        model = DeepSeekModel()
        cases = discover_cases(args.input_dir)
        extractor = RiskExtractor(model, prompts, config, rules)
        reporter = FraudReportWorkflow(model, prompts, config)
        output_dir = args.output_dir or Path.cwd() / "output" / f"fraud_batch_{datetime.now():%Y%m%d_%H%M%S}"
        summary = BatchRunner(extractor, reporter).run(cases, output_dir, args.case_workers, args.overwrite)
    except (FraudWorkflowError, FileNotFoundError, ValueError) as exc:
        raise SystemExit(str(exc)) from exc
    print(f"案例总数: {summary['case_count']}")
    print(f"成功: {summary['success_count']}，失败: {summary['failure_count']}")
    print(f"仅风险事件链成功: {summary['risk_chain_only_count']}")
    print(f"输出目录: {Path(output_dir).expanduser().resolve()}")
    return 1 if summary["failure_count"] else 0
