from __future__ import annotations

import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))

import framework_extraction
from framework_extraction import _merge_history_snapshot, load_batch_case_file, run_pipeline


def _basic(case_id: str) -> dict[str, str]:
    return {"case_id": case_id, "case_name": f"案例-{case_id}"}


def _customers(case_id: str) -> list[dict[str, str]]:
    return [{"entity_id": f"CUSTOMER-{case_id}"}]


class BatchInputTests(unittest.TestCase):
    def _write_csv(self, directory: str, name: str, fields: tuple[str, ...], rows: list[dict]) -> Path:
        path = Path(directory) / name
        with path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            for row in rows:
                writer.writerow({key: json.dumps(value, ensure_ascii=False) for key, value in row.items()})
        return path

    def test_historical_csv_reuses_existing_analysis_texts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(directory, "history.csv", (
                "basic_info", "customers", "analysis_texts"
            ), [{
                "basic_info": _basic("CASE-H-1"),
                "customers": _customers("H-1"),
                "analysis_texts": {"analysis_text1": "已有历史可疑报告"},
            }])

            records = load_batch_case_file(source, "HISTORICAL")
            validation = run_pipeline({
                "sourcePath": str(source),
                "processingMode": "BATCH",
                "recognitionMode": "HISTORICAL",
                "validateOnly": True,
            })

            self.assertEqual("已有历史可疑报告", records[0]["analysis_texts"]["analysis_text1"])
            self.assertEqual(1, validation["caseCount"])
            self.assertEqual(["CASE-H-1"], validation["caseIds"])

    def test_historical_csv_accepts_legacy_analysis_text_alias(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(directory, "history.csv", (
                "basic_info", "customers", "analysis_text"
            ), [{
                "basic_info": _basic("CASE-H-2"),
                "customers": _customers("H-2"),
                "analysis_text": "已有单字段可疑报告",
            }])

            records = load_batch_case_file(source, "HISTORICAL")

            self.assertEqual("已有单字段可疑报告", records[0]["analysis_texts"]["analysis_text"])

    def test_historical_csv_accepts_enriched_accounts_and_evidences(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(directory, "history.csv", (
                "basic_info", "customers", "analysis_texts", "accounts", "evidences"
            ), [{
                "basic_info": _basic("CASE-H-RICH-1"),
                "customers": _customers("H-RICH-1"),
                "analysis_texts": {"analysis_text1": "已有历史可疑报告"},
                "accounts": [{"entity_id": "ACCOUNT-H-RICH-1", "account_number": "62220001"}],
                "evidences": [{"evidence_id": "EVIDENCE-H-RICH-1", "evidence_type": "交易流水"}],
            }])

            records = load_batch_case_file(source, "HISTORICAL")

            self.assertEqual("ACCOUNT-H-RICH-1", records[0]["accounts"][0]["entity_id"])
            self.assertEqual("EVIDENCE-H-RICH-1", records[0]["evidences"][0]["evidence_id"])

    def test_historical_csv_rejects_new_case_transaction_features_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(directory, "history.csv", (
                "basic_info", "customers", "transaction_features"
            ), [{
                "basic_info": _basic("CASE-H-3"),
                "customers": _customers("H-3"),
                "transaction_features": {"case_id": "CASE-H-3"},
            }])

            with self.assertRaisesRegex(ValueError, "analysis_texts"):
                load_batch_case_file(source, "HISTORICAL")

    def test_aml_upload_reports_anti_fraud_scenario_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(directory, "history.csv", (
                "basic_info", "customers", "accounts", "devices", "text_analysis"
            ), [{
                "basic_info": _basic("FRD-H-1"),
                "customers": _customers("FRD-H-1"),
                "accounts": [],
                "devices": [],
                "text_analysis": {"text": "历史反欺诈报告"},
            }])

            with self.assertRaisesRegex(ValueError, "切换为反欺诈"):
                load_batch_case_file(source, "HISTORICAL")

    def test_new_csv_requires_transaction_features_and_rejects_duplicate_case_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            row = {
                "basic_info": _basic("CASE-N-1"),
                "customers": _customers("N-1"),
                "transaction_features": {"case_id": "CASE-N-1", "features": []},
            }
            source = self._write_csv(directory, "new.csv", (
                "basic_info", "customers", "transaction_features"
            ), [row, row])

            with self.assertRaisesRegex(ValueError, "重复 case_id"):
                load_batch_case_file(source, "NEW")

    def test_xlsx_uses_the_same_contract(self) -> None:
        try:
            from openpyxl import Workbook
        except ImportError:
            self.skipTest("openpyxl is not installed")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "new.xlsx"
            workbook = Workbook()
            sheet = workbook.active
            sheet.append(["basic_info", "customers", "transaction_features"])
            sheet.append([
                json.dumps(_basic("CASE-X-1"), ensure_ascii=False),
                json.dumps(_customers("X-1"), ensure_ascii=False),
                json.dumps({"case_id": "CASE-X-1"}, ensure_ascii=False),
            ])
            workbook.save(source)
            workbook.close()

            records = load_batch_case_file(source, "NEW")

            self.assertEqual("CASE-X-1", records[0]["basic_info"]["case_id"])

    def test_current_historical_input_replaces_same_case_from_postgresql_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pg_case = root / "pg.json"
            current_case = root / "current.json"
            pg_case.write_text(json.dumps({"basic_info": _basic("CASE-H-1")}), encoding="utf-8")
            current_case.write_text(json.dumps({"basic_info": _basic("CASE-H-1")}), encoding="utf-8")

            snapshot = _merge_history_snapshot([pg_case], [{
                "text": {"generated": False},
                "framework": {"basic_info": _basic("CASE-H-1")},
                "finalPath": current_case,
            }])

            self.assertEqual([current_case], snapshot)

    def test_completed_case_is_emitted_before_a_later_case_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            rows = [
                {
                    "basic_info": _basic("CASE-N-1"),
                    "customers": _customers("N-1"),
                    "transaction_features": {"case_id": "CASE-N-1"},
                },
                {
                    "basic_info": _basic("CASE-N-2"),
                    "customers": _customers("N-2"),
                    "transaction_features": {"case_id": "CASE-N-2"},
                },
            ]
            source = self._write_csv(directory, "new.csv", (
                "basic_info", "customers", "transaction_features"
            ), rows)
            emitted: list[tuple[str, int, int]] = []

            class FakeWorkflow:
                def __init__(self, **_kwargs):
                    pass

                def close(self) -> None:
                    pass

            def generate_text(record: dict) -> dict:
                case_id = record["basic_info"]["case_id"]
                if case_id == "CASE-N-2":
                    raise RuntimeError("second case failed")
                return {"generated": True, "analysisTexts": {"summary": "报告"}}

            def extract_case(_workflow, record, _text, _index, output_root):
                case_id = record["basic_info"]["case_id"]
                final_path = output_root / case_id / "final_case.json"
                final_path.parent.mkdir(parents=True, exist_ok=True)
                final_path.write_text(json.dumps({"basic_info": _basic(case_id)}))
                return {
                    "basic_info": _basic(case_id),
                    "events": [],
                    "relationships": [],
                }, final_path

            similarity = {
                "similarCases": [],
                "rawResult": {},
                "algorithm": "TEST",
                "algorithmVersion": "1",
                "degraded": False,
            }
            callback = lambda case, completed, total: emitted.append(
                (case["caseId"], completed, total)
            )
            request = {
                "jobId": "JOB-STREAM-TEST",
                "sourcePath": str(source),
                "processingMode": "BATCH",
                "recognitionMode": "NEW",
                "historySource": "POSTGRESQL",
            }

            with patch.object(framework_extraction, "BankCaseWorkflow", FakeWorkflow), \
                    patch.object(framework_extraction, "ensure_analysis_text", generate_text), \
                    patch.object(framework_extraction, "_extract_case", extract_case), \
                    patch.object(
                        framework_extraction,
                        "graph_snapshot",
                        return_value={"nodeCount": 0, "edgeCount": 0},
                    ), patch.object(
                        framework_extraction,
                        "match_similar_cases",
                        return_value=similarity,
                    ):
                with self.assertRaisesRegex(RuntimeError, "second case failed"):
                    run_pipeline(request, callback)

            self.assertEqual([("CASE-N-1", 1, 2)], emitted)

    def test_new_batch_uses_quality_services_and_accumulates_completed_cases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            rows = [
                {
                    "basic_info": _basic(case_id),
                    "customers": _customers(case_id),
                    "transaction_features": {"case_id": case_id},
                }
                for case_id in ("CASE-N-1", "CASE-N-2")
            ]
            source = self._write_csv(directory, "new.csv", (
                "basic_info", "customers", "transaction_features"
            ), rows)
            workflow_options: list[dict] = []
            similarity_histories: list[list[str]] = []

            class FakeWorkflow:
                def __init__(self, **kwargs):
                    workflow_options.append(kwargs)

                def close(self) -> None:
                    pass

            def generate_text(_record: dict) -> dict:
                return {
                    "generated": True,
                    "analysisTexts": {"summary": "报告"},
                    "analysisText": "报告",
                }

            def extract_case(_workflow, record, _text, _index, output_root):
                case_id = record["basic_info"]["case_id"]
                final_path = output_root / case_id / "final_case.json"
                final_path.parent.mkdir(parents=True, exist_ok=True)
                final_path.write_text(
                    json.dumps({"basic_info": _basic(case_id)}), encoding="utf-8"
                )
                return {
                    "basic_info": _basic(case_id),
                    "events": [],
                    "relationships": [],
                }, final_path

            def match_cases(_query, history, _settings):
                similarity_histories.append([Path(item).parent.name for item in history])
                return {
                    "similarCases": [],
                    "rawResult": {},
                    "algorithm": "TEST",
                    "algorithmVersion": "1",
                    "degraded": False,
                }

            request = {
                "jobId": "JOB-QUALITY-TEST",
                "sourcePath": str(source),
                "processingMode": "BATCH",
                "recognitionMode": "NEW",
                "historySource": "POSTGRESQL",
            }
            with patch.object(framework_extraction, "BankCaseWorkflow", FakeWorkflow), \
                    patch.object(framework_extraction, "ensure_analysis_text", generate_text), \
                    patch.object(framework_extraction, "_extract_case", extract_case), \
                    patch.object(
                        framework_extraction,
                        "graph_snapshot",
                        return_value={"nodeCount": 0, "edgeCount": 0},
                    ), patch.object(
                        framework_extraction,
                        "match_similar_cases",
                        side_effect=match_cases,
                    ):
                result = run_pipeline(request)

            self.assertEqual("SUCCEEDED", result["status"])
            self.assertTrue(workflow_options[0]["llm_enabled"])
            self.assertTrue(workflow_options[0]["corenlp_enabled"])
            self.assertEqual("http://127.0.0.1:9002", workflow_options[0]["corenlp_url"])
            self.assertEqual(3, workflow_options[0]["service_max_attempts"])
            self.assertEqual(
                [["CASE-N-1"], ["CASE-N-1", "CASE-N-2"]],
                similarity_histories,
            )


if __name__ == "__main__":
    unittest.main()
