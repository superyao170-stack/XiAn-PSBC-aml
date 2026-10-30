from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parent
for root in (WORKER_ROOT, WORKER_ROOT / "xian_modules"):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from aml_analysis_workflow.csv_input import build_fact_index
from aml_analysis_workflow.knowledge import KnowledgeBase
from datagraph_bank.governed_analysis import GovernedAnalysisTextGenerator
from framework_extraction import (
    _directory_sha256, _workflow_input, load_single_case_directory, run_pipeline,
)
from text_generation import _transaction_features, ensure_analysis_text, generate_analysis_text


def _features() -> dict:
    return {
        "schema_version": "1.0", "case_id": "NEW-SINGLE-001", "feature_level": "customer",
        "stat_window_days": 30, "stat_window_start": "20251201000000",
        "stat_window_end": "20251230235959", "amount_currency_basis": "CNY",
        "amount_unit": "元", "data_quality_note": "仅统计已列示交易",
        "customer_features": [{
            "entity_id": f"CUSTOMER-{index:02d}", "has_transaction_flag": 1,
            "feature_window_start": "20251201000000", "feature_window_end": "20251230235959",
            "tx_cnt_30d": index, "tx_cirmb_amt_sum_30d": index * 1000,
        } for index in range(1, 13)],
    }


