"""Entity enhancement: replacement and reference resolution."""

from __future__ import annotations

import re
from typing import Any, Dict, List, Tuple


class EntityEnhancer:
    """Replace known aliases after the dedicated coreference stage."""

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        aliases = self._build_aliases(state)
        enhanced_texts: Dict[str, str] = {}
        enhanced_sections: Dict[str, List[Dict[str, Any]]] = {}
        replacements: List[Dict[str, Any]] = []

        source_texts = (
            state.get("coreference_resolved_texts")
            or state.get("preprocessed_texts")
            or {}
        )
        source_sections = (
            state.get("coreference_resolved_sections")
            or state.get("structured_sections")
            or {}
        )

        for text_name, text in source_texts.items():
            enhanced, text_replacements = self.enhance_text(text, aliases)
            enhanced_texts[text_name] = enhanced
            replacements.extend(
                {"text_name": text_name, **item} for item in text_replacements
            )

        for text_name, sections in source_sections.items():
            enhanced_sections[text_name] = []
            for section in sections:
                enhanced, text_replacements = self.enhance_text(section["text"], aliases)
                replacements.extend(
                    {
                        "text_name": text_name,
                        "section_id": section["section_id"],
                        **item,
                    }
                    for item in text_replacements
                )
                enhanced_sections[text_name].append({**section, "text": enhanced})

        return {
            "entity_enhanced_texts": enhanced_texts,
            "entity_enhanced_sections": enhanced_sections,
            "entity_mapping_report": {
                "alias_count": len(aliases),
                "replacement_count": len(replacements),
                "replacements": replacements,
            },
        }

    def enhance_text(
        self,
        text: str,
        aliases: List[Tuple[str, str, str]],
    ) -> Tuple[str, List[Dict[str, Any]]]:
        result = text
        replacements: List[Dict[str, Any]] = []
        for alias, entity_id, entity_type in aliases:
            pattern = re.escape(alias)
            matches = list(re.finditer(pattern, result))
            if not matches:
                continue
            result = re.sub(pattern, f"[{entity_id}]", result)
            replacements.append(
                {
                    "alias": alias,
                    "entity_id": entity_id,
                    "entity_type": entity_type,
                    "count": len(matches),
                }
            )
        return result, replacements

    def _build_aliases(self, state: Dict[str, Any]) -> List[Tuple[str, str, str]]:
        aliases: List[Tuple[str, str, str]] = []
        for customer in state.get("customers") or []:
            entity_id = customer.get("entity_id")
            for field_name in ("customer_name", "customer_number", "id_number"):
                value = customer.get(field_name)
                if entity_id and value:
                    aliases.append((str(value), entity_id, "客户"))

        for account in state.get("accounts") or []:
            entity_id = account.get("entity_id")
            for field_name in ("account_number", "bank_card_number"):
                value = account.get(field_name)
                if entity_id and value:
                    aliases.append((str(value), entity_id, "账户"))

        for other in state.get("other_entities") or []:
            entity_id = other.get("entity_id")
            name = other.get("entity_attr_1")
            if entity_id and name:
                aliases.append((str(name), entity_id, "其他实体"))

        unique = {(alias, entity_id, entity_type) for alias, entity_id, entity_type in aliases}
        return sorted(unique, key=lambda item: len(item[0]), reverse=True)
