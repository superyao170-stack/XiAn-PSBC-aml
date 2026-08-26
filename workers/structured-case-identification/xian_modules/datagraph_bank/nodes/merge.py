"""Merge structured data, events and relationships into the final case."""

from __future__ import annotations

from typing import Any, Dict

from datagraph_bank.models import Event, IllegalBehaviorCase, Relationship


class ResultMerger:
    """Final merge node."""

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        structured: IllegalBehaviorCase = state["structured_result"]
        base = structured.model_dump()
        base["events"] = self.public_events(state)
        public_event_names = {
            internal.get("event_id"): public.get("event_name")
            for internal, public in zip(
                state.get("events") or [],
                base["events"],
            )
        }
        base["relationships"] = self.public_relationships(
            state,
            public_event_names,
        )
        base["processing_metadata"] = {
            **(base.get("processing_metadata") or {}),
            "llm_status": state.get("llm_status"),
            "coreference_engine_status": state.get("coreference_engine_status"),
            "coreference_report": state.get("coreference_report"),
            "coreference_manual_review_count": len(
                state.get("coreference_review_queue") or []
            ),
            "event_extraction_mode": state.get("event_extraction_mode"),
            "event_candidate_count": len(state.get("event_candidates") or []),
            "event_count": len(state.get("events") or []),
            "existing_knowledge_base_event_count": sum(
                1
                for event in (state.get("events") or [])
                if "知识库标记：已有" in (event.get("recognition_rule") or "")
            ),
            "new_knowledge_base_event_count": sum(
                1
                for event in (state.get("events") or [])
                if "知识库标记：新增" in (event.get("recognition_rule") or "")
            ),
            "relationship_candidate_count": len(state.get("relationship_candidates") or []),
            "relationship_extraction_order": state.get("relationship_extraction_order"),
            "relationship_candidate_category_counts": {
                category: len(items)
                for category, items in (
                    state.get("relationship_candidates_by_category") or {}
                ).items()
            },
            "relationship_candidate_type_counts": {
                rel_type: len(items)
                for rel_type, items in (state.get("relationship_candidates_by_type") or {}).items()
            },
            "relationship_count": len(state.get("relationships") or []),
            "risk_event_knowledge_base": state.get("risk_event_knowledge_base"),
            "transaction_processing_report": state.get(
                "transaction_processing_report"
            ),
            "concurrency_report": state.get("concurrency_report"),
        }
        return {"final_result": IllegalBehaviorCase(**base)}

    def public_events(self, state: Dict[str, Any]) -> list[Dict[str, str]]:
        """Project internal ID-based events into the public display schema."""

        aliases = self._event_name_display_aliases(state)
        public_events: list[Dict[str, str]] = []
        for event in state.get("events") or []:
            display_event = dict(event)
            event_name = str(display_event.get("event_name") or "")
            for entity_id, display_name in aliases:
                event_name = event_name.replace(f"[{entity_id}]", display_name)
                event_name = event_name.replace(entity_id, display_name)
            display_event["event_name"] = event_name
            public_events.append(Event(**display_event).model_dump())
        return public_events

    def _event_name_display_aliases(
        self,
        state: Dict[str, Any],
    ) -> list[tuple[str, str]]:
        aliases: list[tuple[str, str]] = []
        for customer in state.get("customers") or []:
            entity_id = customer.get("entity_id")
            customer_name = customer.get("customer_name")
            if entity_id and customer_name:
                aliases.append((str(entity_id), f"客户{customer_name}"))

        for account in state.get("accounts") or []:
            entity_id = account.get("entity_id")
            account_number = (
                account.get("account_number")
                or account.get("bank_card_number")
                or account.get("holder_name")
            )
            if entity_id and account_number:
                aliases.append((str(entity_id), f"账户{account_number}"))

        for entity in state.get("other_entities") or []:
            entity_id = entity.get("entity_id")
            entity_name = entity.get("entity_attr_1")
            if entity_id and entity_name:
                aliases.append((str(entity_id), str(entity_name)))

        return sorted(aliases, key=lambda item: len(item[0]), reverse=True)

    def _relationship_with_display_event_names(
        self,
        relationship: Dict[str, Any],
        public_event_names: Dict[Any, Any],
    ) -> Dict[str, Any]:
        display_relationship = dict(relationship)
        source_id = display_relationship.get("source_node_id")
        target_id = display_relationship.get("target_node_id")
        if display_relationship.get("source_node_type") == "事件":
            display_relationship["source_node_name"] = (
                public_event_names.get(source_id)
                or display_relationship.get("source_node_name")
            )
        if display_relationship.get("target_node_type") == "事件":
            display_relationship["target_node_name"] = (
                public_event_names.get(target_id)
                or display_relationship.get("target_node_name")
            )
        return display_relationship

    def public_relationships(
        self,
        state: Dict[str, Any],
        public_event_names: Dict[Any, Any] | None = None,
    ) -> list[Dict[str, str]]:
        """Project internal relationship traces into the public nine fields."""

        if public_event_names is None:
            public_events = self.public_events(state)
            public_event_names = {
                internal.get("event_id"): public.get("event_name")
                for internal, public in zip(
                    state.get("events") or [],
                    public_events,
                )
            }
        return [
            Relationship(
                **self._relationship_with_display_event_names(
                    relationship,
                    public_event_names,
                )
            ).model_dump()
            for relationship in state.get("relationships") or []
        ]
