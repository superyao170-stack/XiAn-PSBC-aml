from __future__ import annotations

import unittest
from unittest.mock import patch

from app import (
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_RERANKER_MODEL,
    OpenAICompatibleJsonModel,
    SIMILARITY_ALGORITHM_ID,
    execute,
)


def final_case(case_id: str, event_type: str = "快速转账") -> dict:
    return {
        "basic_info": {"case_id": case_id, "case_name": f"{case_id}测试案例"},
        "customers": [{"entity_id": f"CUS-{case_id}", "customer_name": "测试客户"}],
        "accounts": [],
        "other_entities": [],
        "events": [
            {
                "event_id": f"EV-{case_id}",
                "event_type": event_type,
                "event_name": event_type,
                "event_description": "资金到账后快速转出",
            }
        ],
        "relationships": [
            {
                "source_node_id": f"CUS-{case_id}",
                "target_node_id": f"EV-{case_id}",
                "relationship_type": "参与关系",
            }
        ],
        "evidences": [],
    }


class AmlIntelligenceWorkerTests(unittest.TestCase):
    def test_health_lists_both_algorithms(self) -> None:
        result = execute({"action": "health"})
        self.assertEqual("UP", result["status"])
        self.assertEqual(2, len(result["algorithms"]))

    def test_similarity_returns_ranked_ged_results(self) -> None:
        # Keep the unit test deterministic and offline; Docker/runtime tests
        # cover loading the two bundled BGE models.
        with patch("app.EMBEDDING_MODEL", None), patch("app.RERANKER_MODEL", None):
            result = execute(
                {
                    "action": "case-similarity",
                    "payload": {
                        "queryCase": final_case("NEW"),
                        "historyCases": [
                            final_case("SAME"),
                            final_case("OTHER", "夜间现金交易"),
                        ],
                    },
                },
            )
        self.assertEqual(SIMILARITY_ALGORITHM_ID, result["algorithmId"])
        self.assertEqual(2, result["pair_match_count"])
        self.assertEqual("SAME", result["results"][0]["case_id"])
        self.assertGreaterEqual(
            result["results"][0]["similarity"],
            result["results"][1]["similarity"],
        )

    def test_similarity_defaults_to_requested_bge_models(self) -> None:
        self.assertEqual("BAAI/bge-small-zh-v1.5", DEFAULT_EMBEDDING_MODEL)
        self.assertEqual("BAAI/bge-reranker-v2-m3", DEFAULT_RERANKER_MODEL)

    def test_text_generation_model_uses_deepseek_v4_flash_environment(self) -> None:
        with patch("app.LLM_API_KEY", "test-key"):
            model = OpenAICompatibleJsonModel()
        self.assertEqual("test-key", model.api_key)
        self.assertEqual("https://api.deepseek.com/chat/completions", model.api_url)
        self.assertEqual("deepseek-v4-flash", model.model_name)


if __name__ == "__main__":
    unittest.main()
