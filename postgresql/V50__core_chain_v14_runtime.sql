-- Core-chain V1.4 runtime contract.
-- PostgreSQL owns immutable reasoning snapshots; TuGraph remains an asynchronous projection.

CREATE TABLE IF NOT EXISTS graph_node_type_registry (
    node_type VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    plane VARCHAR(32) NOT NULL,
    epistemic_type VARCHAR(32) NOT NULL,
    id_strategy VARCHAR(128) NOT NULL,
    lifecycle_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    visibility_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    schema_version VARCHAR(32) NOT NULL DEFAULT '1.4',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS graph_relation_type_registry (
    relation_type VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    edge_category VARCHAR(24) NOT NULL,
    allowed_source_types JSONB NOT NULL,
    allowed_target_types JSONB NOT NULL,
    cardinality VARCHAR(16),
    participates_in_reasoning BOOLEAN NOT NULL DEFAULT FALSE,
    requires_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    requires_definition_version BOOLEAN NOT NULL DEFAULT FALSE,
    schema_version VARCHAR(32) NOT NULL DEFAULT '1.4',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO graph_node_type_registry
    (node_type,display_name,plane,epistemic_type,id_strategy,visibility_policy)
VALUES
    ('CASE','案件','CASE_CONTAINER','CONTEXT','tenant::workspace::caseId','{"default":true}'),
    ('CUSTOMER','客户','FACT_SUBJECT','OBSERVED','tenant::workspace::caseId::customerId','{"default":true}'),
    ('ACCOUNT','账户','FACT_SUBJECT','OBSERVED','tenant::workspace::caseId::accountId','{"default":true}'),
    ('EVIDENCE','证据','FACT_EVENT','SOURCE','tenant::workspace::caseId::evidenceId','{"default":false}'),
    ('BASE_EVENT','基础事件','FACT_EVENT','OBSERVED','tenant::workspace::caseId::eventId','{"default":true}'),
    ('CANONICAL_EVENT','规范事件','FACT_EVENT','RESOLVED','tenant::workspace::caseId::canonicalEventId','{"default":true}'),
    ('INDICATOR_RESULT','指标结果','DETECTION_REASONING','CALCULATED','tenant::workspace::caseId::calculationId','{"default":false}'),
    ('PATTERN_OCCURRENCE','模式实例','DETECTION_REASONING','DETECTED','tenant::workspace::caseId::occurrenceId','{"default":true}'),
    ('RISK_EVENT_HYPOTHESIS','风险事件假设','DETECTION_REASONING','INFERRED','tenant::workspace::caseId::riskEventId','{"default":true}'),
    ('TECHNIQUE_OCCURRENCE','技术实例','DETECTION_REASONING','MAPPED','tenant::workspace::caseId::occurrenceId','{"default":true}'),
    ('ALTERNATIVE_EXPLANATION','替代解释','DETECTION_REASONING','INFERRED','tenant::workspace::caseId::explanationId','{"default":false}'),
    ('EVENT_FRAME','事件定义','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('INDICATOR_DEFINITION','指标定义','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('PATTERN_DEFINITION','模式定义','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('RISK_EVENT_DEFINITION','风险事件定义','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('APPLICABILITY_RULE','适用规则','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('TECHNIQUE','AMLTRIX技术','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('TACTIC','AMLTRIX战术','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('CONTROL','控制措施','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('RECOMMENDED_ACTION','建议行动','KNOWLEDGE','DEFINED','scope::code::version','{"default":false}'),
    ('MATTER','事理产品','EXPLANATION_REVIEW','NARRATED','tenant::workspace::caseId::matterId','{"default":false}'),
    ('MATTER_CLAIM','事理声明','EXPLANATION_REVIEW','NARRATED','tenant::workspace::caseId::claimId','{"default":false}'),
    ('REVIEW_SUGGESTION','复核建议','EXPLANATION_REVIEW','NARRATED','tenant::workspace::caseId::suggestionId','{"default":false}'),
    ('INVESTIGATION_HYPOTHESIS','调查假设','EXPLANATION_REVIEW','INFERRED','tenant::workspace::caseId::hypothesisId','{"default":false}')
ON CONFLICT (node_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,plane=EXCLUDED.plane,
    epistemic_type=EXCLUDED.epistemic_type,id_strategy=EXCLUDED.id_strategy,
    visibility_policy=EXCLUDED.visibility_policy,schema_version='1.4',
    status='ACTIVE',updated_at=CURRENT_TIMESTAMP;

INSERT INTO graph_relation_type_registry
    (relation_type,display_name,edge_category,allowed_source_types,allowed_target_types,
     cardinality,participates_in_reasoning,requires_evidence,requires_definition_version)
VALUES
    ('CONTAINS_EVENT','包含事件','FACT','["CASE"]','["BASE_EVENT"]','1:N',false,false,false),
    ('MATCHES_BEHAVIOR_PATTERN','匹配模式','INFERENCE','["BASE_EVENT","CANONICAL_EVENT"]','["PATTERN_OCCURRENCE"]','N:M',true,true,false),
    ('SUPPORTS_RISK_EVENT','支持风险','INFERENCE','["PATTERN_OCCURRENCE","INDICATOR_RESULT"]','["RISK_EVENT_HYPOTHESIS"]','N:M',true,true,false),
    ('INTERPRETED_AS','解释为技术','INFERENCE','["RISK_EVENT_HYPOTHESIS"]','["TECHNIQUE_OCCURRENCE"]','N:M',true,true,true),
    ('INSTANCE_OF_EVENT_FRAME','属于事件定义','KNOWLEDGE','["BASE_EVENT","CANONICAL_EVENT"]','["EVENT_FRAME"]','N:1',false,false,true),
    ('INSTANCE_OF_INDICATOR','属于指标定义','KNOWLEDGE','["INDICATOR_RESULT"]','["INDICATOR_DEFINITION"]','N:1',false,false,true),
    ('INSTANCE_OF_PATTERN','属于模式定义','KNOWLEDGE','["PATTERN_OCCURRENCE"]','["PATTERN_DEFINITION"]','N:1',false,false,true),
    ('INSTANCE_OF_RISK_EVENT_TYPE','属于风险定义','KNOWLEDGE','["RISK_EVENT_HYPOTHESIS"]','["RISK_EVENT_DEFINITION"]','N:1',true,false,true),
    ('INSTANCE_OF_TECHNIQUE','属于技术定义','KNOWLEDGE','["TECHNIQUE_OCCURRENCE"]','["TECHNIQUE"]','N:1',false,false,true),
    ('TECHNIQUE_SERVES_TACTIC','服务于战术','KNOWLEDGE','["TECHNIQUE"]','["TACTIC"]','N:M',false,false,true),
    ('HAS_CLAIM','包含声明','NARRATIVE','["MATTER"]','["MATTER_CLAIM"]','1:N',false,false,false),
    ('CLAIM_SUPPORTED_BY','声明依据','NARRATIVE','["MATTER_CLAIM"]',
     '["EVIDENCE","BASE_EVENT","INDICATOR_RESULT","PATTERN_OCCURRENCE","RISK_EVENT_HYPOTHESIS","TECHNIQUE_OCCURRENCE"]',
     'N:M',false,true,false)
ON CONFLICT (relation_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,edge_category=EXCLUDED.edge_category,
    allowed_source_types=EXCLUDED.allowed_source_types,
    allowed_target_types=EXCLUDED.allowed_target_types,
    cardinality=EXCLUDED.cardinality,
    participates_in_reasoning=EXCLUDED.participates_in_reasoning,
    requires_evidence=EXCLUDED.requires_evidence,
    requires_definition_version=EXCLUDED.requires_definition_version,
    schema_version='1.4',status='ACTIVE',updated_at=CURRENT_TIMESTAMP;

ALTER TABLE behavior_pattern_occurrence
    ADD COLUMN IF NOT EXISTS indicator_result_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS node_maturity VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE';

ALTER TABLE risk_event_hypothesis
    ADD COLUMN IF NOT EXISTS supporting_evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS contradicting_evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS risk_event_type_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS risk_event_definition_ref VARCHAR(192),
    ADD COLUMN IF NOT EXISTS definition_binding_method VARCHAR(32),
    ADD COLUMN IF NOT EXISTS definition_binding_confidence NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS node_maturity VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
    ADD COLUMN IF NOT EXISTS review_decision VARCHAR(24) NOT NULL DEFAULT 'PENDING';

UPDATE risk_event_hypothesis r
SET risk_event_type_version=d.version,
    risk_event_definition_ref='RISK_EVENT_TYPE:' || d.risk_event_type || ':v' || d.version,
    definition_binding_method='EXACT_CODE',
    definition_binding_confidence=1.0,
    node_maturity=CASE WHEN r.status='ACTIVE' THEN 'SUPPORTED' ELSE r.node_maturity END
FROM risk_event_type_definition d
WHERE r.risk_event_type=d.risk_event_type
  AND d.status='ACTIVE'
  AND (r.risk_event_type_version IS NULL OR r.risk_event_definition_ref IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM risk_event_type_definition newer
      WHERE newer.risk_event_type=d.risk_event_type AND newer.status='ACTIVE'
        AND newer.version>d.version
  );

ALTER TABLE technique_occurrence
    ADD COLUMN IF NOT EXISTS risk_event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS applicability_rule_ref VARCHAR(192),
    ADD COLUMN IF NOT EXISTS node_maturity VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
    ADD COLUMN IF NOT EXISTS review_decision VARCHAR(24) NOT NULL DEFAULT 'PENDING';

ALTER TABLE case_matter_explanation
    ADD COLUMN IF NOT EXISTS behavior_occurrence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS risk_event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS core_chain_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS knowledge_release_ref VARCHAR(128);

CREATE TABLE IF NOT EXISTS case_core_chain_snapshot (
    chain_id VARCHAR(64) PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    revision INT NOT NULL,
    maturity VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    schema_version VARCHAR(32) NOT NULL DEFAULT '1.4',
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    knowledge_release_ref VARCHAR(128) NOT NULL,
    model_ref VARCHAR(192) NOT NULL,
    node_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    edge_count INT NOT NULL DEFAULT 0,
    primary_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    validation_issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_sha256 VARCHAR(64) NOT NULL,
    projection_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    inference_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at TIMESTAMPTZ,
    UNIQUE(bank_code,workspace_id,case_id,revision)
);

CREATE INDEX IF NOT EXISTS idx_core_chain_case_active
    ON case_core_chain_snapshot(bank_code,workspace_id,case_id,status,revision DESC);

CREATE TABLE IF NOT EXISTS case_core_chain_edge (
    edge_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(64) NOT NULL REFERENCES case_core_chain_snapshot(chain_id) ON DELETE CASCADE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(192) NOT NULL,
    relation_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(192) NOT NULL,
    edge_category VARCHAR(24) NOT NULL,
    path_role VARCHAR(24) NOT NULL,
    edge_origin VARCHAR(32) NOT NULL,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    rule_ref VARCHAR(192),
    confidence NUMERIC(8,6),
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(chain_id,source_type,source_id,relation_type,target_type,target_id)
);

CREATE TABLE IF NOT EXISTS case_matter_claim (
    claim_id VARCHAR(64) PRIMARY KEY,
    matter_id VARCHAR(64) NOT NULL REFERENCES case_matter_explanation(matter_id) ON DELETE CASCADE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    claim_order INT NOT NULL,
    claim_type VARCHAR(24) NOT NULL,
    claim_text TEXT NOT NULL,
    certainty VARCHAR(24) NOT NULL,
    supporting_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    contradicting_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(matter_id,claim_order)
);

CREATE TABLE IF NOT EXISTS case_core_chain_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    chain_id VARCHAR(64) NOT NULL REFERENCES case_core_chain_snapshot(chain_id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projected_at TIMESTAMPTZ
);
