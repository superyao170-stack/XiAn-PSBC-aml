"""Structured transaction-feature preprocessing."""

from __future__ import annotations

from typing import Any, Dict


class TransactionDataProcessor:
    """Validate and audit structured transaction features for downstream use."""

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        # The payload is read-only in downstream nodes. Reusing it avoids a full
        # memory copy for large transaction exports.
        features = state.get("transaction_features") or {}
        if not isinstance(features, (dict, list)):
            raise ValueError("交易流水结构化数据顶层必须是 JSON 对象或数组")

        statistics = self._statistics(features)
        return {
            "processed_transaction_features": features,
            "transaction_processing_report": {
                "present": bool(features),
                "root_type": "object" if isinstance(features, dict) else "array",
                **statistics,
            },
        }

    def _statistics(self, value: Any) -> Dict[str, int]:
        object_count = 0
        array_count = 0
        scalar_count = 0
        stack = [value]
        while stack:
            item = stack.pop()
            if isinstance(item, dict):
                object_count += 1
                stack.extend(item.values())
            elif isinstance(item, list):
                array_count += 1
                stack.extend(item)
            else:
                scalar_count += 1
        return {
            "object_count": object_count,
            "array_count": array_count,
            "scalar_count": scalar_count,
        }
