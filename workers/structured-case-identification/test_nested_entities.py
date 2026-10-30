from __future__ import annotations

import copy
import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parent
for root in (WORKER_ROOT, WORKER_ROOT / "xian_modules"):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from framework_extraction import (
    _workflow_input, graph_snapshot, load_batch_case_file, load_single_case_directory,
)
from datagraph_bank.input_normalization import normalize_case_entities
from datagraph_bank.io_utils import dump_json, load_case_input
from datagraph_bank.nodes.merge import ResultMerger
from datagraph_bank.nodes.relationships import RelationshipExtractor
from datagraph_bank.nodes.structured import StructuredDataProcessor


def _payload() -> dict:
    return {
        "basic_info": {
            "case_id": "NESTED-001",
            "case_name": "嵌套实体案例",
            "accounts": [{
                "entity_id": "ACCT-001", "account_number": "62220001",
                "holder_name": "测试客户", "account_type": "个人结算账户",
            }],
            "other_entities": [{
                "entity_id": "OTHER-001", "entity_attr_1": "链上地址",
                "entity_attr_2": "TRON", "entity_attr_4": "T***001",
            }],
            "evidences": [{
                "evidence_id": "EVD-001", "evidence_link_id": "ACCT-001",
                "evidence_link_type": "账户", "evidence_type": "交易流水",
            }],
            "transactions": [{"transaction_id": "TX-001", "from_account_id": "ACCT-001"}],
        },
        "customers": [{"entity_id": "CUST-001", "customer_name": "测试客户"}],
        "analysis_texts": {"analysis_text1": "测试客户账户62220001与链上地址有关联。"},
    }


