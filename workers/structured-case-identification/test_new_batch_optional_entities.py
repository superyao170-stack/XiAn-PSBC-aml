from __future__ import annotations

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

from framework_extraction import _workflow_input, load_batch_case_file, run_pipeline


REQUIRED_FIELDS = ("basic_info", "customers", "transaction_features")


def _payload() -> dict:
    return {
        "basic_info": {"case_id": "NEW-OPTIONAL-001", "case_name": "可选实体案例"},
        "customers": [{"entity_id": "CUSTOMER-001", "customer_name": "测试客户"}],
        "transaction_features": {"case_id": "NEW-OPTIONAL-001", "features": []},
        "accounts": [{
            "entity_id": "ACCOUNT-001", "account_number": "62220001",
            "details": {"备注": "账户详情", "tags": ["境内", "个人"]},
        }],
        "other_entity": [{
            "entity_id": "ENTITY-001", "entity_attr_1": "链上地址",
            "entity_attr_2": "TRON", "details": {"地址": "T***001"},
        }],
    }


class NewBatchOptionalEntityTests(unittest.TestCase):
    def _write_file(
        self, root: Path, suffix: str, fields: tuple[str, ...], cells: list[str | None]
    ) -> Path:
        source = root / f"new.{suffix}"
        if suffix == "csv":
            with source.open("w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(fields)
                writer.writerow(cells)
        else:
            from openpyxl import Workbook

            workbook = Workbook()
            sheet = workbook.active
            sheet.append(list(fields))
            sheet.append(cells)
            workbook.save(source)
            workbook.close()
        return source

    def _cells(self, fields: tuple[str, ...], payload: dict) -> list[str]:
        return [json.dumps(payload[field.strip()], ensure_ascii=False) for field in fields]

    def test_new_batch_accepts_all_optional_column_combinations_in_any_order(self) -> None:
        for suffix in ("csv", "xlsx"):
            for optional in (
                (), ("accounts",), ("other_entity",), ("other_entities",),
                ("accounts", "other_entity"), ("accounts", "other_entities"),
            ):
                with self.subTest(suffix=suffix, optional=optional), tempfile.TemporaryDirectory() as directory:
                    payload = _payload()
                    payload["other_entities"] = payload["other_entity"]
                    fields = optional + tuple(reversed(REQUIRED_FIELDS))
                    source = self._write_file(Path(directory), suffix, fields, self._cells(fields, payload))
                    record = load_batch_case_file(source, "NEW")[0]
                    self.assertEqual(payload["basic_info"], record["basic_info"])
                    self.assertEqual(payload["accounts"] if "accounts" in optional else [], record["accounts"])
                    self.assertEqual(payload["other_entity"] if any(
                        field.startswith("other_entit") for field in optional
                    ) else [], record["other_entities"])

    def test_optional_cells_accept_blank_whitespace_and_empty_json_arrays(self) -> None:
        for suffix in ("csv", "xlsx"):
            for alias in ("other_entity", "other_entities"):
                for empty in (None, "", "   ", "[]"):
                    with self.subTest(suffix=suffix, alias=alias, empty=empty), tempfile.TemporaryDirectory() as directory:
                        fields = REQUIRED_FIELDS + ("accounts", alias)
                        cells = self._cells(REQUIRED_FIELDS, _payload()) + [empty, empty]
                        source = self._write_file(Path(directory), suffix, fields, cells)
                        record = load_batch_case_file(source, "NEW")[0]
                        self.assertEqual([], record["accounts"])
                        self.assertEqual([], record["other_entities"])

    def test_headers_are_trimmed_before_reading_values(self) -> None:
        payload = _payload()
        fields = (" other_entity ", "customers ", " accounts", "basic_info ", " transaction_features ")
        for suffix in ("csv", "xlsx"):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                source = self._write_file(Path(directory), suffix, fields, self._cells(fields, payload))
                record = load_batch_case_file(source, "NEW")[0]
                self.assertEqual(payload["accounts"], record["accounts"])
                self.assertEqual(payload["other_entity"], record["other_entities"])

    def test_invalid_headers_are_rejected_before_data_can_be_overwritten(self) -> None:
        for suffix in ("csv", "xlsx"):
            for fields, message in (
                (("basic_info", "customers", "accounts"), "缺少必填字段: transaction_features"),
                (REQUIRED_FIELDS + ("unsupported",), "不支持的字段: unsupported"),
                (REQUIRED_FIELDS + ("accounts", " accounts "), "重复字段: accounts"),
                (REQUIRED_FIELDS + ("other_entity", "other_entities"), "不能同时提供"),
                (("customers", "transaction_features"), "缺少必填字段: basic_info"),
                (("basic_info", "transaction_features"), "缺少必填字段: customers"),
            ):
                with self.subTest(suffix=suffix, fields=fields), tempfile.TemporaryDirectory() as directory:
                    source = self._write_file(Path(directory), suffix, fields, ["{}"] * len(fields))
                    with self.assertRaisesRegex(ValueError, message):
                        load_batch_case_file(source, "NEW")

    def test_optional_entities_require_arrays_of_json_objects(self) -> None:
        for suffix in ("csv", "xlsx"):
            for field in ("accounts", "other_entity", "other_entities"):
                for invalid, message in (
                    ("{}", "必须是 JSON 数组"),
                    ("null", "必须是 JSON 数组"),
                    ('"invalid"', "必须是 JSON 数组"),
                    ('["invalid"]', "第 1 项必须是 JSON 对象"),
                    ("invalid", "不是有效 JSON"),
                ):
                    with self.subTest(suffix=suffix, field=field, invalid=invalid), tempfile.TemporaryDirectory() as directory:
                        fields = REQUIRED_FIELDS + (field,)
                        cells = self._cells(REQUIRED_FIELDS, _payload()) + [invalid]
                        source = self._write_file(Path(directory), suffix, fields, cells)
                        with self.assertRaisesRegex(ValueError, message):
                            load_batch_case_file(source, "NEW")

    def test_optional_columns_do_not_bypass_transaction_feature_case_id_validation(self) -> None:
        for suffix in ("csv", "xlsx"):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                payload = _payload()
                payload["transaction_features"]["case_id"] = "OTHER-CASE"
                fields = REQUIRED_FIELDS + ("accounts", "other_entity")
                source = self._write_file(Path(directory), suffix, fields, self._cells(fields, payload))
                with self.assertRaisesRegex(ValueError, "case_id 与 basic_info.case_id 不一致"):
                    load_batch_case_file(source, "NEW")

    def test_explicit_optional_entities_keep_nested_fallback_and_top_level_precedence(self) -> None:
        for suffix in ("csv", "xlsx"):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                payload = _payload()
                payload["basic_info"]["accounts"] = [
                    {"entity_id": "ACCOUNT-001", "account_number": "nested", "holder_name": "测试客户"},
                    {"entity_id": "ACCOUNT-002", "account_number": "62220002"},
                ]
                payload["basic_info"]["other_entities"] = [{
                    "entity_id": "ENTITY-002", "entity_attr_1": "设备", "entity_attr_2": "手机",
                }]
                fields = REQUIRED_FIELDS + ("accounts", "other_entity")
                source = self._write_file(Path(directory), suffix, fields, self._cells(fields, payload))
                record = load_batch_case_file(source, "NEW")[0]
                self.assertEqual(["ACCOUNT-001", "ACCOUNT-002"], [value["entity_id"] for value in record["accounts"]])
                self.assertEqual("62220001", record["accounts"][0]["account_number"])
                self.assertEqual("测试客户", record["accounts"][0]["holder_name"])
                self.assertEqual(["ENTITY-001", "ENTITY-002"], [value["entity_id"] for value in record["other_entities"]])

    def test_upload_result_and_workflow_input_keep_optional_entity_details(self) -> None:
        payload = _payload()
        fields = REQUIRED_FIELDS + ("accounts", "other_entity")
        for suffix in ("csv", "xlsx"):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                source = self._write_file(Path(directory), suffix, fields, self._cells(fields, payload))
                request = {
                    "sourcePath": str(source), "processingMode": "BATCH", "recognitionMode": "NEW",
                }
                validation = run_pipeline({**request, "validateOnly": True})
                self.assertEqual(["NEW-OPTIONAL-001"], validation["caseIds"])
                emitted = []
                uploaded = run_pipeline({**request, "targetStage": "UPLOAD"}, lambda *item: emitted.append(item))
                self.assertEqual(1, len(emitted))
                record = uploaded["results"][0]["rawRecord"]
                self.assertEqual(payload["accounts"], record["accounts"])
                self.assertEqual(payload["other_entity"], record["other_entities"])
                workflow_input = _workflow_input(record, {"analysis_text1": "测试报告"}, 1)
                self.assertEqual(payload["accounts"], workflow_input["accounts"])
                self.assertEqual(payload["other_entity"], workflow_input["other_entities"])

    def test_historical_optional_column_contract_stays_unchanged(self) -> None:
        for suffix in ("csv", "xlsx"):
            for fields, cells, message in (
                (
                    ("basic_info", "customers", "analysis_texts", "accounts", "evidences"),
                    self._cells(("basic_info", "customers"), _payload()) + ['{"report":"历史报告"}', "", "[]"],
                    "accounts 不能为空",
                ),
                (
                    ("basic_info", "customers", "analysis_texts", "other_entity"),
                    self._cells(("basic_info", "customers"), _payload()) + ['{"report":"历史报告"}', "[]"],
                    "表头必须严格为",
                ),
            ):
                with self.subTest(suffix=suffix, fields=fields), tempfile.TemporaryDirectory() as directory:
                    source = self._write_file(Path(directory), suffix, fields, cells)
                    with self.assertRaisesRegex(ValueError, message):
                        load_batch_case_file(source, "HISTORICAL")


if __name__ == "__main__":
    unittest.main()
