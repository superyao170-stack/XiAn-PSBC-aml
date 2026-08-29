from __future__ import annotations

import re
from collections import Counter
from datetime import datetime
from typing import Any

from .models import CaseInput


MONEY_RE = re.compile(r"(?<!\d)(\d[\d,]*(?:\.\d+)?)\s*(万元|元)")
SUFFIX_RE = re.compile(r"尾号(\d{4})")
DEVICE_RE = re.compile(r"DVC-[A-Za-z0-9-]+")


def derive_event_observations(case: CaseInput) -> dict[str, Any]:
    times = [datetime.strptime(item.occurred_at, "%Y-%m-%d %H:%M:%S") for item in case.events]
    type_counts = Counter(item.event_type for item in case.events)
    observations: list[dict[str, Any]] = []
    for event, occurred in zip(case.events, times):
        amounts = []
        for raw, unit in MONEY_RE.findall(event.content):
            value = float(raw.replace(",", "")) * (10_000 if unit == "万元" else 1)
            amounts.append(round(value, 2))
        observations.append({
            "source_event_index": event.source_event_index,
            "event_type": event.event_type,
            "hour": occurred.hour,
            "is_night": occurred.hour >= 22 or occurred.hour < 5,
            "money_mentions_yuan": amounts,
            "account_suffixes": list(dict.fromkeys(SUFFIX_RE.findall(event.content))),
            "device_ids": list(dict.fromkeys(DEVICE_RE.findall(event.content))),
        })
    return {
        "event_window": {"start": case.events[0].occurred_at, "end": case.events[-1].occurred_at},
        "event_count": len(case.events),
        "event_count_by_type": dict(sorted(type_counts.items())),
        "night_event_indexes": [item["source_event_index"] for item in observations if item["is_night"]],
        "rule_hit_event_indexes": [item.source_event_index for item in case.events if item.event_type == "规则命中"],
        "event_observations": observations,
    }
