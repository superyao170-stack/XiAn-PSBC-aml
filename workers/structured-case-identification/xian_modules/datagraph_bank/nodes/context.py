"""Text enhancement by injecting structured case metadata."""

from __future__ import annotations

from typing import Any, Dict, List


class ContextEnhancer:
    """Embed selected case metadata into analysis text for downstream extraction."""

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        metadata = self._build_metadata(state)
        metadata_text = self._format_metadata(metadata)

        context_texts = {
            name: self._enhance_text(text, metadata_text)
            for name, text in (state.get("entity_enhanced_texts") or {}).items()
        }

        context_sections: Dict[str, List[Dict[str, Any]]] = {}
        for text_name, sections in (state.get("entity_enhanced_sections") or {}).items():
            context_sections[text_name] = []
            for section in sections:
                context_sections[text_name].append(
                    {
                        **section,
                        "text": self._enhance_text(section.get("text") or "", metadata_text),
                        "metadata": metadata,
                    }
                )

        return {
            "context_metadata": metadata,
            "context_metadata_text": metadata_text,
            "context_enhanced_texts": context_texts,
            "context_enhanced_sections": context_sections,
        }

    def _build_metadata(self, state: Dict[str, Any]) -> Dict[str, Any]:
        basic_info = state.get("basic_info") or {}
        customers = state.get("customers") or []
        accounts = state.get("accounts") or []

        return {
            "case": {
                "case_id": basic_info.get("case_id"),
                "case_name": basic_info.get("case_name"),
                "business_domain": basic_info.get("business_domain"),
                "case_type": basic_info.get("case_type"),
                "risk_level": basic_info.get("risk_level"),
                "suspected_crime_type": basic_info.get("suspected_crime_type"),
                "suspicious_transaction_codes": basic_info.get("suspicious_transaction_codes"),
            },
            "customers": [
                {
                    "entity_id": item.get("entity_id"),
                    "customer_name": item.get("customer_name"),
                    "customer_number": item.get("customer_number"),
                    "occupation_industry": item.get("occupation_industry"),
                    "customer_risk_level": item.get("customer_risk_level"),
                }
                for item in customers
            ],
            "accounts": [
                {
                    "entity_id": item.get("entity_id"),
                    "holder_name": item.get("holder_name"),
                    "account_number": item.get("account_number"),
                    "institution": item.get("institution"),
                    "account_type": item.get("account_type"),
                }
                for item in accounts
            ],
        }

    def _format_metadata(self, metadata: Dict[str, Any]) -> str:
        lines = ["【案例元信息】"]
        case = metadata.get("case") or {}
        for key, value in case.items():
            if value:
                lines.append(f"- {key}: {value}")

        if metadata.get("customers"):
            lines.append("【客户实体】")
            for item in metadata["customers"]:
                lines.append(
                    f"- {item.get('entity_id')}: {item.get('customer_name')} / "
                    f"客户号={item.get('customer_number')} / 风险等级={item.get('customer_risk_level')}"
                )

        if metadata.get("accounts"):
            lines.append("【账户实体】")
            for item in metadata["accounts"]:
                lines.append(
                    f"- {item.get('entity_id')}: {item.get('holder_name')} / "
                    f"账号={item.get('account_number')} / 机构={item.get('institution')}"
                )
        return "\n".join(lines)

    def _enhance_text(self, text: str, metadata_text: str) -> str:
        return f"{metadata_text}\n【分析文本】\n{text}"