class NestedEntityInputTests(unittest.TestCase):
    def _write_csv(self, root: Path, fields: tuple[str, ...], payload: dict) -> Path:
        source = root / "nested.csv"
        with source.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerow({field: json.dumps(payload[field], ensure_ascii=False) for field in fields})
        return source

    def test_three_column_csv_promotes_all_nested_structured_data(self) -> None:
        payload = _payload()
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(Path(directory), ("basic_info", "customers", "analysis_texts"), payload)
            record = load_batch_case_file(source, "HISTORICAL")[0]
        self.assertEqual(payload["basic_info"]["accounts"], record["accounts"])
        self.assertEqual(payload["basic_info"]["other_entities"], record["other_entities"])
        self.assertEqual(payload["basic_info"]["evidences"], record["evidences"])
        workflow_input = _workflow_input(record, record["analysis_texts"], 1)
        self.assertEqual(payload["basic_info"]["transactions"], workflow_input["transaction_features"])

    def test_enriched_csv_merges_nested_only_records_with_top_level_precedence(self) -> None:
        payload = _payload()
        payload["accounts"] = [
            {"entity_id": "ACCT-001", "account_number": "62229999", "holder_name": ""},
            {"entity_id": "ACCT-002", "account_number": "62220002"},
        ]
        payload["basic_info"]["accounts"].append({"entity_id": "ACCT-003", "account_number": "62220003"})
        payload["evidences"] = []
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(Path(directory), (
                "basic_info", "customers", "analysis_texts", "accounts", "evidences",
            ), payload)
            record = load_batch_case_file(source, "HISTORICAL")[0]
        self.assertEqual(["ACCT-001", "ACCT-002", "ACCT-003"], [item["entity_id"] for item in record["accounts"]])
        self.assertEqual("62229999", record["accounts"][0]["account_number"])
        self.assertEqual("测试客户", record["accounts"][0]["holder_name"])
        self.assertEqual(1, len(record["other_entities"]))
        self.assertEqual(1, len(record["evidences"]))

    def test_new_csv_preserves_nested_entities_and_explicit_transaction_features(self) -> None:
        payload = _payload()
        payload["transaction_features"] = {"case_id": "NESTED-001", "summary": "明确提供的交易特征"}
        with tempfile.TemporaryDirectory() as directory:
            source = self._write_csv(Path(directory), ("basic_info", "customers", "transaction_features"), payload)
            record = load_batch_case_file(source, "NEW")[0]
        self.assertEqual(1, len(record["accounts"]))
        self.assertEqual(1, len(record["other_entities"]))
        self.assertEqual(payload["transaction_features"], record["transaction_features"])

    def test_xlsx_promotes_nested_entities(self) -> None:
        from openpyxl import Workbook

        payload = _payload()
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "nested.xlsx"
            workbook = Workbook()
            sheet = workbook.active
            fields = ("basic_info", "customers", "analysis_texts")
            sheet.append(list(fields))
            sheet.append([json.dumps(payload[field], ensure_ascii=False) for field in fields])
            workbook.save(source)
            workbook.close()
            record = load_batch_case_file(source, "HISTORICAL")[0]
        self.assertEqual(1, len(record["accounts"]))
        self.assertEqual(1, len(record["other_entities"]))

    def test_single_case_and_general_loaders_use_nested_fallback(self) -> None:
        payload = _payload()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for field in ("basic_info", "customers", "analysis_texts"):
                (root / f"{field}.json").write_text(json.dumps(payload[field]), encoding="utf-8")
            # Empty legacy files are placeholders, not instructions to discard nested records.
            (root / "accounts.json").write_text("[]", encoding="utf-8")
            (root / "other_entities.json").write_text("[]", encoding="utf-8")
            records = [load_single_case_directory(root), load_case_input(root), load_case_input(payload)]
            complete_file = root / "complete.json"
            complete_file.write_text(json.dumps(payload), encoding="utf-8")
            records.append(load_case_input(complete_file))
        for record in records:
            with self.subTest(loader=record.get("input_file", record.get("input_dir", "adapter"))):
                self.assertEqual(1, len(record["accounts"]))
                self.assertEqual(1, len(record["other_entities"]))
                self.assertEqual(1, len(record["evidences"]))
                self.assertEqual(payload["basic_info"]["transactions"], record["transaction_features"])

    def test_ids_remain_stable_and_serialized_graph_has_valid_entity_edges(self) -> None:
        payload = _payload()
        del payload["basic_info"]["accounts"][0]["entity_id"]
        del payload["basic_info"]["other_entities"][0]["entity_id"]
        original = copy.deepcopy(payload)
        normalized = normalize_case_entities(payload)
        self.assertEqual(original, payload)
        self.assertEqual(normalized, normalize_case_entities(normalized))
        self.assertEqual(normalized["accounts"], normalize_case_entities(payload)["accounts"])
        state = {**normalized, **StructuredDataProcessor().process(normalized)}
        state.update(RelationshipExtractor().process(state))
        final = ResultMerger().process(state)["final_result"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "final_case.json"
            dump_json(path, final)
            serialized = json.loads(path.read_text(encoding="utf-8"))
        graph = graph_snapshot(serialized)
        by_type = {item["type"]: item for item in graph["nodes"]}
        self.assertEqual("62220001", by_type["ACCOUNT"]["label"])
        self.assertEqual("链上地址", by_type["OTHER_ENTITY"]["label"])
        node_ids = {node["id"] for node in graph["nodes"]}
        self.assertTrue(all(edge["source"] in node_ids and edge["target"] in node_ids for edge in graph["edges"]))
        case_targets = {edge["target"] for edge in graph["edges"] if edge["source"] == "NESTED-001"}
        self.assertIn(by_type["ACCOUNT"]["id"], case_targets)
        self.assertIn(by_type["OTHER_ENTITY"]["id"], case_targets)
        self.assertTrue(any(edge["type"] == "持有关系" and edge["target"] == by_type["ACCOUNT"]["id"] for edge in graph["edges"]))

    def test_invalid_nested_collections_report_source_instead_of_silently_disappearing(self) -> None:
        for field, value, message in (
            ("accounts", {}, "basic_info.accounts 必须是 JSON 数组"),
            ("other_entities", ["invalid"], "basic_info.other_entities 第 1 项必须是 JSON 对象"),
            ("evidences", "invalid", "basic_info.evidences 必须是 JSON 数组"),
            ("transactions", "invalid", "basic_info.transactions 必须是 JSON 对象或数组"),
        ):
            with self.subTest(field=field):
                payload = _payload()
                payload["basic_info"][field] = value
                with self.assertRaisesRegex(ValueError, message):
                    normalize_case_entities(payload, "bad.csv#row=2")


if __name__ == "__main__":
    unittest.main()
