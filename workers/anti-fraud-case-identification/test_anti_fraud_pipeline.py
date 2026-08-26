from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from aml_analysis_workflow.config import WorkflowConfig
from aml_analysis_workflow.knowledge import KnowledgeBase
from aml_analysis_workflow.models import CaseInput
from aml_analysis_workflow.prompt_store import PromptStore
from channel import CHANNELS
from framework_extraction import RULE_PATH, load_case_directory, run_pipeline
from fraud_analysis_workflow import (
    FRAUD_INITIAL_NODES,
    FraudAnalysisWorkflow,
    build_fraud_fact_index,
)
from risk_chain import FraudRuleEngine


class RecordingModel:
    model_name = "recording-model"

    def __init__(self) -> None:
        self.active = 0
        self.max_active = 0
        self.lock = threading.Lock()

    def generate(self, _system_prompt: str, user_prompt: str) -> dict:
        payload = json.loads(user_prompt.rsplit("\n", 1)[-1])
        if "paragraph_ids" not in payload:
            return {"pass": True, "issues": [], "rewrite_targets": []}
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            time.sleep(0.03)
            fact_refs = list(payload.get("facts") or {})
            return {
                "paragraphs": [
                    {
                        "paragraph_id": paragraph_id,
                        "text": f"{paragraph_id} 测试分析",
                        "fact_refs": fact_refs[:1],
                        "signal_refs": [],
                        "missing_inputs": [] if fact_refs else ["事实数据"],
                        "status": "complete" if fact_refs else "not_assessable",
                    }
                    for paragraph_id in payload["paragraph_ids"]
                ]
            }
        finally:
            with self.lock:
                self.active -= 1


class AntiFraudPipelineTests(unittest.TestCase):
    def _case(self, root: Path, mode: str = "NEW") -> Path:
        values = {
            "basic_info.json": {"case_id": "FRD-TEST-001", "case_name": "测试", "case_trigger": "公安机关涉案账户名单下发"},
            "customers.json": [{"entity_id": "CUS-1", "customer_name": "张某"}],
            "accounts.json": [{"entity_id": "ACC-1", "holder_name": "张某"}],
            "devices.json": [{"设备号": "DEV-1", "设备名称": "手机", "ip地址": "127.0.0.1"}],
        }
        if mode == "NEW":
            values["event_chain.json"] = [
                {"发生时间": "2026-08-01 01:00:00", "类型": "设备操作", "具体内容": "设备DEV-1首次登录并检测到屏幕共享进程"},
                {"发生时间": "2026-08-01 02:00:00", "类型": "规则命中", "具体内容": "命中公安涉案账户名单及FRD-1023多头分散转入规则"},
                {"发生时间": "2026-08-01 02:05:00", "类型": "规则命中", "具体内容": "命中FRD-1031资金快进快出规则，转出比例90%"},
            ]
        for name, value in values.items():
            (root / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return root

    def test_channel_field_is_inferred_and_direct_data_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory)), "NEW")
        self.assertEqual("公安机关下发", record["basic_info"]["渠道"])
        self.assertEqual("ACC-1", record["accounts"][0]["entity_id"])
        self.assertEqual("DEV-1", record["devices"][0]["设备号"])

    def test_police_case_still_applies_row_level_mobile_and_transaction_rules(self) -> None:
        engine = FraudRuleEngine(RULE_PATH)
        events = [
            {"类型": "设备操作", "具体内容": "首次登录并检测到屏幕共享进程"},
            {"类型": "规则命中", "具体内容": "命中FRD-1023多头分散转入规则"},
            {"类型": "规则命中", "具体内容": "命中FRD-1031资金快进快出规则，转出比例90%"},
        ]
        rule_ids = {item["rule_id"] for item in engine.build("公安机关下发", events)}
        self.assertTrue({"FRD-MOB-001", "FRD-MOB-002", "FRD-TXN-001", "FRD-TXN-002"} <= rule_ids)

    def test_validate_only_builds_risk_chain_without_llm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._case(Path(directory))
            result = run_pipeline({"sourcePath": str(source), "processingMode": "SINGLE", "recognitionMode": "NEW", "validateOnly": True})
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertGreaterEqual(result["riskEventCount"], 4)
        self.assertIn(result["channel"], CHANNELS)

    def test_historical_contract_accepts_upload_without_text_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory), "HISTORICAL"), "HISTORICAL")
        self.assertNotIn("text_analysis", record)

    def test_fraud_fund_node_receives_risk_chain_facts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory)), "NEW")
        record["risk_event_chain"] = FraudRuleEngine(RULE_PATH).build(
            record["basic_info"]["渠道"], record["event_chain"]
        )
        case = self._analysis_case(record)
        facts = build_fraud_fact_index(case)
        fund_facts = facts.select("funds", "counterparty", "event_time")
        self.assertTrue(fund_facts)
        self.assertTrue(any(ref.startswith("risk_event_chain[") for ref in fund_facts))
        self.assertEqual(("funds", "counterparty", "event_time"), FRAUD_INITIAL_NODES[1].fact_groups)

    def test_fraud_initial_analysis_nodes_run_in_parallel(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory)), "NEW")
        record["risk_event_chain"] = FraudRuleEngine(RULE_PATH).build(
            record["basic_info"]["渠道"], record["event_chain"]
        )
        model = RecordingModel()
        workflow = FraudAnalysisWorkflow(
            model=model,
            prompts=PromptStore(Path(__file__).parent / "data" / "analysis_prompts"),
            config=WorkflowConfig.load(),
            knowledge_base=KnowledgeBase(None, ()),
        )
        generated = workflow.run(self._analysis_case(record))
        self.assertEqual(12, len(generated.paragraphs))
        self.assertGreaterEqual(model.max_active, 2)

    @staticmethod
    def _analysis_case(record: dict) -> CaseInput:
        basic = dict(record["basic_info"])
        basic["risk_event_chain"] = record["risk_event_chain"]
        basic["account_facts"] = record["accounts"]
        basic["device_facts"] = record["devices"]
        return CaseInput(
            csv_path=Path("<anti-fraud-test>"),
            row_number=1,
            case_id=str(basic["case_id"]),
            basic_info=basic,
            customers=record["customers"],
            transaction_features={
                "customer_transaction_features": [
                    {"customer_id": str(customer["entity_id"]), "has_transaction_flag": 0}
                    for customer in record["customers"]
                ]
            },
        )


if __name__ == "__main__":
    unittest.main()
