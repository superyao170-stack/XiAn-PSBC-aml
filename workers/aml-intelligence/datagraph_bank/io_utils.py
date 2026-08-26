"""Input/output helpers."""

from __future__ import annotations

import csv
import json
import os
import tempfile
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Optional

from pydantic import BaseModel


PRODUCTION_CSV_FIELDS = ("basic_info", "customers", "analysis_texts")
ANALYSIS_GENERATION_CSV_FIELDS = (
    "basic_info",
    "customers",
    "transaction_features",
)


@dataclass(frozen=True)
class CsvCaseInput:
    """One logical case record inside a production CSV file."""

    csv_path: Path
    row_number: int
    values: Dict[str, Any]

    @property
    def identity(self) -> str:
        return f"{self.csv_path}#row={self.row_number}"

    @property
    def name(self) -> str:
        return f"{self.csv_path.stem}_row_{self.row_number:06d}"


@dataclass(frozen=True)
class CsvCaseDeduplication:
    """Deterministic last-row-wins de-duplication result for one CSV batch."""

    records: tuple[CsvCaseInput, ...]
    duplicate_count: int
    duplicate_groups: tuple[Dict[str, Any], ...]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "policy": "same_case_id_last_csv_row_wins",
            "input_count": len(self.records) + self.duplicate_count,
            "output_count": len(self.records),
            "duplicate_count": self.duplicate_count,
            "duplicate_groups": list(self.duplicate_groups),
        }


def load_json(path: Path, default: Any = None) -> Any:
    if not path.exists():
        return default
    with path.open("r", encoding="utf-8-sig") as file:
        return json.load(file)


def load_text(path: Path) -> Optional[str]:
    if not path.exists():
        return None
    return path.read_text(encoding="utf-8-sig")


def to_jsonable(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, dict):
        return {key: to_jsonable(item) for key, item in value.items()}
    if isinstance(value, list):
        return [to_jsonable(item) for item in value]
    if isinstance(value, tuple):
        return [to_jsonable(item) for item in value]
    return value


def dump_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temp_name = tempfile.mkstemp(
        dir=str(path.parent),
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    try:
        with os.fdopen(file_descriptor, "w", encoding="utf-8") as file:
            json.dump(to_jsonable(payload), file, ensure_ascii=False, indent=2)
            file.flush()
        os.replace(temp_name, path)
    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def _load_analysis_texts(input_dir: Path) -> Dict[str, str]:
    analysis_texts = load_json(input_dir / "analysis_texts.json", default=None)
    if analysis_texts is None:
        single_text = load_text(input_dir / "analysis_text1.txt")
        analysis_texts = {"analysis_text1": single_text} if single_text else {}
    if not isinstance(analysis_texts, dict):
        raise ValueError(f"{input_dir / 'analysis_texts.json'} 顶层必须是 JSON 对象")
    return analysis_texts


def _load_case_directory(input_dir: Path) -> Dict[str, Any]:
    """Read independent case files concurrently.

    File reads are intentionally bounded and their results are assembled in a
    stable order.  ``feature_analysis.json`` is the project's structured
    transaction-feature input and remains optional for backwards compatibility.
    """

    loaders: Dict[str, Callable[[], Any]] = {
        "basic_info": lambda: load_json(input_dir / "basic_info.json", default={}),
        "customers": lambda: load_json(input_dir / "customers.json", default=[]),
        "accounts": lambda: load_json(input_dir / "accounts.json", default=[]),
        "other_entities": lambda: load_json(
            input_dir / "other_entities.json", default=[]
        ),
        "evidences": lambda: load_json(input_dir / "evidences.json", default=[]),
        "transaction_features": lambda: load_json(
            input_dir / "feature_analysis.json", default={}
        ),
        "analysis_texts": lambda: _load_analysis_texts(input_dir),
    }
    with ThreadPoolExecutor(
        max_workers=len(loaders),
        thread_name_prefix="case-input",
    ) as executor:
        futures = {name: executor.submit(loader) for name, loader in loaders.items()}
        loaded = {name: futures[name].result() for name in loaders}

    state = {
        "input_dir": str(input_dir),
        **loaded,
    }
    _validate_case_input(state, input_dir)
    return state


def _load_complete_case_file(input_file: Path) -> Dict[str, Any]:
    payload = load_json(input_file, default=None)
    if not isinstance(payload, dict):
        raise ValueError(f"完整案例文件顶层必须是 JSON 对象: {input_file}")

    return _load_complete_case_payload(
        payload,
        source=input_file,
        input_dir=str(input_file.parent),
        input_file=str(input_file),
    )


def _decode_json_column(value: Any, field_name: str, source: Path | str) -> Any:
    """Decode JSON/JSONB columns returned as text or bytes by SQL drivers."""

    if isinstance(value, memoryview):
        value = value.tobytes()
    if isinstance(value, (bytes, bytearray)):
        try:
            value = bytes(value).decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise ValueError(f"{source} 的 {field_name} 字段不是 UTF-8 JSON") from exc
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"{source} 的 {field_name} 字段不是有效 JSON"
                f"（第 {exc.lineno} 行，第 {exc.colno} 列）"
            ) from exc
    return value


