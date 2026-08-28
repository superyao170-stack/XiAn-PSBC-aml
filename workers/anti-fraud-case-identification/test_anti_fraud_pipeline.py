from __future__ import annotations

import csv
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from aml_analysis_workflow.config import WorkflowConfig
from aml_analysis_workflow.knowledge import KnowledgeBase
from aml_analysis_workflow.models import CaseInput
from aml_analysis_workflow.prompt_store import PromptStore
from channel import CHANNELS
from framework_extraction import (
    RELATIONSHIP_LAYER_ORDER,
    RELATIONSHIP_TYPE_CONTRACT,
    RISK_KB_COLLECTION,
    RISK_KB_PATH,
    RULE_PATH,
    load_batch_case_file,
    load_case_directory,
    run_pipeline,
)
from report_generation import RISK_KB_COLLECTION as REPORT_RISK_KB_COLLECTION
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
            "customers.json": [{"entity_id": "CUST-1", "customer_name": "张某"}],
            "accounts.json": [{"entity_id": "ACCT-1", "holder_name": "张某"}],
            "devices.json": [{"设备号": "DEV-1", "设备名称": "手机", "ip地址": "127.0.0.1"}],
        }
        if mode == "NEW":
            values["event_chain.json"] = [
                {"发生时间": "2026-08-01 01:00:00", "类型": "设备操作", "具体内容": "设备DEV-1首次登录并检测到屏幕共享进程"},
                {"发生时间": "2026-08-01 02:00:00", "类型": "规则命中", "具体内容": "命中公安涉案账户名单及FRD-1023多头分散转入规则"},
                {"发生时间": "2026-08-01 02:05:00", "类型": "规则命中", "具体内容": "命中FRD-1031资金快进快出规则，转出比例90%"},
            ]
        else:
            values["text_analysis.json"] = {"text": "公安机关下发涉案账户线索，账户存在异常资金归集转出。"}
        for name, value in values.items():
            (root / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return root

    def _batch_csv(self, root: Path, mode: str, case_ids: tuple[str, ...]) -> Path:
        fields = ["basic_info", "customers", "accounts", "devices"]
        if mode == "NEW":
            fields.append("event_chain")
        else:
            fields.append("text_analysis")
        target = root / "cases.csv"
        with target.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            for case_id in case_ids:
                row = {
                    "basic_info": {"case_id": case_id, "case_name": case_id, "case_trigger": "公安机关涉案账户名单下发"},
                    "customers": [{"entity_id": f"CUS-{case_id}"}],
                    "accounts": [{"entity_id": f"ACC-{case_id}"}],
                    "devices": [{"device_id": f"DEV-{case_id}"}],
                }
                if mode == "NEW":
                    row["event_chain"] = [{"类型": "规则命中", "具体内容": "命中FRD-1031资金快进快出规则"}]
                else:
                    row["text_analysis"] = {"text": "历史案例既有分析文本"}
                writer.writerow({key: json.dumps(value, ensure_ascii=False) for key, value in row.items()})
        return target

    def test_channel_field_is_inferred_and_direct_data_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory)), "NEW")
        self.assertEqual("公安机关下发", record["basic_info"]["渠道"])
        self.assertEqual("ACCT-1", record["accounts"][0]["entity_id"])
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

    def test_risk_event_knowledge_base_is_separate_complete_and_compatible(self) -> None:
        rule_library = json.loads(RULE_PATH.read_text(encoding="utf-8"))
        risk_kb = json.loads(RISK_KB_PATH.read_text(encoding="utf-8"))
        entries = risk_kb["event_types"]
        ids = [str(item["id"]) for item in entries]
        operational_ids = {str(item["id"]) for item in rule_library["event_types"]}
        self.assertEqual(risk_kb["metadata"]["event_count"], len(entries))
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(operational_ids <= set(ids))
        self.assertTrue(all(set(item) == {"id", "name", "rule", "category"} for item in entries))
        self.assertIn("source_urls", risk_kb["metadata"])
        self.assertEqual(5, risk_kb["context_control"]["recommended_top_k"])
        self.assertNotEqual("risk_event_knowledge_base", RISK_KB_COLLECTION)
        self.assertEqual(RISK_KB_COLLECTION, REPORT_RISK_KB_COLLECTION)

    def test_validate_only_builds_risk_chain_without_llm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._case(Path(directory))
            result = run_pipeline({"sourcePath": str(source), "processingMode": "SINGLE", "recognitionMode": "NEW", "validateOnly": True})
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertGreaterEqual(result["riskEventCount"], 4)
        self.assertIn(result["channel"], CHANNELS)

    def test_historical_contract_loads_existing_text_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record = load_case_directory(self._case(Path(directory), "HISTORICAL"), "HISTORICAL")
        self.assertEqual("公安机关下发涉案账户线索，账户存在异常资金归集转出。", record["text_analysis"]["text"])

    def test_new_batch_csv_validates_multiple_cases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._batch_csv(Path(directory), "NEW", ("FRD-B-001", "FRD-B-002"))
            result = run_pipeline({
                "sourcePath": str(source), "processingMode": "BATCH",
                "recognitionMode": "NEW", "validateOnly": True,
            })
        self.assertEqual(2, result["caseCount"])
        self.assertEqual(["FRD-B-001", "FRD-B-002"], result["caseIds"])

    def test_historical_batch_uses_text_analysis_in_fifth_column(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._batch_csv(Path(directory), "HISTORICAL", ("FRD-H-001",))
            records = load_batch_case_file(source, "HISTORICAL")
        self.assertEqual([], records[0]["event_chain"])
        self.assertEqual("历史案例既有分析文本", records[0]["text_analysis"]["text"])

    def test_batch_rejects_duplicate_case_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self._batch_csv(Path(directory), "NEW", ("FRD-DUP-001", "FRD-DUP-001"))
            with self.assertRaisesRegex(ValueError, "重复 case_id"):
                load_batch_case_file(source, "NEW")

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

    def test_new_case_framework_uses_fraud_kb_and_shared_layered_relationship_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input"
            source.mkdir()
            self._case(source)
            analysis = {
                "analysisTexts": {
                    "analysis_text1": (
                        "公安机关下发[CUST-1]名下[ACCT-1]涉案线索。"
                        "[CUST-1]持有[ACCT-1]，新设备登录后发生多头分散转入和资金快进快出，银行已止付。"
                    )
                },
                "analysisText": (
                    "公安机关下发[CUST-1]名下[ACCT-1]涉案线索。"
                    "[CUST-1]持有[ACCT-1]，新设备登录后发生多头分散转入和资金快进快出，银行已止付。"
                ),
                "generated": True,
                "source": "TEST_RISK_EVENT_CHAIN",
                "algorithm": "test",
                "algorithmVersion": "test",
            }
            with (
                patch("framework_extraction.WORKER_ROOT", root),
                patch("framework_extraction.generate_analysis", return_value=analysis),
            ):
                result = run_pipeline({
                    "sourcePath": str(source),
                    "processingMode": "SINGLE",
                    "recognitionMode": "NEW",
                    "targetStage": "FRAMEWORK",
                    "jobId": "TEST-NEW-FRAMEWORK",
                    "frameworkSettings": {"llmEnabled": False, "corenlpEnabled": False},
                })
        framework = result["results"][0]["frameworkExtraction"]
        metadata = framework["processing_metadata"]
        self.assertEqual(list(RELATIONSHIP_LAYER_ORDER), metadata["relationship_layer_order"])
        self.assertEqual(
            ["entity_entity", "entity_event", "event_event"],
            [item["stage"] for item in metadata["relationship_extraction_calls"]],
        )
        self.assertEqual(
            {stage: list(types) for stage, types in RELATIONSHIP_TYPE_CONTRACT.items()},
            metadata["relationship_type_contract"],
        )
        allowed_types = {
            relation_type
            for values in RELATIONSHIP_TYPE_CONTRACT.values()
            for relation_type in values
        }
        self.assertTrue({item["relationship_type"] for item in framework["relationships"]} <= allowed_types)
        self.assertEqual("反欺诈风险事件知识库", metadata["risk_event_knowledge_base_audit"]["name"])
        self.assertEqual(RISK_KB_PATH.resolve(), Path(metadata["risk_event_knowledge_base"]).resolve())
        self.assertGreater(len(framework["events"]), 0)

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
