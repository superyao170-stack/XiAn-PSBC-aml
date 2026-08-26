"""Parallel, fraud-specific routing for the governed analysis workflow.

The AML workflow owns the generic parallel orchestration, evidence validation,
review and bounded rewrite loop.  This module owns the anti-fraud fact index and
node routes so fraud event-chain facts are never forced through AML transaction
feature groups.
"""
from __future__ import annotations

from typing import Any

from aml_analysis_workflow.models import CaseInput, FactIndex
from aml_analysis_workflow.workflow import AnalysisWorkflow, NodeSpec


FRAUD_INITIAL_NODES = (
    NodeSpec(
        "t1_profile_transaction",
        "t1_profile_transaction",
        ("T1.2", "T1.3"),
        (
            "case_identity",
            "case_risk",
            "customer",
            "account",
            "device",
            "risk_event",
            "operation",
        ),
    ),
    NodeSpec(
        "t1_funds",
        "t1_funds",
        ("T1.4",),
        ("funds", "counterparty", "event_time"),
    ),
    NodeSpec(
        "t2_case_subject",
        "t2_case_subject",
        ("T2.1", "T2.2"),
        (
            "case_identity",
            "case_risk",
            "case_investigation",
            "customer",
            "account",
            "device",
            "risk_event",
            "funds",
            "counterparty",
            "operation",
            "external_clue",
        ),
    ),
    NodeSpec(
        "t2_funds_account",
        "t2_funds_account",
        ("T2.3.1", "T2.3.2"),
        (
            "case_risk",
            "account",
            "device",
            "funds",
            "counterparty",
            "operation",
            "event_time",
        ),
        ("资金规模", "交易频率", "交易时间", "资金链路", "金额模式", "账户行为"),
    ),
    NodeSpec(
        "t2_counterparty_judicial",
        "t2_counterparty_judicial",
        ("T2.3.3", "T2.3.4"),
        (
            "case_identity",
            "case_investigation",
            "counterparty",
            "external_clue",
        ),
        ("交易对手", "司法处置", "地域"),
    ),
)

FRAUD_SECOND_STAGE_NODES = (
    NodeSpec(
        "t1_subject",
        "t1_subject",
        ("T1.5",),
        ("case_identity", "case_risk", "customer", "account", "device"),
    ),
    NodeSpec("t2_conclusion", "t2_conclusion", ("T2.4",), ()),
)

FRAUD_DISCOVERY_NODE = NodeSpec(
    "t1_discovery",
    "t1_discovery",
    ("T1.1",),
    ("case_identity", "case_risk", "external_clue", "event_time"),
)

FUND_CATEGORIES = {"涉诈资金归集", "涉诈资金转移", "诈骗类型线索"}
OPERATION_CATEGORIES = {"账户接管", "敏感操作", "异常操作时序"}
EXTERNAL_CATEGORIES = {"外部权威线索", "风险处置"}
FUND_TOKENS = (
    "流水",
    "转入",
    "转出",
    "入账",
    "出账",
    "收款",
    "付款",
    "取现",
    "金额",
    "资金",
    "余额",
    "限额",
)
COUNTERPARTY_TOKENS = ("交易对手", "收款人", "付款人", "尾号", "账户")
EXTERNAL_TOKENS = ("公安", "反诈", "监管", "司法", "止付", "冻结", "限制")


class FraudAnalysisWorkflow(AnalysisWorkflow):
    """Run the same staged parallel workflow with fraud-owned fact routes."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(
            *args,
            fact_index_builder=build_fraud_fact_index,
            initial_nodes=FRAUD_INITIAL_NODES,
            second_stage_nodes=FRAUD_SECOND_STAGE_NODES,
            discovery_node=FRAUD_DISCOVERY_NODE,
            **kwargs,
        )


def build_fraud_fact_index(case: CaseInput) -> FactIndex:
    """Index direct mappings and risk-chain evidence into fraud fact groups."""
    values: dict[str, Any] = {}
    groups: dict[str, list[str]] = {
        "case_identity": [],
        "case_risk": [],
        "case_investigation": [],
        "customer": [],
        "account": [],
        "device": [],
        "risk_event": [],
        "funds": [],
        "counterparty": [],
        "operation": [],
        "external_clue": [],
        "event_time": [],
    }

    synthetic = {"risk_event_chain", "account_facts", "device_facts"}
    for key, value in case.basic_info.items():
        if key in synthetic:
            continue
        target = _basic_group(key)
        _flatten(f"basic_info.{key}", value, values, groups[target])

    for customer in case.customers:
        customer_id = str(customer.get("entity_id") or "unknown")
        _flatten(f"customers[{customer_id}]", customer, values, groups["customer"])

    for index, account in enumerate(_objects(case.basic_info.get("account_facts"))):
        account_id = str(account.get("entity_id") or index)
        _flatten(f"accounts[{account_id}]", account, values, groups["account"])

    for index, device in enumerate(_objects(case.basic_info.get("device_facts"))):
        device_id = str(
            device.get("设备号") or device.get("device_id") or device.get("entity_id") or index
        )
        _flatten(f"devices[{device_id}]", device, values, groups["device"])

    for index, event in enumerate(_objects(case.basic_info.get("risk_event_chain"))):
        event_id = str(event.get("risk_event_id") or index)
        prefix = f"risk_event_chain[{event_id}]"
        refs: list[str] = []
        _flatten(prefix, event, values, refs)
        groups["risk_event"].extend(refs)
        category = str(event.get("category") or "")
        searchable = _searchable_text(event)
        if category in FUND_CATEGORIES or any(token in searchable for token in FUND_TOKENS):
            groups["funds"].extend(refs)
        if any(token in searchable for token in COUNTERPARTY_TOKENS):
            groups["counterparty"].extend(refs)
        if category in OPERATION_CATEGORIES:
            groups["operation"].extend(refs)
        if category in EXTERNAL_CATEGORIES or any(token in searchable for token in EXTERNAL_TOKENS):
            groups["external_clue"].extend(refs)
        groups["event_time"].extend(
            ref for ref in refs if _is_time_ref(ref)
        )

    return FactIndex(
        values,
        {name: tuple(dict.fromkeys(refs)) for name, refs in groups.items()},
    )


def _objects(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _flatten(prefix: str, value: Any, output: dict[str, Any], refs: list[str]) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            _flatten(f"{prefix}.{key}", item, output, refs)
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _flatten(f"{prefix}[{index}]", item, output, refs)
        return
    if value is None or not str(value).strip():
        return
    output[prefix] = value
    refs.append(prefix)


def _basic_group(field: str) -> str:
    normalized = field.lower()
    if any(token in normalized for token in (
        "disposition", "investigation", "judicial", "freeze", "measure",
        "处置", "调查", "公安", "司法", "止付", "冻结",
    )):
        return "case_investigation"
    if any(token in normalized for token in (
        "risk", "suspicious", "suspected", "trigger", "status", "urgency",
        "风险", "可疑", "触发", "状态", "紧急",
    )):
        return "case_risk"
    return "case_identity"


def _searchable_text(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(_searchable_text(item) for item in value.values())
    if isinstance(value, list):
        return " ".join(_searchable_text(item) for item in value)
    return str(value or "")


def _is_time_ref(ref: str) -> bool:
    field = ref.rsplit(".", 1)[-1]
    return field in {"event_start", "event_end", "occurred_at", "发生时间"}