def _load_complete_case_payload(
    payload: Mapping[str, Any],
    source: Path | str,
    input_dir: str,
    input_file: str | None = None,
) -> Dict[str, Any]:
    """Normalize one complete case payload without changing extraction semantics."""

    transaction_features = payload.get("transaction_features")
    if transaction_features is None:
        transaction_features = payload.get("feature_analysis")
    if transaction_features is None:
        transaction_features = payload.get("transactions")

    def value_or_default(field_name: str, default: Any) -> Any:
        value = payload.get(field_name)
        if value is None:
            return default
        return _decode_json_column(value, field_name, source)

    state = {
        "input_dir": input_dir,
        "basic_info": value_or_default("basic_info", {}),
        "customers": value_or_default("customers", []),
        "accounts": value_or_default("accounts", []),
        "other_entities": value_or_default("other_entities", []),
        "evidences": value_or_default("evidences", []),
        "transaction_features": (
            {}
            if transaction_features is None
            else _decode_json_column(
                transaction_features,
                "transaction_features",
                source,
            )
        ),
        "analysis_texts": value_or_default("analysis_texts", {}),
    }
    if input_file is not None:
        state["input_file"] = input_file
    _validate_case_input(state, source)
    return state


def _load_three_field_record(
    row: Mapping[str, Any],
    source: Path | str,
    input_source: str,
    input_dir: str = "",
    input_file: str | None = None,
    input_row_number: int | None = None,
) -> Dict[str, Any]:
    missing_fields = [
        name for name in PRODUCTION_CSV_FIELDS if row.get(name) is None
    ]
    if missing_fields:
        raise ValueError(
            f"{source} 缺少必需字段: " + ", ".join(missing_fields)
        )

    state = _load_complete_case_payload(
        row,
        source=source,
        input_dir=input_dir,
        input_file=input_file,
    )
    state["input_source"] = input_source
    if input_row_number is not None:
        state["input_row_number"] = input_row_number
    return state


def load_sql_case_row(row: Mapping[str, Any]) -> Dict[str, Any]:
    """Load one SQL result row containing the three production JSON columns.

    Required column names are ``basic_info``, ``customers`` and
    ``analysis_texts``. Column values may already be decoded JSON objects or
    JSON text/bytes, as returned by common JSON/JSONB database drivers.
    """

    if not isinstance(row, Mapping):
        raise TypeError("SQL 单条输入必须是按列名访问的映射对象")
    return _load_three_field_record(
        row,
        source="<sql-row>",
        input_source="sql_row",
    )


def _load_csv_records(
    csv_path: Path | str,
    required_fields: tuple[str, ...],
    *,
    exact_headers: bool = False,
) -> list[CsvCaseInput]:
    """Read one UTF-8 CSV under a named column contract."""

    source = Path(csv_path).expanduser().absolute()
    if not source.is_file():
        raise FileNotFoundError(f"CSV 案例输入不存在: {source}")

    with source.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        raw_headers = reader.fieldnames
        if not raw_headers:
            raise ValueError(f"CSV 缺少表头: {source}")
        normalized_headers = [str(name).strip() for name in raw_headers]
        if len(set(normalized_headers)) != len(normalized_headers):
            raise ValueError(f"CSV 存在重复表头: {source}")
        missing_headers = [name for name in required_fields if name not in normalized_headers]
        if missing_headers:
            raise ValueError(
                f"CSV 缺少必需列: {source}: " + ", ".join(missing_headers)
            )
        if exact_headers and tuple(normalized_headers) != required_fields:
            raise ValueError(
                f"CSV 表头必须严格为: {', '.join(required_fields)}: {source}"
            )
        header_mapping = dict(zip(raw_headers, normalized_headers))

        records: list[CsvCaseInput] = []
        for row_number, raw_row in enumerate(reader, start=1):
            if raw_row.get(None):
                raise ValueError(
                    f"CSV 第 {row_number} 条记录的字段数超过表头列数: {source}"
                )
            values = {
                normalized: raw_row.get(raw_header)
                for raw_header, normalized in header_mapping.items()
            }
            if not any(str(value or "").strip() for value in values.values()):
                continue
            records.append(
                CsvCaseInput(
                    csv_path=source,
                    row_number=row_number,
                    values=values,
                )
            )

    if not records:
        raise ValueError(f"CSV 中没有案例数据行: {source}")
    return records


