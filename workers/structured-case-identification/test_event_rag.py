from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))

from datagraph_bank.knowledge_base import JsonKnowledgeRepository
from datagraph_bank.nodes.events import EventExtractor


class KeywordEmbedder:
    def encode(self, texts):
        vectors = []
        for text in texts:
            vectors.append(
                [
                    float("循环" in text or "回流" in text),
                    float("取现" in text or "现金" in text),
                    float("高频" in text),
                ]
            )
        return vectors, [{} for _ in texts]


class EventRagTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.kb_path = Path(self.temp_dir.name) / "kb.json"
        self.kb_path.write_text(
            json.dumps(
                {
                    "metadata": {"version": "test-v1"},
                    "context_control": {"recommended_top_k": 2},
                    "event_types": [
                        {
                            "id": "ET020",
                            "name": "循环或回流交易",
                            "category": "资金链路",
                            "rule": "形成闭合资金路径并有金额和时间证据",
                        },
                        {
                            "id": "ET016",
                            "name": "异常现金集中存取",
                            "category": "现金",
                            "rule": "存在已发生的现金交易及可核验异常依据",
                        },
                        {
                            "id": "ET002",
                            "name": "短期高频交易",
                            "category": "交易频率",
                            "rule": "明确观察窗口且频次达到阈值",
                        },
                    ],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_json_repository_uses_bge_m3_semantic_search(self) -> None:
        repository = JsonKnowledgeRepository(
            self.kb_path,
            embedder=KeywordEmbedder(),
        )

        hits = repository.search("顾问费经多人账户循环回流", top_k=2)

        self.assertEqual("ET020", hits[0].record.id)
        self.assertEqual("json+bge-m3", hits[0].method)

    def test_document_rag_injects_only_retrieved_candidates(self) -> None:
        extractor = EventExtractor(self.kb_path)
        extractor.knowledge_repository = JsonKnowledgeRepository(
            self.kb_path,
            embedder=KeywordEmbedder(),
        )
        candidate = {
            "candidate_text": "[CUST001]收到顾问费后经多人账户循环回流",
            "trigger_keywords": ["循环", "回流"],
            "metrics": {},
        }

        selected, report = extractor._document_kb_candidates(
            candidate["candidate_text"],
            [candidate],
        )

        self.assertLess(len(selected), len(extractor.event_types) + 1)
        self.assertEqual("ET020", selected[0]["id"])
        self.assertEqual("candidate_top_k_bge_m3_then_document_rrf", report["strategy"])
        self.assertTrue(
            all(
                hit["method"] == "json+bge-m3"
                for query in report["queries"]
                for hit in query["hits"]
            )
        )

    def test_existing_type_outside_rag_context_is_downgraded_to_new(self) -> None:
        extractor = EventExtractor(self.kb_path)
        raw_event = {
            "event_id": "0000001",
            "event_name": "[CUST001]异常现金集中存取事件",
            "event_type": "异常现金集中存取",
            "event_description": "[CUST001]发生多笔现金取现",
            "recognition_rule": "知识库标记：已有；知识库事件类型：ET016/异常现金集中存取；事实依据：多笔取现",
        }

        normalized = extractor._normalize_prompt_event(
            raw_event,
            text_name="report",
            source_text=raw_event["event_description"],
            entity_catalog=[
                {"node_id": "CUST001", "node_name": "测试客户", "node_type": "客户", "aliases": ""}
            ],
            allowed_kb_candidates=[
                {"id": "ET020", "name": "循环或回流交易", "rule": "", "category": "资金链路"}
            ],
        )

        self.assertIsNone(normalized["event_type_id"])
        self.assertIn("知识库标记：新增", normalized["recognition_rule"])

    def test_same_event_from_two_reports_is_deduplicated(self) -> None:
        extractor = EventExtractor.__new__(EventExtractor)
        extractor.dedup_threshold = 0.82
        extractor.llm_review_threshold = 0.68
        extractor.llm_client = type(
            "NoReview",
            (),
            {"judge_duplicate_events": lambda *args: {"available": False, "is_duplicate": None}},
        )()
        base = {
            "event_id": "0000001",
            "event_type_id": "ET020",
            "event_type": "循环或回流交易",
            "event_name": "[CUST001]循环或回流交易事件",
            "event_description": "2026年2月，[CUST001]收到顾问费后经多人账户回流，并转回顾问公司形成资金循环。",
            "event_start_date": "",
            "event_end_date": "",
            "risk_indicator": "多人回转并形成闭环",
            "involved_entities": ["CUST001"],
            "confidence_score": 0.9,
            "reason_steps": [],
        }
        second = {
            **base,
            "event_id": "0000002",
            "event_description": "[CUST001]顾问费经关联人员回转后再次转入顾问机构，形成循环资金路径。",
            "confidence_score": 0.85,
            "reason_steps": [],
        }

        unique, report = extractor._deduplicate([base, second])

        self.assertEqual(1, len(unique))
        self.assertEqual(1, len(report))

    def test_source_fact_fallback_prevents_unexplained_zero_events(self) -> None:
        extractor = EventExtractor(self.kb_path)
        state = {
            "basic_info": {
                "case_facts": (
                    "2025年9月至2026年2月，劳务公司收款后通过多人银行卡分批取现，"
                    "测试客户同期收到6名个人转款并用于还贷。"
                ),
                "fund_flow_path": "劳务公司→多人银行卡→测试客户→还贷",
            },
            "customers": [
                {
                    "entity_id": "CUST001",
                    "customer_name": "测试客户",
                    "customer_number": "TEST-001",
                }
            ],
        }

        candidates = extractor._build_source_fact_fallback(state)
        events = extractor._build_local_events(candidates)

        self.assertEqual(1, len(candidates))
        self.assertEqual(["CUST001"], candidates[0]["involved_entities"])
        self.assertEqual(1, len(events))
        self.assertIn("知识库标记：新增", events[0]["recognition_rule"])

    def test_production_knowledge_base_governance_contract(self) -> None:
        kb_path = (
            WORKER_ROOT
            / "xian_modules"
            / "data"
            / "kb"
            / "risk_event_knowledge_base.json"
        )
        value = json.loads(kb_path.read_text(encoding="utf-8"))
        events = value["event_types"]

        self.assertEqual(value["metadata"]["event_count"], len(events))
        self.assertEqual(
            [f"ET{index:03d}" for index in range(1, len(events) + 1)],
            [event["id"] for event in events],
        )
        self.assertEqual(len(events), len({event["name"] for event in events}))
        self.assertGreaterEqual(len(value["metadata"]["research_references"]), 10)
        for event in events:
            self.assertEqual({"id", "name", "rule", "category"}, set(event))
            self.assertIn("必要条件", event["rule"])
            self.assertIn("内部数值门槛", event["rule"])
            self.assertIn("排除条件", event["rule"])


if __name__ == "__main__":
    unittest.main()
