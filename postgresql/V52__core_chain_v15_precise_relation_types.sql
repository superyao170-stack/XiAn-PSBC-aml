-- Register the precise fact-resolution, indicator-support and narrative edges
-- used by the V1.5 connected core-chain graph.

INSERT INTO graph_relation_type_registry
    (relation_type,display_name,edge_category,allowed_source_types,allowed_target_types,
     cardinality,participates_in_reasoning,requires_evidence,requires_definition_version,
     schema_version)
VALUES
    ('RESOLVES_TO_CANONICAL_EVENT','解析为规范事件','FACT',
     '["BASE_EVENT"]','["CANONICAL_EVENT"]','N:1',false,false,false,'1.5'),
    ('SUPPORTS_BEHAVIOR_PATTERN','指标支持模式','INFERENCE',
     '["INDICATOR_RESULT"]','["PATTERN_OCCURRENCE"]','N:M',true,true,false,'1.5'),
    ('SUPPORTS_TECHNIQUE_MAPPING','指标支持技术映射','INFERENCE',
     '["INDICATOR_RESULT"]','["TECHNIQUE_OCCURRENCE"]','N:M',true,true,false,'1.5'),
    ('SUMMARIZES_EVENT','事理概括事件','NARRATIVE',
     '["MATTER"]','["BASE_EVENT"]','N:M',false,true,false,'1.5'),
    ('SUMMARIZES_PATTERN','事理概括模式','NARRATIVE',
     '["MATTER"]','["PATTERN_OCCURRENCE"]','N:M',false,true,false,'1.5'),
    ('SUMMARIZES_RISK','事理概括风险','NARRATIVE',
     '["MATTER"]','["RISK_EVENT_HYPOTHESIS"]','N:M',false,true,false,'1.5'),
    ('SUMMARIZES_TECHNIQUE','事理概括技术','NARRATIVE',
     '["MATTER"]','["TECHNIQUE_OCCURRENCE"]','N:M',false,true,false,'1.5')
ON CONFLICT (relation_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,
    edge_category=EXCLUDED.edge_category,
    allowed_source_types=EXCLUDED.allowed_source_types,
    allowed_target_types=EXCLUDED.allowed_target_types,
    cardinality=EXCLUDED.cardinality,
    participates_in_reasoning=EXCLUDED.participates_in_reasoning,
    requires_evidence=EXCLUDED.requires_evidence,
    requires_definition_version=EXCLUDED.requires_definition_version,
    schema_version='1.5',
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;