def load_csv_case_inputs(csv_path: Path | str) -> list[CsvCaseInput]:
    """Read historical/extraction CSV rows containing existing analysis text."""

    return _load_csv_records(csv_path, PRODUCTION_CSV_FIELDS)


def load_analysis_generation_csv_inputs(
    csv_path: Path | str,
) -> list[CsvCaseInput]:
    """Read new-case rows from the two production source-table projections.

    ``basic_info`` and ``customers`` represent the case-information source;
    ``transaction_features`` represents the precomputed transaction-feature
    source. Existing or placeholder analysis text is deliberately rejected.
    """

    return _load_csv_records(
        csv_path,
        ANALYSIS_GENERATION_CSV_FIELDS,
        exact_headers=True,
    )


def load_csv_report_input(record: CsvCaseInput) -> Dict[str, Any]:
    """Load only the two CSV fields allowed to enter report generation."""

    if not isinstance(record, CsvCaseInput):
        raise TypeError("可疑报告生成输入必须是 CsvCaseInput")
    basic_info = _decode_json_column(
        record.values.get("basic_info"),
        "basic_info",
        record.identity,
    )
    customers = _decode_json_column(
        record.values.get("customers"),
        "customers",
        record.identity,
    )
    if not isinstance(basic_info, dict):
        raise ValueError(f"{record.identity} 的 basic_info 必须是 JSON 对象")
    if not isinstance(customers, list):
        raise ValueError(f"{record.identity} 的 customers 必须是 JSON 数组")
    return {
        "basic_info": basic_info,
        "customers": customers,
    }


def load_csv_transaction_features(record: CsvCaseInput) -> Dict[str, Any]:
    """Decode the transaction-feature table projection for one new case."""

    if not isinstance(record, CsvCaseInput):
        raise TypeError("交易特征输入必须是 CsvCaseInput")
    features = _decode_json_column(
        record.values.get("transaction_features"),
        "transaction_features",
        record.identity,
    )
    if not isinstance(features, dict):
        raise ValueError(f"{record.identity} 的 transaction_features 必须是 JSON 对象")
    return features


def csv_case_id(record: CsvCaseInput) -> str:
    """Return the stable case ID used for CSV de-duplication."""

    basic_info = load_csv_report_input(record)["basic_info"]
    case_id = str(basic_info.get("case_id") or "").strip()
    if not case_id:
        raise ValueError(f"{record.identity} 的 basic_info.case_id 不能为空")
    return case_id


def deduplicate_csv_case_inputs(
    records: Iterable[CsvCaseInput],
) -> CsvCaseDeduplication:
    """Keep the last valid CSV row for each case ID, preserving row order.

    Rows whose case ID cannot be decoded remain in the batch so the extraction
    workflow can report them as isolated row failures instead of aborting all
    otherwise valid cases.
    """

    items = tuple(records)
    if not items:
        raise ValueError("CSV 去重至少需要一条案例记录")

    keys: list[str] = []
    row_numbers_by_case_id: Dict[str, list[int]] = {}
    for record in items:
        try:
            case_id = csv_case_id(record)
        except (TypeError, ValueError):
            case_id = None
        key = case_id if case_id is not None else f"__invalid__:{record.identity}"
        keys.append(key)
        if case_id is not None:
            row_numbers_by_case_id.setdefault(case_id, []).append(record.row_number)

    last_index_by_key = {key: index for index, key in enumerate(keys)}
    selected = tuple(
        record
        for index, (key, record) in enumerate(zip(keys, items))
        if last_index_by_key[key] == index
    )
    duplicate_groups = tuple(
        {
            "case_id": case_id,
            "csv_row_numbers": row_numbers,
            "kept_row_number": row_numbers[-1],
            "discarded_row_numbers": row_numbers[:-1],
        }
        for case_id, row_numbers in sorted(row_numbers_by_case_id.items())
        if len(row_numbers) > 1
    )
    return CsvCaseDeduplication(
        records=selected,
        duplicate_count=len(items) - len(selected),
        duplicate_groups=duplicate_groups,
    )


