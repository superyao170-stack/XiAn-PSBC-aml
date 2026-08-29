from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from fraud_report_workflow.case_input import read_case
from fraud_report_workflow.config import WorkflowConfig
from fraud_report_workflow.errors import InputValidationError
from fraud_report_workflow.prompt_store import PromptStore
from fraud_report_workflow.rules import FraudRuleLibrary


class FraudReportInputCompatibilityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp())
        basic = {
            "case_id": "FRD-TEST-001", "case_name": "测试案例",
            "case_description": "", "business_domain": "反欺诈",
            "case_type": "", "submission_direction": "", "case_trigger": "",
            "urgency": "", "report_date": "", "case_status": "",
            "risk_level": "", "suspected_crime_type": "",
            "suspicious_transaction_codes": [], "disposition_measures": [],
            "channel": "手机银行", "渠道": "手机银行",
        }
        customer = {
            "entity_id": "C1", "customer_name": "张某", "customer_number": "",
            "id_type": "", "id_number": "ID1", "occupation_industry": "",
            "nationality": "", "province": "", "institution": "",
            "address": "", "customer_risk_level": "",
        }
        account = {
            "entity_id": "A1", "account_type": "", "holder_name": "张某",
            "holder_id_type": "", "holder_id_number": "ID1", "account_open_date": "",
            "province": "", "institution": "", "account_number": "",
            "bank_card_type": "", "bank_info": "", "bank_card_number": "622200001234",
        }
        self.write("basic_info.json", basic)
        self.write("customers.json", [customer])
        self.write("accounts.json", [account])
        self.write("devices.json", [{"设备号": "DVC-1", "设备名称": "手机", "ip地址": "10.0.0.1"}])
        self.write("event_chain.json", [{
            "发生时间": "2026-08-28 10:00:00", "类型": "app操作",
            "具体内容": "主体账户登录手机银行。",
        }])

    def write(self, name: str, value: object) -> None:
        (self.root / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def test_allows_system_channel_extension_fields(self) -> None:
        case = read_case(self.root)
        self.assertEqual(case.case_id, "FRD-TEST-001")
        self.assertEqual(case.basic_info["渠道"], "手机银行")

    def test_still_rejects_missing_required_basic_field(self) -> None:
        path = self.root / "basic_info.json"
        basic = json.loads(path.read_text(encoding="utf-8"))
        del basic["case_id"]
        self.write("basic_info.json", basic)
        with self.assertRaisesRegex(InputValidationError, "case_id"):
            read_case(self.root)

    def test_embedded_resources_resolve_from_renamed_package(self) -> None:
        self.assertTrue(WorkflowConfig.load().allowed_risk_types)
        self.assertIn("反欺诈", PromptStore().system())
        self.assertTrue(FraudRuleLibrary.load().rules)


if __name__ == "__main__":
    unittest.main()
