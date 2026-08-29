from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from .errors import InputValidationError
from .models import CaseInput, NormalizedEvent


REQUIRED_FILES = {"accounts.json", "customers.json", "basic_info.json", "devices.json", "event_chain.json"}
ACCOUNT_KEYS = {"entity_id", "account_type", "holder_name", "holder_id_type", "holder_id_number", "account_open_date", "province", "institution", "account_number", "bank_card_type", "bank_info", "bank_card_number"}
CUSTOMER_KEYS = {"entity_id", "customer_name", "customer_number", "id_type", "id_number", "occupation_industry", "nationality", "province", "institution", "address", "customer_risk_level"}
BASIC_KEYS = {"case_id", "case_name", "case_description", "business_domain", "case_type", "submission_direction", "case_trigger", "urgency", "report_date", "case_status", "risk_level", "suspected_crime_type", "suspicious_transaction_codes", "disposition_measures"}
DEVICE_KEYS = {"设备号", "设备名称", "ip地址"}
EVENT_KEYS = {"发生时间", "类型", "具体内容"}
EVENT_TYPES = {"流水", "app操作", "设备操作", "规则命中", "限额情况"}
SUFFIX_RE = re.compile(r"尾号(\d{4})")
DEVICE_RE = re.compile(r"DVC-[A-Za-z0-9-]+")
IP_RE = re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)")


def discover_cases(input_dir: Path) -> list[CaseInput]:
    root = Path(input_dir).expanduser().resolve()
    if not root.is_dir():
        raise FileNotFoundError(f"输入目录不存在: {root}")
    if REQUIRED_FILES <= {item.name for item in root.iterdir() if item.is_file()}:
        return [read_case(root)]
    case_dirs = sorted(
        item for item in root.iterdir()
        if item.is_dir() and REQUIRED_FILES <= {child.name for child in item.iterdir() if child.is_file()}
    )
    if not case_dirs:
        raise InputValidationError(f"输入目录未找到包含5个必需JSON文件的案例: {root}")
    return [read_case(item) for item in case_dirs]


def read_case(case_dir: Path) -> CaseInput:
    root = Path(case_dir).expanduser().resolve()
    missing = REQUIRED_FILES - {item.name for item in root.iterdir() if item.is_file()}
    if missing:
        raise InputValidationError(f"案例目录缺少文件: {sorted(missing)}")
    accounts = _load_array(root / "accounts.json")
    customers = _load_array(root / "customers.json")
    basic = _load_object(root / "basic_info.json")
    devices = _load_array(root / "devices.json")
    raw_events = _load_array(root / "event_chain.json")

    _validate_rows(accounts, ACCOUNT_KEYS, "accounts.json")
    _validate_rows(customers, CUSTOMER_KEYS, "customers.json")
    _validate_rows(devices, DEVICE_KEYS, "devices.json")
    missing_basic_keys = BASIC_KEYS - set(basic)
    if missing_basic_keys:
        raise InputValidationError(
            f"basic_info.json缺少必需字段: {sorted(missing_basic_keys)}"
        )
    case_id = str(basic.get("case_id") or "").strip()
    if not case_id:
        raise InputValidationError("basic_info.case_id不能为空")
    _validate_entities(accounts, customers)
    events = _normalize_events(raw_events)
    _validate_event_references(events, accounts, devices)
    return CaseInput(root, case_id, basic, customers, accounts, devices, events)


def safe_case_name(case_id: str) -> str:
    cleaned = re.sub(r"[^0-9A-Za-z._-]+", "_", case_id).strip("._")
    return cleaned[:80] or "case"


def _load_object(path: Path) -> dict[str, Any]:
    value = _load(path)
    if not isinstance(value, dict):
        raise InputValidationError(f"{path.name}顶层必须是JSON对象")
    return value


def _load_array(path: Path) -> list[dict[str, Any]]:
    value = _load(path)
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise InputValidationError(f"{path.name}顶层必须是对象数组")
    return value


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise InputValidationError(f"{path.name}不是有效JSON") from exc


def _validate_rows(rows: list[dict[str, Any]], expected: set[str], filename: str) -> None:
    if not rows:
        raise InputValidationError(f"{filename}不能为空")
    for index, row in enumerate(rows):
        if set(row) != expected:
            raise InputValidationError(f"{filename}[{index}]字段与规定格式不一致")


def _validate_entities(accounts: list[dict[str, Any]], customers: list[dict[str, Any]]) -> None:
    account_ids = [str(item["entity_id"]) for item in accounts]
    customer_ids = [str(item["entity_id"]) for item in customers]
    if len(account_ids) != len(set(account_ids)) or len(customer_ids) != len(set(customer_ids)):
        raise InputValidationError("账户或客户entity_id重复")
    for account in accounts:
        matches = [
            customer for customer in customers
            if customer["customer_name"] == account["holder_name"] and customer["id_number"] == account["holder_id_number"]
        ]
        if len(matches) != 1:
            raise InputValidationError(f"账户{account['entity_id']}无法唯一映射客户")


def _normalize_events(rows: list[dict[str, Any]]) -> tuple[NormalizedEvent, ...]:
    if not rows:
        raise InputValidationError("event_chain.json不能为空")
    events: list[NormalizedEvent] = []
    previous: datetime | None = None
    for index, row in enumerate(rows, start=1):
        if set(row) != EVENT_KEYS:
            raise InputValidationError(f"event_chain.json[{index}]字段与规定格式不一致")
        occurred = str(row["发生时间"]).strip()
        try:
            parsed = datetime.strptime(occurred, "%Y-%m-%d %H:%M:%S")
        except ValueError as exc:
            raise InputValidationError(f"事件{index}发生时间格式无效") from exc
        if previous is not None and parsed < previous:
            raise InputValidationError("event_chain.json必须按发生时间升序排列")
        previous = parsed
        event_type = str(row["类型"]).strip()
        content = str(row["具体内容"]).strip()
        if event_type not in EVENT_TYPES or not content:
            raise InputValidationError(f"事件{index}类型或内容无效")
        if "主体账户" not in content:
            raise InputValidationError(f"事件{index}未明确以主体账户为行为主体")
        events.append(NormalizedEvent(index, occurred, event_type, content))
    return tuple(events)


def _validate_event_references(events: tuple[NormalizedEvent, ...], accounts: list[dict[str, Any]], devices: list[dict[str, Any]]) -> None:
    suffix_counts: dict[str, int] = {}
    for account in accounts:
        suffix = str(account["bank_card_number"])[-4:]
        suffix_counts[suffix] = suffix_counts.get(suffix, 0) + 1
    device_ids = {str(item["设备号"]) for item in devices}
    ips = {str(item["ip地址"]) for item in devices}
    for event in events:
        for suffix in SUFFIX_RE.findall(event.content):
            if suffix_counts.get(suffix) != 1:
                raise InputValidationError(f"事件{event.source_event_index}的尾号{suffix}无法唯一映射账户")
        for device in DEVICE_RE.findall(event.content):
            if device not in device_ids:
                raise InputValidationError(f"事件{event.source_event_index}引用未知设备{device}")
        for ip in IP_RE.findall(event.content):
            if ip not in ips:
                raise InputValidationError(f"事件{event.source_event_index}引用未知IP {ip}")
