-- Operational AML reasoning foundation:
-- separate pattern definitions/occurrences, risk hypotheses, alternatives,
-- investigation actions, model registry, temporal semantics and frozen fingerprints.

ALTER TABLE event_pattern_definition
    ADD COLUMN IF NOT EXISTS pattern_class VARCHAR(16) NOT NULL DEFAULT 'RISK',
    ADD COLUMN IF NOT EXISTS knowledge_authority_level VARCHAR(2) NOT NULL DEFAULT 'K2',
    ADD COLUMN IF NOT EXISTS authority_source_ref JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE event_pattern_definition
    DROP CONSTRAINT IF EXISTS chk_event_pattern_class;
ALTER TABLE event_pattern_definition
    ADD CONSTRAINT chk_event_pattern_class
    CHECK (pattern_class IN ('RISK','NORMAL'));

ALTER TABLE event_pattern_definition
    DROP CONSTRAINT IF EXISTS chk_event_pattern_authority;
ALTER TABLE event_pattern_definition
    ADD CONSTRAINT chk_event_pattern_authority
    CHECK (knowledge_authority_level IN ('K1','K2','K3','K4','K5'));

CREATE TABLE IF NOT EXISTS behavior_pattern_occurrence (
    id BIGSERIAL PRIMARY KEY,
    occurrence_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    pattern_code VARCHAR(128) NOT NULL,
    pattern_version VARCHAR(32) NOT NULL,
    pattern_class VARCHAR(16) NOT NULL,
    event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    matched_constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    missing_constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    fact_confidence NUMERIC(8,6),
    pattern_confidence NUMERIC(8,6),
    evidence_strength VARCHAR(2) NOT NULL DEFAULT 'E1',
    event_time_start TIMESTAMPTZ,
    event_time_end TIMESTAMPTZ,
    detection_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    inference_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    knowledge_effective_time TIMESTAMPTZ,
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id,pattern_code,pattern_version,input_snapshot_sha256),
    CHECK (pattern_class IN ('RISK','NORMAL')),
    CHECK (evidence_strength IN ('E1','E2','E3','E4','E5')),
    CHECK (fact_confidence IS NULL OR fact_confidence BETWEEN 0 AND 1),
    CHECK (pattern_confidence IS NULL OR pattern_confidence BETWEEN 0 AND 1)
);

CREATE INDEX IF NOT EXISTS idx_behavior_pattern_case
    ON behavior_pattern_occurrence(case_id,status,pattern_class);

CREATE TABLE IF NOT EXISTS risk_event_hypothesis (
    id BIGSERIAL PRIMARY KEY,
    risk_event_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    risk_event_type VARCHAR(128) NOT NULL,
    title VARCHAR(256) NOT NULL,
    summary TEXT NOT NULL,
    behavior_occurrence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    fact_confidence NUMERIC(8,6),
    risk_confidence NUMERIC(8,6) NOT NULL,
    evidence_strength VARCHAR(2) NOT NULL DEFAULT 'E1',
    detection_time TIMESTAMPTZ,
    inference_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    knowledge_effective_time TIMESTAMPTZ,
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id,risk_event_type,input_snapshot_sha256),
    CHECK (fact_confidence IS NULL OR fact_confidence BETWEEN 0 AND 1),
    CHECK (risk_confidence BETWEEN 0 AND 1),
    CHECK (evidence_strength IN ('E1','E2','E3','E4','E5'))
);

CREATE INDEX IF NOT EXISTS idx_risk_event_hypothesis_case
    ON risk_event_hypothesis(case_id,status,review_status);

CREATE TABLE IF NOT EXISTS alternative_explanation (
    id BIGSERIAL PRIMARY KEY,
    explanation_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    alternative_type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    summary TEXT NOT NULL,
    target_risk_event_id VARCHAR(64) REFERENCES risk_event_hypothesis(risk_event_id),
    behavior_occurrence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    supporting_evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    contradicting_evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence NUMERIC(8,6),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id,alternative_type,target_risk_event_id,input_snapshot_sha256),
    CHECK (alternative_type IN ('NORMAL_BUSINESS','ALTERNATIVE_RISK','DATA_QUALITY','CONTRADICTION')),
    CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);

CREATE TABLE IF NOT EXISTS investigation_hypothesis (
    id BIGSERIAL PRIMARY KEY,
    hypothesis_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    risk_event_id VARCHAR(64) REFERENCES risk_event_hypothesis(risk_event_id),
    hypothesis TEXT NOT NULL,
    evidence_needed JSONB NOT NULL DEFAULT '[]'::jsonb,
    recommended_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    blocking BOOLEAN NOT NULL DEFAULT false,
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    resolution TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    UNIQUE(case_id,hypothesis,risk_event_id,input_snapshot_sha256),
    CHECK (blocking=false),
    CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED','DISMISSED'))
);

CREATE TABLE IF NOT EXISTS inference_model_registry (
    id BIGSERIAL UNIQUE,
    model_id VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    model_type VARCHAR(32) NOT NULL,
    purpose VARCHAR(256) NOT NULL,
    training_data_ref JSONB NOT NULL DEFAULT '{}'::jsonb,
    evaluation_metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    owner VARCHAR(64) NOT NULL,
    artifact_digest VARCHAR(128),
    config_digest VARCHAR(128),
    knowledge_authority_level VARCHAR(2) NOT NULL DEFAULT 'K2',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    approval_record JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(model_id,version),
    CHECK (knowledge_authority_level IN ('K1','K2','K3','K4','K5')),
    CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','RETIRED'))
);

