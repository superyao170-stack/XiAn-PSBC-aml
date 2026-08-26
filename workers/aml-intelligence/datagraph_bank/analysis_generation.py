"""Generate two case analysis texts from structured data and transaction features."""

from __future__ import annotations

import copy
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

from datagraph_bank.io_utils import dump_json, load_json
from datagraph_bank.llm import DeepSeekLLMClient
from datagraph_bank.models import ENUM_MAPPINGS


ANALYSIS_TEXT_FIELDS = ("analysis_text1", "analysis_text2")
CASE_INPUT_FILES = {
    "basic_info": ("basic_info.json", dict),
    "customers": ("customers.json", list),
    "accounts": ("accounts.json", list),
    "other_entities": ("other_entities.json", list),
    "evidences": ("evidences.json", list),
}


class AnalysisGenerationError(RuntimeError):
    """Raised when analysis text generation cannot produce a valid result."""


@dataclass(frozen=True)
class LoadedFeatureData:
    """Parsed feature data plus information about a safe syntax repair."""

    payload: Any
    repaired_missing_outer_brace: bool = False


def load_feature_data(path: Path) -> LoadedFeatureData:
    """Load JSON feature data, tolerating a missing top-level opening brace.

    Some analysis platforms export a top-level object beginning directly with
    ``"field": ...`` while retaining its closing brace.  The supplied mock file
    has this shape.  Only that narrowly identifiable defect is repaired; other
    malformed JSON still fails with its original location information.
    """

    feature_path = Path(path)
    if not feature_path.is_file():
        raise FileNotFoundError(f"交易特征文件不存在: {feature_path}")

    text = feature_path.read_text(encoding="utf-8-sig").strip()
    if not text:
        raise ValueError(f"交易特征文件为空: {feature_path}")

    try:
        payload = json.loads(text)
        _validate_feature_root(payload, feature_path)
        return LoadedFeatureData(payload=payload)
    except json.JSONDecodeError as original_error:
        if not re.match(r'^"[^"\\]+"\s*:', text):
            raise ValueError(
                _format_json_error("交易特征 JSON 格式错误", feature_path, original_error)
            ) from original_error

        try:
            payload = json.loads("{" + text)
        except json.JSONDecodeError:
            raise ValueError(
                _format_json_error("交易特征 JSON 格式错误", feature_path, original_error)
            ) from original_error

        _validate_feature_root(payload, feature_path)
        return LoadedFeatureData(
            payload=payload,
            repaired_missing_outer_brace=True,
        )


def load_case_information(input_dir: Path) -> Dict[str, Any]:
    """Load structured case fields without reading target analysis text fields."""

    case_dir = Path(input_dir)
    if not case_dir.is_dir():
        raise NotADirectoryError(f"案例目录不存在: {case_dir}")

    case_information: Dict[str, Any] = {}
    populated = False
    for field_name, (file_name, expected_type) in CASE_INPUT_FILES.items():
        file_path = case_dir / file_name
        default: Any = {} if expected_type is dict else []
        value = load_json(file_path, default=default)
        if not isinstance(value, expected_type):
            type_name = "对象" if expected_type is dict else "数组"
            raise ValueError(f"{file_path} 顶层必须是 JSON {type_name}")
        case_information[field_name] = value
        populated = populated or bool(value)

    if not populated:
        expected = "、".join(item[0] for item in CASE_INPUT_FILES.values())
        raise ValueError(f"案例目录中没有可用的结构化数据，预期文件: {expected}")

    enum_labels = _basic_info_enum_labels(case_information["basic_info"])
    if enum_labels:
        case_information["basic_info_enum_labels"] = enum_labels
    return case_information