def case_input_identity(input_case: Path | str | CsvCaseInput) -> str:
    if isinstance(input_case, CsvCaseInput):
        return input_case.identity
    return str(Path(input_case).expanduser().absolute())


def case_input_name(input_case: Path | str | CsvCaseInput) -> str:
    if isinstance(input_case, CsvCaseInput):
        return input_case.name
    path = Path(input_case)
    return path.stem if path.is_file() else path.name


def _validate_case_input(state: Dict[str, Any], source: Path | str) -> None:
    expected_types = {
        "basic_info": dict,
        "customers": list,
        "accounts": list,
        "other_entities": list,
        "evidences": list,
        "analysis_texts": dict,
    }
    for field_name, expected_type in expected_types.items():
        value = state.get(field_name)
        if not isinstance(value, expected_type):
            expected_name = "对象" if expected_type is dict else "数组"
            raise ValueError(
                f"{source} 的 {field_name} 必须是 JSON {expected_name}"
            )

    transaction_features = state.get("transaction_features")
    if not isinstance(transaction_features, (dict, list)):
        raise ValueError(f"{source} 的交易流水结构化数据必须是 JSON 对象或数组")
    invalid_text_fields = [
        name
        for name, value in state["analysis_texts"].items()
        if not isinstance(name, str) or not isinstance(value, str)
    ]
    if invalid_text_fields:
        raise ValueError(f"{source} 的 analysis_texts 字段值必须是字符串")


def load_case_input(
    input_path: Path | str | Mapping[str, Any] | CsvCaseInput,
) -> Dict[str, Any]:
    """Load a directory, JSON file, SQL row, or one logical CSV record."""

    if isinstance(input_path, CsvCaseInput):
        return _load_three_field_record(
            input_path.values,
            source=input_path.identity,
            input_source="csv_row",
            input_dir=str(input_path.csv_path.parent),
            input_file=str(input_path.csv_path),
            input_row_number=input_path.row_number,
        )

    if isinstance(input_path, Mapping):
        return load_sql_case_row(input_path)

    source = Path(input_path)
    if source.suffix.lower() == ".csv":
        raise ValueError(
            "CSV 文件必须使用批处理入口 BankCaseWorkflow.run_csv_batch()；"
            "CSV 中每一行都是独立案例"
        )
    if source.is_file():
        return _load_complete_case_file(source)
    if not source.is_dir():
        raise FileNotFoundError(f"案例输入不存在: {source}")
    return _load_case_directory(source)


def discover_case_inputs(input_root: Path) -> list[Path | CsvCaseInput]:
    """Discover complete case directories/files directly below a batch root."""

    root = Path(input_root)
    if not root.is_dir():
        raise NotADirectoryError(f"批量案例目录不存在: {root}")
    directory_inputs = [
        path
        for path in root.iterdir()
        if path.is_dir()
        and (path / "basic_info.json").is_file()
        and (
            (path / "analysis_texts.json").is_file()
            or (path / "analysis_text1.txt").is_file()
        )
    ]
    file_inputs = []
    for path in root.glob("*.json"):
        try:
            payload = load_json(path, default=None)
        except (OSError, json.JSONDecodeError):
            continue
        if (
            isinstance(payload, dict)
            and isinstance(payload.get("basic_info"), dict)
            and isinstance(payload.get("analysis_texts"), dict)
        ):
            file_inputs.append(path)
    csv_inputs = [
        record
        for csv_path in sorted(root.glob("*.csv"))
        for record in load_csv_case_inputs(csv_path)
    ]
    inputs = sorted(
        [*directory_inputs, *file_inputs, *csv_inputs],
        key=case_input_identity,
    )
    if not inputs:
        raise ValueError(
            "批量目录中未发现生产 CSV、完整案例目录或 JSON 文件"
            f"（CSV 需包含 basic_info、customers、analysis_texts 三列）: {root}"
        )
    return inputs
