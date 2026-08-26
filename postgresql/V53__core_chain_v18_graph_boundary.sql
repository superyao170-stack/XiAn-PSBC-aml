-- Core-chain V1.8 graph boundary:
-- TuGraph stores only the 11 Event Graph and 4 Reasoning Graph instance types.
-- NarrativeProjection, KnowledgeRef, KnowledgeBindingRecord and schema facets
-- remain in PostgreSQL and are returned as separate API domains.

UPDATE graph_node_type_registry
SET status='RETIRED', updated_at=CURRENT_TIMESTAMP
WHERE status='ACTIVE';

INSERT INTO graph_node_type_registry
    (node_type,display_name,plane,epistemic_type,id_strategy,visibility_policy,
     schema_version,status)
VALUES
    ('CASE','案例','CASE_CONTAINER','CONTEXT','tenant::workspace::caseId','{"default":true}','1.8','ACTIVE'),
    ('CUSTOMER','客户','FACT_SUBJECT','OBSERVED','tenant::workspace::caseId::customerId','{"default":true}','1.8','ACTIVE'),
    ('ORGANIZATION','机构','FACT_SUBJECT','OBSERVED','tenant::workspace::caseId::organizationId','{"default":true}','1.8','ACTIVE'),
    ('MERCHANT','商户','FACT_SUBJECT','OBSERVED','tenant::workspace::caseId::merchantId','{"default":true}','1.8','ACTIVE'),
    ('ACCOUNT','账户','FACT_ASSET','OBSERVED','tenant::workspace::caseId::accountId','{"default":true}','1.8','ACTIVE'),
    ('WALLET','钱包','FACT_ASSET','OBSERVED','tenant::workspace::caseId::walletId','{"default":true}','1.8','ACTIVE'),
    ('DEVICE','设备','FACT_ENVIRONMENT','OBSERVED','tenant::workspace::caseId::deviceId','{"default":true}','1.8','ACTIVE'),
    ('IP_ADDRESS','IP地址','FACT_ENVIRONMENT','OBSERVED','tenant::workspace::caseId::ipAddress','{"default":true}','1.8','ACTIVE'),
    ('ADDRESS','地址','FACT_ENVIRONMENT','OBSERVED','tenant::workspace::caseId::addressId','{"default":true}','1.8','ACTIVE'),
    ('EVIDENCE','证据','FACT_EVENT','SOURCE','tenant::workspace::caseId::evidenceId','{"default":true}','1.8','ACTIVE'),
    ('EVENT','事件','FACT_EVENT','OBSERVED','tenant::workspace::caseId::eventId','{"default":true}','1.8','ACTIVE'),
    ('INDICATOR_RESULT','指标结果','REASONING_GRAPH','CALCULATED','tenant::workspace::caseId::calculationId','{"default":true}','1.8','ACTIVE'),
    ('BEHAVIOR_PATTERN','行为模式','REASONING_GRAPH','DETECTED','tenant::workspace::caseId::occurrenceId','{"default":true}','1.8','ACTIVE'),
    ('RISK_HYPOTHESIS','风险假设','REASONING_GRAPH','INFERRED','tenant::workspace::caseId::riskHypothesisId','{"default":true}','1.8','ACTIVE'),
    ('INVESTIGATION_HYPOTHESIS','调查假设','REASONING_GRAPH','INFERRED','tenant::workspace::caseId::hypothesisId','{"default":true}','1.8','ACTIVE')
ON CONFLICT (node_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,
    plane=EXCLUDED.plane,
    epistemic_type=EXCLUDED.epistemic_type,
    id_strategy=EXCLUDED.id_strategy,
    visibility_policy=EXCLUDED.visibility_policy,
    schema_version='1.8',
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO graph_relation_type_registry
    (relation_type,display_name,edge_category,allowed_source_types,allowed_target_types,
     cardinality,participates_in_reasoning,requires_evidence,requires_definition_version,
     schema_version,status)
VALUES
    ('CONTAINS_EVENT','包含事件','FACT','["CASE"]','["EVENT"]','1:N',false,false,false,'1.8','ACTIVE'),
    ('MATCHES_BEHAVIOR_PATTERN','匹配行为模式','INFERENCE','["EVENT"]','["BEHAVIOR_PATTERN"]','N:M',true,true,false,'1.8','ACTIVE'),
    ('SUPPORTS_BEHAVIOR_PATTERN','指标支持行为模式','INFERENCE','["INDICATOR_RESULT"]','["BEHAVIOR_PATTERN"]','N:M',true,true,false,'1.8','ACTIVE'),
    ('SUPPORTS_RISK_HYPOTHESIS','支持风险假设','INFERENCE','["BEHAVIOR_PATTERN","INDICATOR_RESULT"]','["RISK_HYPOTHESIS"]','N:M',true,true,false,'1.8','ACTIVE'),
    ('RAISES_INVESTIGATION_HYPOTHESIS','提出调查假设','INFERENCE','["RISK_HYPOTHESIS"]','["INVESTIGATION_HYPOTHESIS"]','1:N',true,true,false,'1.8','ACTIVE'),
    ('PRECEDES','先于','FACT','["EVENT"]','["EVENT"]','N:M',false,true,false,'1.8','ACTIVE'),
    ('FUNDS_FLOW_TO','资金流向','FACT','["EVENT"]','["EVENT"]','N:M',false,true,false,'1.8','ACTIVE'),
    ('SAME_SESSION_NEXT','同会话后继','FACT','["EVENT"]','["EVENT"]','N:M',false,true,false,'1.8','ACTIVE')
ON CONFLICT (relation_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,
    edge_category=EXCLUDED.edge_category,
    allowed_source_types=EXCLUDED.allowed_source_types,
    allowed_target_types=EXCLUDED.allowed_target_types,
    cardinality=EXCLUDED.cardinality,
    participates_in_reasoning=EXCLUDED.participates_in_reasoning,
    requires_evidence=EXCLUDED.requires_evidence,
    requires_definition_version=EXCLUDED.requires_definition_version,
    schema_version='1.8',
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

UPDATE graph_relation_type_registry
SET status='RETIRED', updated_at=CURRENT_TIMESTAMP
WHERE schema_version<>'1.8' AND status='ACTIVE';