class AnalysisTextGenerator:
    """Build the governed prompt and validate the two generated text fields."""

    def __init__(
        self,
        llm_client: DeepSeekLLMClient,
        prompt_path: Optional[Path] = None,
        require_transaction_features: bool = True,
    ) -> None:
        self.llm_client = llm_client
        self.require_transaction_features = require_transaction_features
        self.prompt_path = (
            Path(prompt_path)
            if prompt_path
            else Path(__file__).resolve().parents[1]
            / "data"
            / "prompts"
            / "analysis_text_generation.txt"
        )
        self.prompt_template = self._load_prompt_template()

    def build_prompt(
        self,
        case_information: Dict[str, Any],
        transaction_features: Any = None,
    ) -> str:
        """Render only the inputs declared by the selected prompt template."""

        case_json = json.dumps(case_information, ensure_ascii=False, indent=2)
        rendered = self.prompt_template.replace(
            "{{CASE_INFORMATION_JSON}}",
            case_json,
        )
        if "{{TRANSACTION_FEATURES_JSON}}" in rendered:
            feature_json = json.dumps(
                {} if transaction_features is None else transaction_features,
                ensure_ascii=False,
                indent=2,
            )
            rendered = rendered.replace(
                "{{TRANSACTION_FEATURES_JSON}}",
                feature_json,
            )
        return rendered

    def generate(
        self,
        case_information: Dict[str, Any],
        transaction_features: Any = None,
    ) -> Dict[str, str]:
        """Call the configured LLM and return the strict two-field result."""

        if not self.llm_client.chat_available:
            reason = self.llm_client.status().get("init_error") or "大模型不可用"
            raise AnalysisGenerationError(f"无法生成分析文本: {reason}")

        prompt = self.build_prompt(case_information, transaction_features)
        response = self.llm_client.generate_analysis_texts(prompt)
        payload = response.get("payload")
        if payload is None:
            reason = response.get("reason") or "大模型未返回有效结果"
            raise AnalysisGenerationError(f"无法生成分析文本: {reason}")

        return validate_analysis_texts(payload)

    def _load_prompt_template(self) -> str:
        if not self.prompt_path.is_file():
            raise FileNotFoundError(f"分析文本提示词不存在: {self.prompt_path}")
        template = self.prompt_path.read_text(encoding="utf-8-sig")
        placeholders = ["{{CASE_INFORMATION_JSON}}"]
        if self.require_transaction_features:
            placeholders.append("{{TRANSACTION_FEATURES_JSON}}")
        missing = [item for item in placeholders if item not in template]
        if missing:
            raise ValueError(
                f"分析文本提示词缺少占位符: {', '.join(missing)}"
            )
        return template


def validate_analysis_texts(payload: Any) -> Dict[str, str]:
    """Project an LLM payload to the exact workflow input contract."""

    if not isinstance(payload, dict):
        raise AnalysisGenerationError("大模型结果必须是 JSON 对象")

    result: Dict[str, str] = {}
    for field_name in ANALYSIS_TEXT_FIELDS:
        value = payload.get(field_name)
        if not isinstance(value, str) or not value.strip():
            raise AnalysisGenerationError(
                f"大模型结果缺少非空字符串字段 {field_name}"
            )
        result[field_name] = value.strip()

    if result["analysis_text1"] == result["analysis_text2"]:
        raise AnalysisGenerationError("两段分析文本内容相同，无法区分资金流转与疑点分析")
    return result


def write_analysis_texts(
    output_path: Path,
    analysis_texts: Dict[str, str],
    overwrite: bool = False,
) -> None:
    """Write an analysis_texts.json-compatible file with overwrite protection."""

    destination = Path(output_path)
    if destination.exists() and not overwrite:
        raise FileExistsError(
            f"输出文件已存在: {destination}；如确认覆盖，请增加 --overwrite"
        )
    dump_json(destination, validate_analysis_texts(copy.deepcopy(analysis_texts)))


def _validate_feature_root(payload: Any, path: Path) -> None:
    if not isinstance(payload, (dict, list)):
        raise ValueError(f"交易特征顶层必须是 JSON 对象或数组: {path}")
    if not payload:
        raise ValueError(f"交易特征内容为空: {path}")


def _format_json_error(prefix: str, path: Path, error: json.JSONDecodeError) -> str:
    return (
        f"{prefix}: {path}（第 {error.lineno} 行，第 {error.colno} 列: "
        f"{error.msg}）"
    )


def _basic_info_enum_labels(basic_info: Dict[str, Any]) -> Dict[str, str]:
    labels: Dict[str, str] = {}
    for field_name, mapping in ENUM_MAPPINGS.items():
        value = basic_info.get(field_name)
        if value is None:
            continue
        text = str(value).strip()
        code_match = re.match(r"^(\d{2,4})(?:[-_：:\s].*)?$", text)
        if code_match and code_match.group(1) in mapping:
            code = code_match.group(1)
            labels[field_name] = f"{code}-{mapping[code]}"
    return labels
