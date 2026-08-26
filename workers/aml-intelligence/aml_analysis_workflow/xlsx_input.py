from __future__ import annotations

import re
import posixpath
import zipfile
from pathlib import Path
from xml.etree import ElementTree

from .csv_input import CSV_COLUMNS, _decode, _parse_values
from .errors import InputValidationError
from .models import CaseInput


MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
DOC_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CELL_REFERENCE = re.compile(r"^([A-Z]+)([1-9][0-9]*)$")


def read_xlsx_cases(path: Path) -> list[CaseInput]:
    """Read the only worksheet in an XLSX using the strict three-column contract."""

    workbook_path = Path(path).expanduser().resolve()
    if not workbook_path.is_file():
        raise FileNotFoundError(f"XLSX输入不存在: {workbook_path}")
    try:
        with zipfile.ZipFile(workbook_path) as archive:
            sheet_path = _single_sheet_path(archive, workbook_path)
            shared_strings = _shared_strings(archive)
            rows = _worksheet_rows(archive, sheet_path, shared_strings, workbook_path)
    except (zipfile.BadZipFile, KeyError, ElementTree.ParseError) as exc:
        raise InputValidationError(f"XLSX文件结构无效: {workbook_path}") from exc

    headers = tuple(rows.get(1, {}).get(column, "") for column in range(1, 4))
    if headers != CSV_COLUMNS or any(
        value for column, value in rows.get(1, {}).items() if column > 3
    ):
        raise InputValidationError(
            "XLSX首行表头必须严格为: " + ", ".join(CSV_COLUMNS)
        )

    cases: list[CaseInput] = []
    for row_number in sorted(row for row in rows if row > 1):
        row = rows[row_number]
        if any(value for column, value in row.items() if column > 3):
            raise InputValidationError(
                f"{workbook_path}#row={row_number} 包含三字段以外的内容"
            )
        values = [row.get(column, "") for column in range(1, 4)]
        if not any(value.strip() for value in values):
            continue
        source = f"{workbook_path}#row={row_number}"
        cases.append(
            _parse_values(
                workbook_path,
                row_number,
                _decode(values[0], "basic_info", source, dict),
                _decode(values[1], "customers", source, list),
                _decode(values[2], "transaction_features", source, dict),
            )
        )
    if not cases:
        raise InputValidationError("XLSX中没有案例数据")
    return cases


def _single_sheet_path(archive: zipfile.ZipFile, source: Path) -> str:
    workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
    sheets = workbook.findall(f"{{{MAIN_NS}}}sheets/{{{MAIN_NS}}}sheet")
    if len(sheets) != 1:
        raise InputValidationError(f"{source} 必须且只能包含一个工作表")
    relationship_id = sheets[0].attrib.get(f"{{{DOC_REL_NS}}}id")
    relationships = ElementTree.fromstring(
        archive.read("xl/_rels/workbook.xml.rels")
    )
    for relationship in relationships.findall(f"{{{PKG_REL_NS}}}Relationship"):
        if relationship.attrib.get("Id") == relationship_id:
            target = relationship.attrib.get("Target", "")
            if target.startswith("/"):
                return target.lstrip("/")
            return posixpath.normpath(posixpath.join("xl", target))
    raise InputValidationError(f"{source} 无法定位工作表")


def _shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    return [
        "".join(node.text or "" for node in item.iter(f"{{{MAIN_NS}}}t"))
        for item in root.findall(f"{{{MAIN_NS}}}si")
    ]


def _worksheet_rows(
    archive: zipfile.ZipFile,
    sheet_path: str,
    shared_strings: list[str],
    source: Path,
) -> dict[int, dict[int, str]]:
    root = ElementTree.fromstring(archive.read(sheet_path))
    rows: dict[int, dict[int, str]] = {}
    for cell in root.iter(f"{{{MAIN_NS}}}c"):
        reference = cell.attrib.get("r", "")
        match = CELL_REFERENCE.match(reference)
        if not match:
            raise InputValidationError(f"{source} 包含无效单元格引用: {reference}")
        if cell.find(f"{{{MAIN_NS}}}f") is not None:
            raise InputValidationError(f"{source}!{reference} 不允许使用公式")
        column = _column_number(match.group(1))
        row_number = int(match.group(2))
        cell_type = cell.attrib.get("t", "")
        value_node = cell.find(f"{{{MAIN_NS}}}v")
        if cell_type == "inlineStr":
            value = "".join(
                node.text or "" for node in cell.iter(f"{{{MAIN_NS}}}t")
            )
        elif value_node is None:
            value = ""
        elif cell_type == "s":
            try:
                value = shared_strings[int(value_node.text or "")]
            except (ValueError, IndexError) as exc:
                raise InputValidationError(
                    f"{source}!{reference} 的共享字符串索引无效"
                ) from exc
        else:
            value = value_node.text or ""
        rows.setdefault(row_number, {})[column] = value
    return rows


def _column_number(letters: str) -> int:
    value = 0
    for letter in letters:
        value = value * 26 + ord(letter) - ord("A") + 1
    return value
