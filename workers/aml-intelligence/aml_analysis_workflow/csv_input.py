from __future__ import annotations

import csv
import json
import math
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from .errors import InputValidationError
from .models import CaseInput, FactIndex, JsonObject


CSV_COLUMNS = ("basic_info", "customers", "transaction_features")
COUNT_FIELDS = {
    "tx_cnt_30d",
    "in_tx_cnt_30d",
    "out_tx_cnt_30d",
    "active_days_30d",
    "night_tx_cnt_30d",
    "weekend_tx_cnt_30d",
    "counterparty_cnt_30d",
    "in_counterparty_cnt_30d",
    "out_counterparty_cnt_30d",
    "currency_cnt_30d",
    "cny_tx_cnt_30d",
    "foreign_tx_cnt_30d",
    "province_cnt_30d",
    "tx_mode_cnt_30d",
}
AMOUNT_FIELDS = {
    "tx_cirmb_amt_sum_30d",
    "in_cirmb_amt_sum_30d",
    "out_cirmb_amt_sum_30d",
    "tx_cirmb_amt_avg_30d",
    "tx_cirmb_amt_max_30d",
    "tx_cirmb_amt_min_30d",
    "tx_cirmb_amt_std_30d",
    "net_in_cirmb_amt_30d",
    "foreign_cirmb_amt_30d",
}
RATIO_FIELDS = {"night_tx_ratio_30d", "weekend_tx_ratio_30d"}
TIME_FIELDS = {"first_tx_time_30d", "last_tx_time_30d"}
SUPPORTED_FEATURE_FIELDS = (
    {"customer_id", "statistics_start_date", "statistics_end_date", "has_transaction_flag"}
    | COUNT_FIELDS
    | AMOUNT_FIELDS
    | RATIO_FIELDS
    | TIME_FIELDS
)
FEATURE_SEMANTICS = {
    "has_transaction_flag": "是否存在交易记录；1表示有交易，0表示无交易。",
    "tx_cnt_30d": "30日收款与付款交易总笔数。",
    "in_tx_cnt_30d": "30日收款交易笔数。",
    "out_tx_cnt_30d": "30日付款交易笔数。",
    "tx_cirmb_amt_sum_30d": "30日全部收付款人民币折算金额合计。",
    "in_cirmb_amt_sum_30d": "30日收款人民币折算金额合计。",
    "out_cirmb_amt_sum_30d": "30日付款人民币折算金额合计。",
    "tx_cirmb_amt_avg_30d": "30日单笔交易人民币折算金额平均值。",
    "tx_cirmb_amt_max_30d": "30日单笔交易人民币折算金额最大值。",
    "tx_cirmb_amt_min_30d": "30日单笔交易人民币折算金额最小值。",
    "tx_cirmb_amt_std_30d": "30日单笔交易人民币折算金额总体标准差。",
    "net_in_cirmb_amt_30d": "30日净流入金额，即预计算的收款金额减付款金额；正值为净流入，负值为净流出。",
    "active_days_30d": "30日窗口内至少发生一笔交易的自然日数量。",
    "first_tx_time_30d": "窗口内最早交易时间，格式为yyyyMMddHHmmss。",
    "last_tx_time_30d": "窗口内最晚交易时间，格式为yyyyMMddHHmmss。",
    "night_tx_cnt_30d": "22:00至次日06:00的夜间交易笔数。",
    "night_tx_ratio_30d": "22:00至次日06:00的夜间交易笔数占总交易笔数比例。",
    "weekend_tx_cnt_30d": "周六和周日交易笔数。",
    "weekend_tx_ratio_30d": "周六和周日交易笔数占总交易笔数比例。",
    "counterparty_cnt_30d": "30日行内和行外唯一交易对手总数。",
    "in_counterparty_cnt_30d": "30日收款方向唯一交易对手数量。",
    "out_counterparty_cnt_30d": "30日付款方向唯一交易对手数量。",
    "currency_cnt_30d": "30日涉及币种数量。",
    "cny_tx_cnt_30d": "30日人民币交易笔数。",
    "foreign_tx_cnt_30d": "30日外币交易笔数。",
    "foreign_cirmb_amt_30d": "30日外币交易人民币折算金额合计。",
    "province_cnt_30d": "按交易地区省级代码统计的涉及省份数量。",
    "tx_mode_cnt_30d": "交易方式代码种类数量，不代表具体交易方式名称。",
}


