from __future__ import annotations

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


WORKER_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))

from aml_analysis_workflow.errors import ModelResponseError
from datagraph_bank.governed_analysis import GovernedAnalysisTextGenerator


def _case(*, case_name: str) -> SimpleNamespace:
    return SimpleNamespace(
        basic_info={"case_name": case_name},
        customers=[],
        transaction_features={},
        raw_transaction_features=None,
    )


def _generated(text: str) -> SimpleNamespace:
    return SimpleNamespace(analysis_text1=text, analysis_text2="正式分析结论")


class ReportStyleValidationTests(unittest.TestCase):
    def test_allows_test_marker_that_is_part_of_input_fact(self) -> None:
        GovernedAnalysisTextGenerator._validate_report_style(
            _generated("涉及测试客户甲，需进一步核验。"),
            _case(case_name="测试客户甲异常资金案例"),
        )

    def test_allows_test_and_mock_markers_introduced_by_model(self) -> None:
        GovernedAnalysisTextGenerator._validate_report_style(
            _generated("本报告用于测试/mock。"),
            _case(case_name="张某异常资金案例"),
        )

    def test_rejects_other_informal_marker_introduced_only_by_model(self) -> None:
        with self.assertRaisesRegex(ModelResponseError, "模拟"):
            GovernedAnalysisTextGenerator._validate_report_style(
                _generated("本报告仅用于模拟。"),
                _case(case_name="张某异常资金案例"),
            )


if __name__ == "__main__":
    unittest.main()
