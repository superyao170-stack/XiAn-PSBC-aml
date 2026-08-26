from __future__ import annotations

import sys
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))

from datagraph_bank.nodes.events import EventExtractor


class EventDeduplicationTests(unittest.TestCase):
    def test_exact_events_without_entity_metadata_are_deduplicated(self) -> None:
        extractor = EventExtractor.__new__(EventExtractor)
        extractor.dedup_threshold = 0.82
        extractor.llm_review_threshold = 0.68
        event = {
            "event_id": "0000001",
            "event_type_id": "ET015",
            "event_type": "短期高频交易",
            "event_name": "客户唐某云短期高频交易事件",
            "event_description": "客户唐某云短期高频交易事件",
            "event_start_date": "",
            "event_end_date": "",
            "confidence_score": 0.8,
            "reason_steps": [],
        }

        unique, report = extractor._deduplicate([
            dict(event),
            {**event, "event_id": "0000002"},
        ])

        self.assertEqual(1, len(unique))
        self.assertEqual(1, len(report))
        self.assertEqual(1.0, report[0]["similarity"])


if __name__ == "__main__":
    unittest.main()