CREATE TABLE IF NOT EXISTS case_explanation_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    revision INT NOT NULL,
    data_snapshot_sha256 VARCHAR(64) NOT NULL,
    knowledge_release_ref VARCHAR(128) NOT NULL,
    model_ref VARCHAR(192) NOT NULL,
    pattern_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_policy_version VARCHAR(64) NOT NULL,
    explanation_fingerprint VARCHAR(64) NOT NULL,
    object_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id,revision),
    UNIQUE(case_id,explanation_fingerprint)
);

ALTER TABLE cf_risk_case
    ADD COLUMN IF NOT EXISTS explanation_status VARCHAR(24) NOT NULL DEFAULT 'NOT_GENERATED',
    ADD COLUMN IF NOT EXISTS explanation_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS explanation_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS knowledge_impact_snapshot (
    id BIGSERIAL PRIMARY KEY,
    impact_id VARCHAR(64) NOT NULL UNIQUE,
    source_type VARCHAR(32) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    source_version VARCHAR(32) NOT NULL,
    dependency_graph JSONB NOT NULL,
    affected_pattern_count INT NOT NULL DEFAULT 0,
    affected_scenario_count INT NOT NULL DEFAULT 0,
    affected_case_count INT NOT NULL DEFAULT 0,
    content_sha256 VARCHAR(64) NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE technique_occurrence
    ADD COLUMN IF NOT EXISTS behavior_occurrence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS fact_confidence NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS mapping_confidence NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS risk_confidence NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS evidence_strength VARCHAR(2) NOT NULL DEFAULT 'E1',
    ADD COLUMN IF NOT EXISTS knowledge_authority_level VARCHAR(2) NOT NULL DEFAULT 'K4',
    ADD COLUMN IF NOT EXISTS detection_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS inference_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS knowledge_effective_time TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_technique_confidences') THEN
        ALTER TABLE technique_occurrence ADD CONSTRAINT chk_technique_confidences CHECK (
            (fact_confidence IS NULL OR fact_confidence BETWEEN 0 AND 1)
            AND (mapping_confidence IS NULL OR mapping_confidence BETWEEN 0 AND 1)
            AND (risk_confidence IS NULL OR risk_confidence BETWEEN 0 AND 1)
        );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_technique_evidence_strength') THEN
        ALTER TABLE technique_occurrence ADD CONSTRAINT chk_technique_evidence_strength
            CHECK (evidence_strength IN ('E1','E2','E3','E4','E5'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_technique_authority_level') THEN
        ALTER TABLE technique_occurrence ADD CONSTRAINT chk_technique_authority_level
            CHECK (knowledge_authority_level IN ('K1','K2','K3','K4','K5'));
    END IF;
END $$;

INSERT INTO inference_model_registry
    (model_id,version,model_type,purpose,owner,knowledge_authority_level,status,effective_from)
VALUES
    ('AMLTRIX_CASE_MATTER_BASELINE','1.2.0','RULE_ENGINE',
     '生成行为模式、风险事件、技术映射、替代解释和调查假设',
     'bankgraph','K2','ACTIVE',CURRENT_TIMESTAMP)
ON CONFLICT (model_id,version) DO NOTHING;

INSERT INTO event_pattern_definition
    (pattern_code,pattern_name,pattern_type,scope,owner,status,pattern_class,
     knowledge_authority_level,authority_source_ref)
VALUES
    ('SHORT_WINDOW_COMPOSITE','短时间窗口复合资金行为','DAG','GLOBAL','bankgraph','ACTIVE',
     'RISK','K2','{"source":"BankGraph baseline"}'::jsonb),
    ('TEXT_AMLTRIX_REPORTED_PATTERN','文本陈述的 AMLTRIX 行为组合','DAG','GLOBAL','bankgraph','ACTIVE',
     'RISK','K4','{"source":"AMLTRIX"}'::jsonb),
    ('NORMAL_CASH_INTENSIVE_CYCLE','现金密集行业正常经营循环','DAG','GLOBAL','bankgraph','DRAFT',
     'NORMAL','K2','{"source":"BankGraph local candidate"}'::jsonb)
ON CONFLICT (pattern_code) DO UPDATE SET
    pattern_class=EXCLUDED.pattern_class,
    knowledge_authority_level=EXCLUDED.knowledge_authority_level,
    authority_source_ref=EXCLUDED.authority_source_ref;

INSERT INTO event_pattern_version
    (pattern_code,version,pattern_payload,content_sha256,review_status,created_by,effective_from)
VALUES
    ('SHORT_WINDOW_COMPOSITE','1.0',
     '{"windowSeconds":600,"minimumEvents":2,"groupBy":["coreAccount"],"patternClass":"RISK"}'::jsonb,
     'e9f1114173170156a206653604033208452d4fe89c9f9b43d515ce7508d03cf2','APPROVED','migration-v43',CURRENT_TIMESTAMP),
    ('TEXT_AMLTRIX_REPORTED_PATTERN','1.0',
     '{"minimumExplicitTechniques":1,"observationMode":"REPORTED","patternClass":"RISK"}'::jsonb,
     '4d953085c99af01f42bb64aa037e32f5568cff8f3657220906a0aa7f4852fb62','APPROVED','migration-v43',CURRENT_TIMESTAMP),
    ('NORMAL_CASH_INTENSIVE_CYCLE','1.0',
     '{"patternClass":"NORMAL","requiredContext":["industry","businessPurpose"],"status":"DRAFT"}'::jsonb,
     'e54e9c23830d6c236a01327b98b10f4796a604c348331dab694205720839316b','PENDING','migration-v43',NULL)
ON CONFLICT (pattern_code,version) DO NOTHING;

INSERT INTO sys_menu(parent_id,menu_name,path,component,sort_order,type,permission,visible)
SELECT p.id,'模型注册','/knowledge/models','views/knowledge/overview.vue',9,'MENU','knowledge:view',true
FROM sys_menu p
WHERE p.path='/knowledge'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/knowledge/models');