def read_cases(path: Path) -> list[CaseInput]:
    csv_path = Path(path).expanduser().resolve()
    if not csv_path.is_file():
        raise FileNotFoundError(f"CSV输入不存在: {csv_path}")
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        headers = tuple(reader.fieldnames or ())
        if headers != CSV_COLUMNS:
            raise InputValidationError(
                "CSV表头必须严格为: " + ", ".join(CSV_COLUMNS)
            )
        cases = [
            _parse_row(csv_path, row_number, row)
            for row_number, row in enumerate(reader, start=2)
            if any(str(value or "").strip() for value in row.values())
        ]
    if not cases:
        raise InputValidationError("CSV中没有案例数据")
    return cases


def read_case(path: Path) -> CaseInput:
    """Read one JSON case using the same contract as one CSV row."""

    input_path = Path(path).expanduser().resolve()
    if not input_path.is_file():
        raise FileNotFoundError(f"单案例输入不存在: {input_path}")
    try:
        value = json.loads(input_path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise InputValidationError(
            f"{input_path} 不是有效JSON（第{exc.lineno}行，第{exc.colno}列）"
        ) from exc
    if not isinstance(value, dict):
        raise InputValidationError(f"{input_path} 顶层必须是JSON对象")
    headers = tuple(value)
    if set(headers) != set(CSV_COLUMNS) or len(headers) != len(CSV_COLUMNS):
        raise InputValidationError(
            "单案例JSON字段必须严格为: " + ", ".join(CSV_COLUMNS)
        )
    return _parse_values(
        input_path,
        None,
        value.get("basic_info"),
        value.get("customers"),
        value.get("transaction_features"),
    )


def _parse_row(csv_path: Path, row_number: int, row: dict[str, Any]) -> CaseInput:
    source = f"{csv_path}#row={row_number}"
    basic_info = _decode(row.get("basic_info"), "basic_info", source, dict)
    customers = _decode(row.get("customers"), "customers", source, list)
    raw_features = _decode(
        row.get("transaction_features"), "transaction_features", source, dict
    )
    return _parse_values(
        csv_path,
        row_number,
        basic_info,
        customers,
        raw_features,
    )


def _parse_values(
    input_path: Path,
    row_number: int | None,
    basic_info: Any,
    customers: Any,
    raw_features: Any,
) -> CaseInput:
    source = (
        str(input_path)
        if row_number is None
        else f"{input_path}#row={row_number}"
    )
    if not isinstance(basic_info, dict):
        raise InputValidationError(f"{source} 的 basic_info 必须是JSON对象")
    if not isinstance(customers, list):
        raise InputValidationError(f"{source} 的 customers 必须是JSON数组")
    if not isinstance(raw_features, dict):
        raise InputValidationError(
            f"{source} 的 transaction_features 必须是JSON对象"
        )
    case_id = str(basic_info.get("case_id") or "").strip()
    if not case_id:
        raise InputValidationError(f"{source} 的 basic_info.case_id 不能为空")
    customer_ids = _validate_customers(customers, source)
    features, warnings = _validate_features(raw_features, customer_ids, source)
    return CaseInput(
        csv_path=input_path,
        row_number=row_number,
        case_id=case_id,
        basic_info=basic_info,
        customers=customers,
        transaction_features=features,
        warnings=tuple(warnings),
        raw_transaction_features=raw_features,
    )


def _decode(
    value: Any, field: str, source: str, expected_type: type[dict] | type[list]
) -> Any:
    try:
        parsed = json.loads(str(value or ""))
    except json.JSONDecodeError as exc:
        raise InputValidationError(
            f"{source} 的 {field} 不是有效JSON（第{exc.lineno}行，第{exc.colno}列）"
        ) from exc
    if not isinstance(parsed, expected_type):
        expected = "对象" if expected_type is dict else "数组"
        raise InputValidationError(f"{source} 的 {field} 必须是JSON{expected}")
    return parsed


def _validate_customers(customers: list[Any], source: str) -> set[str]:
    ids: set[str] = set()
    for index, customer in enumerate(customers):
        if not isinstance(customer, dict):
            raise InputValidationError(f"{source} 的 customers[{index}] 必须是对象")
        customer_id = str(customer.get("entity_id") or "").strip()
        if not customer_id:
            raise InputValidationError(
                f"{source} 的 customers[{index}].entity_id 不能为空"
            )
        if customer_id in ids:
            raise InputValidationError(f"{source} 存在重复客户ID: {customer_id}")
        ids.add(customer_id)
    if not ids:
        raise InputValidationError(f"{source} 至少需要一个客户")
    return ids


def _validate_features(
    value: JsonObject, customer_ids: set[str], source: str
) -> tuple[JsonObject, list[str]]:
    value = normalize_transaction_feature_table(value, source)
    records = value.get("customer_transaction_features")
    if not isinstance(records, list):
        raise InputValidationError(
            f"{source} 的 transaction_features.customer_transaction_features 必须是数组"
        )
    clean_records: list[JsonObject] = []
    feature_ids: set[str] = set()
    warnings: list[str] = []
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            raise InputValidationError(f"{source} 的交易特征第{index + 1}项必须是对象")
        customer_id = str(record.get("customer_id") or "").strip()
        if not customer_id or customer_id not in customer_ids:
            raise InputValidationError(
                f"{source} 的交易特征无法映射客户: {customer_id or '<empty>'}"
            )
        if customer_id in feature_ids:
            raise InputValidationError(f"{source} 存在重复客户交易特征: {customer_id}")
        feature_ids.add(customer_id)
        flag = record.get("has_transaction_flag")
        if isinstance(flag, bool) or flag not in {0, 1}:
            raise InputValidationError(
                f"{source} 的 {customer_id}.has_transaction_flag 必须为0或1"
            )
        clean = {
            key: item
            for key, item in record.items()
            if key in SUPPORTED_FEATURE_FIELDS and item is not None
        }
        _validate_feature_values(clean, customer_id, source)
        if flag == 0:
            ignored = set(clean) - {
                "customer_id",
                "statistics_start_date",
                "statistics_end_date",
                "has_transaction_flag",
            }
            if ignored:
                warnings.append(
                    f"{customer_id} 无交易记录，{len(ignored)}个依赖交易的特征未进入生成上下文"
                )
                clean = {
                    key: item
                    for key, item in clean.items()
                    if key
                    in {
                        "customer_id",
                        "statistics_start_date",
                        "statistics_end_date",
                        "has_transaction_flag",
                    }
                }
        clean_records.append(clean)
    clean_value: JsonObject = {"customer_transaction_features": clean_records}
    if isinstance(value.get("statistics_window"), dict):
        clean_value["statistics_window"] = value["statistics_window"]
    return clean_value, warnings


def normalize_transaction_feature_table(
    value: JsonObject,
    source: str,
) -> JsonObject:
    """Normalize the production transaction-feature table into fact-index form.

    The external contract follows ``transaction_features_mock.json``:
    top-level ``customer_features`` rows use ``entity_id`` and
    ``feature_window_*``.  The generation algorithm retains its stable internal
    names so prompt facts and audits remain backward compatible.
    """

    if not isinstance(value, dict):
        raise InputValidationError(f"{source} 的 transaction_features 必须是JSON对象")
    if "customer_features" not in value:
        if isinstance(value.get("customer_transaction_features"), list):
            return value
        raise InputValidationError(
            f"{source} 的 transaction_features.customer_features 必须是数组"
        )
    records = value.get("customer_features")
    if not isinstance(records, list):
        raise InputValidationError(
            f"{source} 的 transaction_features.customer_features 必须是数组"
        )
    top_case_id = str(value.get("case_id") or "").strip()
    if not top_case_id:
        raise InputValidationError(f"{source} 的 transaction_features.case_id 不能为空")
    if value.get("feature_level") not in {None, "customer"}:
        raise InputValidationError(
            f"{source} 的 transaction_features.feature_level 必须为 customer"
        )

    normalized_records: list[JsonObject] = []
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            raise InputValidationError(
                f"{source} 的 customer_features[{index}] 必须是对象"
            )
        normalized = {
            key: item
            for key, item in record.items()
            if key in SUPPORTED_FEATURE_FIELDS
        }
        normalized["customer_id"] = record.get("entity_id")
        normalized["statistics_start_date"] = record.get("feature_window_start")
        normalized["statistics_end_date"] = record.get("feature_window_end")
        normalized_records.append(normalized)

    return {
        "statistics_window": {
            "start_date": value.get("stat_window_start"),
            "end_date": value.get("stat_window_end"),
            "window_days": value.get("stat_window_days"),
        },
        "customer_transaction_features": normalized_records,
        "source_table_metadata": {
            key: value.get(key)
            for key in (
                "schema_version",
                "case_id",
                "feature_level",
                "amount_currency_basis",
                "amount_unit",
                "ratio_scale",
                "data_quality_note",
            )
            if value.get(key) is not None
        },
    }


def _validate_feature_values(record: JsonObject, customer_id: str, source: str) -> None:
    for field in COUNT_FIELDS:
        if field in record and (
            isinstance(record[field], bool)
            or not isinstance(record[field], int)
            or record[field] < 0
        ):
            raise InputValidationError(f"{source} 的 {customer_id}.{field} 必须是非负整数")
    for field in AMOUNT_FIELDS:
        if field not in record:
            continue
        value = record[field]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise InputValidationError(f"{source} 的 {customer_id}.{field} 必须是有限数值")
        if field != "net_in_cirmb_amt_30d" and value < 0:
            raise InputValidationError(f"{source} 的 {customer_id}.{field} 不能为负数")
    for field in RATIO_FIELDS:
        if field in record and (
            isinstance(record[field], bool)
            or not isinstance(record[field], (int, float))
            or not 0 <= record[field] <= 1
        ):
            raise InputValidationError(f"{source} 的 {customer_id}.{field} 必须在0到1之间")
    for field in TIME_FIELDS:
        if field in record:
            try:
                datetime.strptime(str(record[field]), "%Y%m%d%H%M%S")
            except ValueError as exc:
                raise InputValidationError(
                    f"{source} 的 {customer_id}.{field} 必须为yyyyMMddHHmmss"
                ) from exc


def build_fact_index(case: CaseInput) -> FactIndex:
    values: dict[str, Any] = {}
    groups: dict[str, list[str]] = {
        "case_identity": [],
        "case_risk": [],
        "case_investigation": [],
        "customer": [],
        "window": [],
        "transaction_overview": [],
        "transaction_flow": [],
        "transaction_time": [],
        "counterparty": [],
        "diversity": [],
    }
    for key, item in case.basic_info.items():
        _flatten_scalars(
            "basic_info",
            {key: item},
            values,
            groups[_basic_info_group(key)],
        )
    for customer in case.customers:
        customer_id = str(customer["entity_id"])
        _flatten_scalars(
            f"customers[{customer_id}]", customer, values, groups["customer"]
        )
    window = case.transaction_features.get("statistics_window")
    if isinstance(window, dict):
        _flatten_scalars("transaction_features.statistics_window", window, values, groups["window"])
    for record in case.transaction_features["customer_transaction_features"]:
        customer_id = str(record["customer_id"])
        prefix = f"transaction_features.customer_transaction_features[{customer_id}]"
        for field, value in record.items():
            ref = f"{prefix}.{field}"
            values[ref] = value
            groups[_feature_group(field)].append(ref)
    return FactIndex(values, {key: tuple(refs) for key, refs in groups.items()})


def selected_feature_semantics(fact_refs: Any) -> dict[str, str]:
    fields = {str(ref).rsplit(".", 1)[-1] for ref in fact_refs}
    return {field: FEATURE_SEMANTICS[field] for field in fields if field in FEATURE_SEMANTICS}


def _feature_group(field: str) -> str:
    if field in {"statistics_start_date", "statistics_end_date"}:
        return "window"
    if field in {
        "in_tx_cnt_30d",
        "out_tx_cnt_30d",
        "in_cirmb_amt_sum_30d",
        "out_cirmb_amt_sum_30d",
        "net_in_cirmb_amt_30d",
    }:
        return "transaction_flow"
    if field in {"night_tx_cnt_30d", "night_tx_ratio_30d", "weekend_tx_cnt_30d", "weekend_tx_ratio_30d", *TIME_FIELDS}:
        return "transaction_time"
    if field in {"counterparty_cnt_30d", "in_counterparty_cnt_30d", "out_counterparty_cnt_30d"}:
        return "counterparty"
    if field in {"currency_cnt_30d", "cny_tx_cnt_30d", "foreign_tx_cnt_30d", "foreign_cirmb_amt_30d", "province_cnt_30d", "tx_mode_cnt_30d"}:
        return "diversity"
    return "transaction_overview"


def _basic_info_group(field: str) -> str:
    normalized = field.lower()
    if any(
        token in normalized
        for token in (
            "disposition",
            "judicial",
            "investigation",
            "query",
            "freeze",
            "measure",
            "action",
            "司法",
            "协查",
            "冻结",
            "止付",
            "处置",
            "调查",
        )
    ):
        return "case_investigation"
    if any(
        token in normalized
        for token in (
            "trigger",
            "risk",
            "suspicious",
            "suspected",
            "urgency",
            "report_date",
            "status",
            "submission",
            "预警",
            "风险",
            "可疑",
            "报送",
            "状态",
        )
    ):
        return "case_risk"
    return "case_identity"


def _flatten_scalars(
    prefix: str, value: JsonObject, output: dict[str, Any], refs: list[str]
) -> None:
    for key, item in value.items():
        ref = f"{prefix}.{key}"
        if isinstance(item, dict):
            _flatten_scalars(ref, item, output, refs)
        elif isinstance(item, list):
            for index, child in enumerate(item):
                if isinstance(child, dict):
                    _flatten_scalars(f"{ref}[{index}]", child, output, refs)
                elif not isinstance(child, (list, dict)):
                    child_ref = f"{ref}[{index}]"
                    output[child_ref] = child
                    refs.append(child_ref)
        elif item is not None and str(item).strip():
            output[ref] = item
            refs.append(ref)


def safe_case_name(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "_", value).strip("._") or "case"
