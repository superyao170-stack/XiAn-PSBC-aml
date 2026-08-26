-- V1.8 frozen domain records and precise event relations.

CREATE TABLE IF NOT EXISTS case_core_chain_node (
    chain_id VARCHAR(64) NOT NULL REFERENCES case_core_chain_snapshot(chain_id) ON DELETE CASCADE,
    node_key VARCHAR(192) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    canonical_type VARCHAR(64) NOT NULL,
    legacy_type VARCHAR(64),
    graph_plane VARCHAR(32) NOT NULL,
    display_name TEXT,
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(chain_id,node_key)
);

CREATE INDEX IF NOT EXISTS idx_core_chain_node_case
    ON case_core_chain_node(bank_code,workspace_id,case_id,canonical_type);

CREATE TABLE IF NOT EXISTS case_core_chain_knowledge_ref (
    chain_id VARCHAR(64) NOT NULL REFERENCES case_core_chain_snapshot(chain_id) ON DELETE CASCADE,
    ref_id VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(64) NOT NULL,
    owner_id VARCHAR(192) NOT NULL,
    definition_type VARCHAR(64) NOT NULL,
    definition_code VARCHAR(128) NOT NULL,
    definition_version VARCHAR(32) NOT NULL,
    release_ref VARCHAR(128) NOT NULL,
    effective_time TIMESTAMPTZ,
    definition_sha256 VARCHAR(64) NOT NULL,
    binding_method VARCHAR(32),
    binding_confidence NUMERIC(8,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(chain_id,ref_id)
);

CREATE INDEX IF NOT EXISTS idx_core_chain_kref_owner
    ON case_core_chain_knowledge_ref(chain_id,owner_type,owner_id);

CREATE TABLE IF NOT EXISTS case_core_chain_narrative_ref (
    chain_id VARCHAR(64) NOT NULL REFERENCES case_core_chain_snapshot(chain_id) ON DELETE CASCADE,
    matter_id VARCHAR(64) NOT NULL REFERENCES case_matter_explanation(matter_id),
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    claim_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    explains_risk_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    supporting_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    contradicting_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(chain_id,matter_id)
);

CREATE TABLE IF NOT EXISTS case_event_relation (
    relation_id VARCHAR(64) PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    source_event_id VARCHAR(64) NOT NULL REFERENCES cf_risk_event(event_id),
    relation_type VARCHAR(32) NOT NULL,
    target_event_id VARCHAR(64) NOT NULL REFERENCES cf_risk_event(event_id),
    edge_origin VARCHAR(32) NOT NULL,
    source_ref JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_time TIMESTAMPTZ,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(bank_code,workspace_id,case_id,source_event_id,relation_type,target_event_id),
    CHECK (source_event_id<>target_event_id),
    CHECK (relation_type IN ('PRECEDES','FUNDS_FLOW_TO','SAME_SESSION_NEXT'))
);

CREATE INDEX IF NOT EXISTS idx_case_event_relation_case
    ON case_event_relation(bank_code,workspace_id,case_id,relation_type);

