-- Other framework entities are sourced case facts, including objects without
-- an event role. Preserve their formal category in the graph metadata.
INSERT INTO graph_node_type_registry
    (node_type,display_name,plane,epistemic_type,id_strategy,visibility_policy,
     schema_version,status,graph_domain,object_semantics,description,
     required_properties,display_property,metadata_version,updated_by)
VALUES
    ('OTHER_ENTITY','其他实体','FACT_ENVIRONMENT','OBSERVED',
     'tenant::workspace::caseId::entityId','{"default":true}',
     '1.8','ACTIVE','EVENT_GRAPH','INSTANCE',
     '上传材料中明确出现的其他实体，具体类别由 entity_type 描述。',
     '["instanceId","nodeType","caseId"]','name',1,'migration-v126')
ON CONFLICT (node_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,status='ACTIVE',schema_version='1.8',
    graph_domain=EXCLUDED.graph_domain,object_semantics='INSTANCE',
    description=EXCLUDED.description,updated_by=EXCLUDED.updated_by,
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO graph_relation_type_registry
    (relation_type,display_name,edge_category,allowed_source_types,allowed_target_types,
     cardinality,participates_in_reasoning,requires_evidence,requires_definition_version,
     schema_version,status,graph_domain,description,metadata_version,updated_by,
     semantic_level,direction_semantics,required_edge_properties,framework_category_code)
VALUES
    ('INVOLVES_ENTITY','涉及其他实体','FACT','["CASE","EVENT"]','["OTHER_ENTITY"]',
     '1:N',false,true,false,'1.8','ACTIVE','EVENT_GRAPH',
     '案例材料或事件明确涉及的其他实体；案例归属不表示参与了某个事件。',
     1,'migration-v126','ENTITY_OR_EVENT_FACT','案例或事件 → 其他实体',
     '["edgeOrigin","sourceRef"]','INVOLVES')
ON CONFLICT (relation_type) DO UPDATE SET
    allowed_source_types=EXCLUDED.allowed_source_types,
    allowed_target_types=EXCLUDED.allowed_target_types,
    status='ACTIVE',schema_version='1.8',updated_by=EXCLUDED.updated_by,
    updated_at=CURRENT_TIMESTAMP;

UPDATE graph_relation_type_registry
SET allowed_source_types='["CASE","CUSTOMER","ORGANIZATION","MERCHANT","ACCOUNT","WALLET","DEVICE","IP_ADDRESS","ADDRESS","OTHER_ENTITY","EVENT","EVIDENCE"]'::jsonb,
    allowed_target_types='["CASE","CUSTOMER","ORGANIZATION","MERCHANT","ACCOUNT","WALLET","DEVICE","IP_ADDRESS","ADDRESS","OTHER_ENTITY","EVENT","EVIDENCE"]'::jsonb,
    updated_by='migration-v126',updated_at=CURRENT_TIMESTAMP
WHERE relation_type='ASSOCIATED_WITH'
  AND NOT allowed_source_types @> '["OTHER_ENTITY"]'::jsonb;
