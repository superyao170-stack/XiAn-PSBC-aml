from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from similarity_matching import match_similar_cases


def _case(case_id: str, event_description: str) -> dict:
    return {
        "basic_info": {"case_id": case_id, "case_name": case_id},
        "customers": [
            {"entity_id": f"{case_id}-CUSTOMER", "customer_name": "测试客户"}
        ],
        "accounts": [],
        "other_entities": [],
        "events": [
            {
                "event_id": f"{case_id}-EVENT",
                "event_name": "资金快进快出",
                "event_type": "可疑资金流转",
                "event_description": event_description,
            }
        ],
        "relationships": [
            {
                "relationship_type": "参与关系",
                "source_node_id": f"{case_id}-CUSTOMER",
                "target_node_id": f"{case_id}-EVENT",
            }
        ],
    }


class SimilarityFallbackTests(unittest.TestCase):
    def test_missing_local_models_degrade_to_structural_ged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            query = root / "query.json"
            history = root / "history.json"
            query.write_text(
                json.dumps(_case("QUERY", "短期内资金集中转入后立即转出"), ensure_ascii=False),
                encoding="utf-8",
            )
            history.write_text(
                json.dumps(_case("HISTORY", "账户资金快进快出"), ensure_ascii=False),
                encoding="utf-8",
            )

            result = match_similar_cases(
                query,
                [history],
                {
                    "embeddingModel": str(root / "missing-embedding"),
                    "rerankerModel": str(root / "missing-reranker"),
                    "embeddingLocalFilesOnly": True,
                    "rerankerLocalFilesOnly": True,
                    "timeoutSeconds": 30,
                    "semanticTimeoutSeconds": 10,
                },
            )

            self.assertTrue(result["degraded"])
            self.assertEqual(1, len(result["similarCases"]))
            self.assertIn("structural_ged_fallback", result["rawResult"]["matching_mode"])
            self.assertTrue(result["rawResult"]["execution_control"]["degraded"])

    def test_no_history_candidate_skips_model_process(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            query = Path(directory) / "query.json"
            query.write_text(
                json.dumps(_case("QUERY", "测试描述"), ensure_ascii=False),
                encoding="utf-8",
            )
            result = match_similar_cases(query, [], {"timeoutSeconds": 30})
            self.assertFalse(result["degraded"])
            self.assertEqual([], result["similarCases"])


if __name__ == "__main__":
    unittest.main()
