"""Workflow state type."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, TypedDict


class WorkflowState(TypedDict, total=False):
    input_dir: str
    basic_info: Dict[str, Any]
    customers: List[Dict[str, Any]]
    accounts: List[Dict[str, Any]]
    other_entities: List[Dict[str, Any]]
    evidences: List[Dict[str, Any]]
    transaction_features: Any
    processed_transaction_features: Any
    transaction_processing_report: Dict[str, Any]
    analysis_texts: Dict[str, str]
    concurrency_report: Dict[str, Any]
    structured_result: Any
    enum_mapping_report: List[Dict[str, Any]]
    structured_backfill_report: List[Dict[str, Any]]
    preprocessed_texts: Dict[str, str]
    structured_sections: Dict[str, List[Dict[str, Any]]]
    text_chunks: Dict[str, List[str]]
    text_cleaning_report: Dict[str, Any]
    coreference_resolved_texts: Dict[str, str]
    coreference_resolved_sections: Dict[str, List[Dict[str, Any]]]
    coreference_chains: List[Dict[str, Any]]
    coreference_resolutions: List[Dict[str, Any]]
    coreference_standalone_entities: List[Dict[str, Any]]
    coreference_review_queue: List[Dict[str, Any]]
    coreference_review_calls: List[Dict[str, Any]]
    coreference_engine_status: Dict[str, Any]
    coreference_report: Dict[str, Any]
    entity_enhanced_texts: Dict[str, str]
    entity_enhanced_sections: Dict[str, List[Dict[str, Any]]]
    entity_mapping_report: Dict[str, Any]
    context_metadata: Dict[str, Any]
    context_metadata_text: str
    context_enhanced_texts: Dict[str, str]
    context_enhanced_sections: Dict[str, List[Dict[str, Any]]]
    event_candidates: List[Dict[str, Any]]
    knowledge_base_recall_results: List[Dict[str, Any]]
    event_extraction_calls: List[Dict[str, Any]]
    event_extraction_mode: str
    event_deduplication_report: List[Dict[str, Any]]
    events: List[Dict[str, Any]]
    relationship_extraction_order: List[Dict[str, Any]]
    relationship_extraction_calls: List[Dict[str, Any]]
    relationship_candidates: List[Dict[str, Any]]
    relationship_candidates_by_category: Dict[str, List[Dict[str, Any]]]
    relationship_candidates_by_type: Dict[str, List[Dict[str, Any]]]
    relationship_deduplication_report: List[Dict[str, Any]]
    relationships: List[Dict[str, Any]]
    final_result: Optional[Any]
