"""Preserve structured entities carried inside production basic-info columns."""

from __future__ import annotations

import copy
import hashlib
import json
from collections.abc import Mapping
from typing import Any


COLLECTION_ALIASES = {
    "customers": ("customers", "customer_info", "customerInfo"),
    "accounts": ("accounts", "account_info", "accountInfo"),
    "other_entities": ("other_entities", "otherEntities", "entities"),
    "evidences": ("evidences", "evidence_list", "evidenceList"),
}
TRANSACTION_ALIASES = (
    "transaction_features", "transactionFeatures", "feature_analysis",
    "featureAnalysis", "transactions",
)


def _present(value: Any) -> bool:
    return value is not None and value != "" and value != [] and value != {}


def _natural_key(field: str, item: Mapping[str, Any]) -> tuple[str, ...] | None:
    identity_fields = {
        "customers": ("customer_number", "id_number", "customer_name"),
        "accounts": ("account_number", "bank_card_number"),
        "other_entities": ("entity_attr_1", "entity_name", "name"),
        "evidences": (),
    }
    for name in identity_fields[field]:
        if _present(item.get(name)):
            if field == "other_entities":
                # The same label can refer to different addresses or devices.
                return (str(item[name]), str(item.get("entity_attr_2") or item.get("entity_type") or ""),
                        str(item.get("entity_attr_4") or ""))
            return (str(item[name]),)
    return None


def _fill_missing(primary: dict[str, Any], fallback: Mapping[str, Any]) -> None:
    for name, value in fallback.items():
        if not _present(primary.get(name)):
            primary[name] = copy.deepcopy(value)
        elif isinstance(primary[name], dict) and isinstance(value, Mapping):
            _fill_missing(primary[name], value)


def normalize_case_entities(
    payload: Mapping[str, Any], source: str = "案例输入"
) -> dict[str, Any]:
    """Promote nested entities, keeping explicit top-level data authoritative.

    Both three-column production rows and five-column enriched rows can carry
    additional structured collections inside ``basic_info``. Empty adapter
    placeholders must not hide those collections. Matching IDs are merged;
    nested-only records remain present. Supplied IDs are preserved, while
    missing IDs get a deterministic case-scoped ID before relationship building.
    The original basic-info fields are retained as report-generation context.
    """
    result = copy.deepcopy(dict(payload))
    basic = result.get("basic_info", result.get("basicInfo", {}))
    if not isinstance(basic, dict):
        raise ValueError(f"{source} 的 basic_info 必须是 JSON 对象")
    result["basic_info"] = basic
    case_id = str(basic.get("case_id") or basic.get("caseId")
                  or result.get("case_id") or result.get("caseId") or "")

    for field, aliases in COLLECTION_ALIASES.items():
        id_field = "evidence_id" if field == "evidences" else "entity_id"
        items: list[dict[str, Any]] = []
        for container_name, container in (("", result), ("basic_info.", basic)):
            for alias in aliases:
                values = container.get(alias)
                if values is None:
                    continue
                if not isinstance(values, list):
                    raise ValueError(f"{source} 的 {container_name}{alias} 必须是 JSON 数组")
                for index, value in enumerate(values, start=1):
                    if not isinstance(value, dict):
                        raise ValueError(
                            f"{source} 的 {container_name}{alias} 第 {index} 项必须是 JSON 对象"
                        )
                    item = copy.deepcopy(value)
                    item_id = str(item.get(id_field) or "").strip()
                    if item_id:
                        item[id_field] = item_id
                    natural_key = _natural_key(field, item)
                    duplicate = next((existing for existing in items if (
                        item_id and item_id == str(existing.get(id_field) or "").strip()
                    ) or (
                        natural_key is not None
                        and natural_key == _natural_key(field, existing)
                        and not (item_id and existing.get(id_field) and item_id != existing[id_field])
                    ) or (not item_id and item == existing)), None)
                    if duplicate is None:
                        items.append(item)
                    else:
                        _fill_missing(duplicate, item)

        for item in items:
            if not str(item.get(id_field) or "").strip():
                identity = _natural_key(field, item) or {
                    key: value for key, value in item.items() if key != id_field
                }
                seed = json.dumps([case_id, field, identity], sort_keys=True, ensure_ascii=False)
                prefix = {"customers": "CUST", "accounts": "ACCT",
                          "other_entities": "OTHER", "evidences": "EVD"}[field]
                item[id_field] = f"{prefix}-{hashlib.sha256(seed.encode('utf-8')).hexdigest()[:16]}"
            if field == "other_entities":
                if not _present(item.get("entity_attr_1")):
                    item["entity_attr_1"] = item.get("entity_name") or item.get("name")
                if not _present(item.get("entity_attr_2")):
                    item["entity_attr_2"] = item.get("entity_type")
        result[field] = items

    features: dict[str, Any] | list[Any] = {}
    for container_name, container in (("", result), ("basic_info.", basic)):
        for alias in TRANSACTION_ALIASES:
            value = container.get(alias)
            if value is None:
                continue
            if not isinstance(value, (dict, list)):
                raise ValueError(f"{source} 的 {container_name}{alias} 必须是 JSON 对象或数组")
            if not features and value:
                features = copy.deepcopy(value)
    result["transaction_features"] = features
    return result
