from __future__ import annotations

from typing import Any

from .models import CaseInput, FactIndex, RiskEvent


TRANSACTION_RISK_TYPES = {"异常资金流入", "资金快进快出", "异常交易对手", "试探或拆分交易"}
MOBILE_RISK_TYPES = {"异常设备登录", "异常APP操作", "异常限额操作", "账户控制异常"}


def build_fact_index(case: CaseInput, risk_events: tuple[RiskEvent, ...]) -> FactIndex:
    values: dict[str, Any] = {}
    groups: dict[str, list[str]] = {
        "case": [], "accounts": [], "customers": [], "devices": [],
        "risk_all": [], "risk_transaction": [], "risk_mobile": [],
    }

    def add(group: str, ref: str, value: Any) -> None:
        values[ref] = value
        groups[group].append(ref)

    for key, value in case.basic_info.items():
        add("case", f"basic_info.{key}", value)
    for account in case.accounts:
        entity = str(account["entity_id"])
        for key, value in account.items():
            add("accounts", f"accounts[{entity}].{key}", value)
    for customer in case.customers:
        entity = str(customer["entity_id"])
        for key, value in customer.items():
            add("customers", f"customers[{entity}].{key}", value)
    for device in case.devices:
        entity = str(device["设备号"])
        for key, value in device.items():
            add("devices", f"devices[{entity}].{key}", value)
    for event in risk_events:
        event_refs: list[str] = []
        for key, value in event.to_dict().items():
            ref = f"risk_events[{event.risk_event_id}].{key}"
            values[ref] = value
            groups["risk_all"].append(ref)
            event_refs.append(ref)
        if event.risk_type in TRANSACTION_RISK_TYPES:
            groups["risk_transaction"].extend(event_refs)
        if event.risk_type in MOBILE_RISK_TYPES:
            groups["risk_mobile"].extend(event_refs)
    return FactIndex(values, {key: tuple(dict.fromkeys(refs)) for key, refs in groups.items()})