class SingleTransactionFeatureTests(unittest.TestCase):
    def _write_case(self, root: Path) -> None:
        (root / "basic_info.json").write_text(json.dumps({
            "case_id": "NEW-SINGLE-001", "case_name": "单案例交易特征",
            "accounts": [{"entity_id": "ACCOUNT-001", "account_number": "62220001"}],
        }), encoding="utf-8")
        (root / "customers.json").write_text(json.dumps([
            {"entity_id": item["entity_id"]} for item in _features()["customer_features"]
        ]), encoding="utf-8")

    def _request(self, root: Path, recognition_mode: str = "NEW") -> dict:
        return {
            "sourcePath": str(root), "processingMode": "SINGLE",
            "recognitionMode": recognition_mode, "targetStage": "UPLOAD",
        }

    def test_compact_canonical_file_is_preserved_in_upload_and_consumed_as_customer_facts(self) -> None:
        features = _features()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_case(root)
            # Compact JSON matches files exported without indentation.
            (root / "transaction_features.json").write_text(
                json.dumps(features, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
            )
            request = self._request(root)
            self.assertEqual(["NEW-SINGLE-001"], run_pipeline({**request, "validateOnly": True})["caseIds"])
            record = run_pipeline(request)["results"][0]["rawRecord"]
        self.assertEqual(features, record["transaction_features"])
        self.assertEqual("ACCOUNT-001", record["accounts"][0]["entity_id"])
        self.assertEqual(features, _workflow_input(record, {"summary": "已有报告"}, 1)["transaction_features"])
        self.assertEqual(features, _transaction_features(record))
        # Construct only the input adapter; initialization and generation call no models here.
        generator = GovernedAnalysisTextGenerator.__new__(GovernedAnalysisTextGenerator)
        case = generator._case_input(record, _transaction_features(record))
        self.assertEqual(12, len(case.transaction_features["customer_transaction_features"]))
        facts = build_fact_index(case)
        for row in features["customer_features"]:
            prefix = f"transaction_features.customer_transaction_features[{row['entity_id']}]"
            self.assertEqual(row["tx_cnt_30d"], facts.values[f"{prefix}.tx_cnt_30d"])
            self.assertEqual(row["tx_cirmb_amt_sum_30d"], facts.values[f"{prefix}.tx_cirmb_amt_sum_30d"])
            self.assertEqual(row["feature_window_start"], facts.values[f"{prefix}.statistics_start_date"])

    def test_report_generation_sends_all_uploaded_customer_features_to_model_prompts(self) -> None:
        class RecordingClient:
            chat_model = "offline-report-regression"
            chat_available = True

            def __init__(self) -> None:
                self.calls = []
                self.lock = threading.Lock()
                self.closed = False

            def chat_json(self, user_prompt: str, *, system_prompt: str) -> dict:
                payload = json.loads(user_prompt.rsplit("\n", 1)[-1])
                with self.lock:
                    self.calls.append({"system": system_prompt, "prompt": user_prompt, "payload": payload})
                if "paragraph_ids" not in payload:
                    return {"available": True, "payload": {"pass": True, "issues": [], "rewrite_targets": []}}
                facts = payload["facts"]
                sentences = []
                for ref, count in facts.items():
                    if ref.endswith(".tx_cnt_30d"):
                        prefix = ref.rsplit(".", 1)[0]
                        customer_id = prefix.split("[")[-1].rstrip("]")
                        amount = facts[f"{prefix}.tx_cirmb_amt_sum_30d"]
                        sentences.append(f"{customer_id}交易{count}笔，合计{amount}元。")
                text = "".join(sentences) or "根据已提供材料开展分析，待进一步核实相关资金用途。"
                return {"available": True, "payload": {"paragraphs": [{
                    "paragraph_id": paragraph_id, "text": text,
                    "fact_refs": list(facts), "signal_refs": [],
                    "missing_inputs": [] if facts else ["相关事实"],
                    "status": "complete" if facts else "not_assessable",
                } for paragraph_id in payload["paragraph_ids"]]}}

            def close(self) -> None:
                self.closed = True

        features = _features()
        client = RecordingClient()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_case(root)
            (root / "transaction_features.json").write_text(
                json.dumps(features, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
            )
            # Keep the real report generator/workflow; isolate unused extraction and external services.
            with patch("framework_extraction.BankCaseWorkflow") as extraction, \
                    patch("text_generation.DeepSeekLLMClient", return_value=client), \
                    patch("datagraph_bank.governed_analysis.KnowledgeBase.load", return_value=KnowledgeBase("offline", ())):
                extraction.return_value.run.side_effect = AssertionError("REPORT must not run framework extraction")
                result = run_pipeline({**self._request(root), "targetStage": "REPORT"})
                extraction.return_value.run.assert_not_called()

        self.assertTrue(client.closed)
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual(1, result["generatedAnalysisTextCount"])
        self.assertEqual(0, result["existingAnalysisTextCount"])
        item = result["results"][0]
        self.assertEqual(features, item["rawRecord"]["transaction_features"])
        report = item["suspiciousReport"]
        self.assertTrue(report["generated"])
        self.assertEqual("SUPPLIED", report["transactionFeatureSource"])
        self.assertTrue(report["audit"]["data_flow"]["transaction_features_used"])
        self.assertTrue(report["review"]["pass"])
        self.assertEqual(12, len(report["paragraphs"]))

        generation_calls = {call["payload"]["node_id"]: call for call in client.calls if "node_id" in call["payload"]}
        self.assertEqual(8, len(generation_calls))
        self.assertEqual(9, len(client.calls))  # Eight generation nodes and the real review step.
        profile_call = generation_calls["t1_profile_transaction"]
        self.assertTrue(profile_call["system"])
        profile_facts = profile_call["payload"]["facts"]
        self.assertEqual(30, profile_facts["transaction_features.statistics_window.window_days"])
        self.assertEqual(features["stat_window_start"], profile_facts["transaction_features.statistics_window.start_date"])
        self.assertEqual(features["stat_window_end"], profile_facts["transaction_features.statistics_window.end_date"])
        audit_refs = set(report["audit"]["paragraphs"]["T1.3"]["fact_refs"])
        review_call = next(call for call in client.calls if "node_id" not in call["payload"])
        for row in features["customer_features"]:
            prefix = f"transaction_features.customer_transaction_features[{row['entity_id']}]"
            expected = {
                "tx_cnt_30d": row["tx_cnt_30d"],
                "tx_cirmb_amt_sum_30d": row["tx_cirmb_amt_sum_30d"],
                "statistics_start_date": row["feature_window_start"],
                "statistics_end_date": row["feature_window_end"],
            }
            for field, value in expected.items():
                ref = f"{prefix}.{field}"
                self.assertEqual(value, profile_facts[ref])
                self.assertEqual(value, review_call["payload"]["facts"][ref])
                self.assertIn(ref, audit_refs)
            self.assertIn(
                f"{row['entity_id']}交易{row['tx_cnt_30d']}笔，合计{row['tx_cirmb_amt_sum_30d']}元。",
                report["analysisTexts"]["analysis_text1"],
            )

    def test_new_single_requires_transaction_file_but_historical_keeps_existing_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_case(root)
            with self.assertRaisesRegex(ValueError, "必须上传 transaction_features.json"):
                run_pipeline({**self._request(root), "validateOnly": True})
            historical = run_pipeline(self._request(root, "HISTORICAL"))
            self.assertEqual({}, historical["results"][0]["rawRecord"]["transaction_features"])

    def test_report_generation_rejects_missing_or_empty_features_without_synthesizing_transactions(self) -> None:
        for optional in ({}, {"transaction_features": {}}, {"transaction_features": []}):
            with self.subTest(optional=optional), patch("text_generation.DeepSeekLLMClient") as client:
                record = {"basic_info": {"case_id": "NEW-SINGLE-001"},
                          "customers": [{"entity_id": "CUSTOMER-01"}], **optional}
                self.assertEqual({}, _transaction_features(record))
                with self.assertRaisesRegex(ValueError, "必须提供非空 transaction_features"):
                    generate_analysis_text(record)
                client.assert_not_called()

    def test_historical_report_reuse_does_not_require_transaction_features(self) -> None:
        with patch("text_generation.DeepSeekLLMClient") as client:
            report = ensure_analysis_text({"analysis_texts": {"analysis_text1": "已有可疑报告"}})
        self.assertFalse(report["generated"])
        self.assertEqual("EXISTING_ANALYSIS_TEXT", report["source"])
        client.assert_not_called()

    def test_canonical_feature_file_requires_object_and_matching_optional_case_id(self) -> None:
        for value, message in (
            ([], "transaction_features.json 顶层必须是 JSON 对象"),
            ({"case_id": "ANOTHER-CASE"}, "case_id 与 basic_info.case_id 不一致"),
        ):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_case(root)
                (root / "transaction_features.json").write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, message):
                    run_pipeline({**self._request(root), "validateOnly": True})

    def test_canonical_feature_file_takes_precedence_over_legacy_and_changes_source_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_case(root)
            legacy = {"customer_transaction_features": [{"customer_id": "CUSTOMER-01", "has_transaction_flag": 0}]}
            (root / "feature_analysis.json").write_text(json.dumps(legacy), encoding="utf-8")
            self.assertEqual(legacy, load_single_case_directory(root)["transaction_features"])
            features = _features()
            (root / "transaction_features.json").write_text(json.dumps(features), encoding="utf-8")
            before = _directory_sha256(root)
            self.assertEqual(features, load_single_case_directory(root)["transaction_features"])
            features["customer_features"][0]["tx_cnt_30d"] += 1
            (root / "transaction_features.json").write_text(json.dumps(features), encoding="utf-8")
            self.assertNotEqual(before, _directory_sha256(root))

    def test_legacy_feature_file_remains_supported_for_internal_single_case_calls(self) -> None:
        for features in (_features(), [{"transaction_id": "TX-001"}]):
            with self.subTest(features_type=type(features)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_case(root)
                (root / "feature_analysis.json").write_text(json.dumps(features), encoding="utf-8")
                record = run_pipeline(self._request(root))["results"][0]["rawRecord"]
                self.assertEqual(features, record["transaction_features"])


if __name__ == "__main__":
    unittest.main()
