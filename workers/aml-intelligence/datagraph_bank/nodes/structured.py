"""Structured data processing: enum mapping and text backfill."""

from __future__ import annotations

import copy
import re
from typing import Any, Dict, Iterable, List, Optional, Tuple

from datagraph_bank.models import (
    ENUM_MAPPINGS,
    AccountEntity,
    CaseBasicInfo,
    CustomerEntity,
    Evidence,
    IllegalBehaviorCase,
    OtherEntity,
)


class StructuredDataProcessor:
    """Map JSON records into the output schema and backfill missing fields from text."""

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        basic_info = copy.deepcopy(state.get("basic_info") or {})
        customers = copy.deepcopy(state.get("customers") or [])
        accounts = copy.deepcopy(state.get("accounts") or [])
        other_entities = copy.deepcopy(state.get("other_entities") or [])
        evidences = copy.deepcopy(state.get("evidences") or [])
        analysis_texts = state.get("analysis_texts") or {}

        enum_report = self._map_enums(basic_info)
        backfill_report = self._backfill_from_text(
            analysis_texts=analysis_texts,
            customers=customers,
            accounts=accounts,
        )

        structured_result = IllegalBehaviorCase(
            basic_info=CaseBasicInfo(**basic_info),
            customers=[CustomerEntity(**item) for item in customers],
            accounts=[AccountEntity(**item) for item in accounts],
            other_entities=[OtherEntity(**item) for item in other_entities],
            evidences=[Evidence(**item) for item in evidences],
            events=[],
            relationships=[],
            processing_metadata={
                "enum_mapping_count": len(enum_report),
                "text_backfill_count": len(backfill_report),
            },
        )

        return {
            "basic_info": basic_info,
            "customers": customers,
            "accounts": accounts,
            "other_entities": other_entities,
            "evidences": evidences,
            "structured_result": structured_result,
            "enum_mapping_report": enum_report,
            "structured_backfill_report": backfill_report,
        }

    def _map_enums(self, basic_info: Dict[str, Any]) -> List[Dict[str, Any]]:
        report: List[Dict[str, Any]] = []
        for field_name, mapping in ENUM_MAPPINGS.items():
            original = basic_info.get(field_name)
            normalized = self._normalize_enum_value(original, mapping)
            if normalized and normalized != original:
                basic_info[field_name] = normalized
                report.append(
                    {
                        "field": field_name,
                        "original": original,
                        "mapped": normalized,
                        "reason": "枚举值按代码表标准化为 code-label 格式",
                    }
                )
        return report

    def _normalize_enum_value(self, value: Any, mapping: Dict[str, str]) -> Optional[str]:
        if value is None:
            return None
        text = str(value).strip()
        if not text:
            return None
        code_match = re.match(r"^(\d{2,4})(?:[-_：:\s].*)?$", text)
        if code_match and code_match.group(1) in mapping:
            code = code_match.group(1)
            return f"{code}-{mapping[code]}"
        for code, label in mapping.items():
            if text == label or text.endswith(label):
                return f"{code}-{label}"
        return text

    def _backfill_from_text(
        self,
        analysis_texts: Dict[str, str],
        customers: List[Dict[str, Any]],
        accounts: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        report: List[Dict[str, Any]] = []
        joined = "\n".join(text for text in analysis_texts.values() if isinstance(text, str))

        profile = self._extract_front_profile(joined)
        if profile:
            target_customer = self._find_customer(customers, profile)
            if target_customer is not None:
                field_map = {
                    "name": "customer_name",
                    "customer_number": "customer_number",
                    "gender": "gender",
                    "nationality": "nationality",
                    "id_type": "id_type",
                    "id_number": "id_number",
                    "contact_phone": "contact_phone",
                    "address": "address",
                    "income_range": "income_range",
                }
                for src_field, dst_field in field_map.items():
                    self._fill_if_empty(
                        target_customer,
                        dst_field,
                        profile.get(src_field),
                        "analysis_texts:front_profile",
                        report,
                    )

        for account in accounts:
            holder_name = account.get("holder_name")
            if holder_name and holder_name in joined:
                open_info = self._extract_open_info(joined, holder_name)
                for dst_field, value in open_info.items():
                    self._fill_if_empty(account, dst_field, value, "analysis_texts:open_info", report)

        return report

    def _extract_front_profile(self, text: str) -> Dict[str, Optional[str]]:
        patterns: Dict[str, str] = {
            "name": r"姓名(?:\(名称\))?[:：]\s*([^,，\n]+)",
            "customer_number": r"客户号[:：]?\s*([0-9*]+)",
            "gender": r"性别[:：]\s*([^,，\n]+)",
            "nationality": r"国籍[:：]\s*([^,，\n]+)",
            "id_type": r"证件类型[:：]\s*([^,，\n]+)",
            "id_number": r"证件号(?:码)?[:：]\s*([0-9A-Za-z*]+)",
            "contact_phone": r"联系方式[:：]\s*([0-9*\-]+)",
            "address": r"地址(?:\(其他地址\))?[:：]\s*([^\"【\n]+)",
            "income_range": r"年收入\s*([0-9]+[-~至到][0-9]+万)",
        }
        extracted: Dict[str, Optional[str]] = {}
        for field_name, pattern in patterns.items():
            match = re.search(pattern, text)
            extracted[field_name] = match.group(1).strip() if match else None
        return {key: value for key, value in extracted.items() if value}

    def _extract_open_info(self, text: str, holder_name: str) -> Dict[str, Optional[str]]:
        window = self._nearest_window(text, holder_name, size=450)
        date_match = re.search(r"于\s*(\d{4})年(\d{1,2})月(\d{1,2})日", window)
        inst_match = re.search(r"由([^，,。；;]+?支行)机构?开立", window)
        account_match = re.search(r"(?:账户|账号|客户号)\s*([0-9*]{6,})", window)
        return {
            "account_open_date": self._date_from_match(date_match),
            "institution": inst_match.group(1).strip() if inst_match else None,
            "bank_info": inst_match.group(1).strip() if inst_match else None,
            "account_number": account_match.group(1).strip() if account_match else None,
        }

    def _find_customer(
        self,
        customers: List[Dict[str, Any]],
        profile: Dict[str, Optional[str]],
    ) -> Optional[Dict[str, Any]]:
        for key in ("id_number", "customer_number", "name"):
            value = profile.get(key)
            if not value:
                continue
            for customer in customers:
                if value in {
                    customer.get("id_number"),
                    customer.get("customer_number"),
                    customer.get("customer_name"),
                }:
                    return customer
        return customers[0] if customers else None

    def _fill_if_empty(
        self,
        record: Dict[str, Any],
        field_name: str,
        value: Optional[str],
        source: str,
        report: List[Dict[str, Any]],
    ) -> None:
        if value is None or value == "":
            return
        if record.get(field_name):
            return
        record[field_name] = value
        report.append(
            {
                "entity_id": record.get("entity_id"),
                "field": field_name,
                "value": value,
                "source": source,
                "reason": "结构化字段为空，从分析文本回填",
            }
        )

    def _nearest_window(self, text: str, needle: str, size: int = 300) -> str:
        position = text.find(needle)
        if position < 0:
            return text[: size * 2]
        start = max(0, position - size)
        end = min(len(text), position + size)
        return text[start:end]

    def _date_from_match(self, match: Optional[re.Match[str]]) -> Optional[str]:
        if not match:
            return None
        year, month, day = match.groups()
        return f"{int(year):04d}-{int(month):02d}-{int(day):02d}"
