-- Semantic assets, reproducible analytics, and secure inter-institution exchange MVP.

CREATE TABLE IF NOT EXISTS ontology_term (
    id BIGSERIAL PRIMARY KEY,
    term_id VARCHAR(64) NOT NULL,
    term_version INT NOT NULL,
    bank_code VARCHAR(32),
    namespace VARCHAR(128) NOT NULL,
    term_code VARCHAR(128) NOT NULL,
    term_name VARCHAR(256) NOT NULL,
    term_type VARCHAR(32) NOT NULL,
    parent_term_id VARCHAR(64),
    definition TEXT,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    constraints JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (term_id, term_version),
    UNIQUE (namespace, term_code, term_version)
);

CREATE INDEX IF NOT EXISTS idx_ontology_term_lookup
    ON ontology_term(namespace, term_code, status, term_version DESC);

CREATE TABLE IF NOT EXISTS semantic_frame_definition (
    id BIGSERIAL PRIMARY KEY,
    frame_id VARCHAR(64) NOT NULL,
    frame_version INT NOT NULL,
    bank_code VARCHAR(32),
    frame_code VARCHAR(128) NOT NULL,
    frame_name VARCHAR(256) NOT NULL,
    parent_frame_id VARCHAR(64),
    scenario_code VARCHAR(64),
    description TEXT,
    json_schema JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (frame_id, frame_version),
    UNIQUE (frame_code, frame_version)
);

CREATE TABLE IF NOT EXISTS semantic_slot_definition (
    id BIGSERIAL PRIMARY KEY,
    frame_id VARCHAR(64) NOT NULL,
    frame_version INT NOT NULL,
    slot_code VARCHAR(128) NOT NULL,
    slot_name VARCHAR(256) NOT NULL,
    value_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    multi_valued BOOLEAN NOT NULL DEFAULT false,
    target_term_id VARCHAR(64),
    target_frame_id VARCHAR(64),
    facet_definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_rule JSONB NOT NULL DEFAULT '{}'::jsonb,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (frame_id, frame_version, slot_code),
    FOREIGN KEY (frame_id, frame_version)
        REFERENCES semantic_frame_definition(frame_id, frame_version) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS indicator_definition (
    id BIGSERIAL PRIMARY KEY,
    indicator_code VARCHAR(128) NOT NULL UNIQUE,
    indicator_name VARCHAR(256) NOT NULL,
    dimension_code VARCHAR(32) NOT NULL,
    description TEXT,
    value_type VARCHAR(32) NOT NULL DEFAULT 'NUMBER',
    unit VARCHAR(32),
    bank_code VARCHAR(32),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_indicator_dimension CHECK
        (dimension_code IN ('BEHAVIOR','SUBJECT','FUND','TIME','CONTEXT'))
);

CREATE TABLE IF NOT EXISTS indicator_version (
    id BIGSERIAL PRIMARY KEY,
    indicator_code VARCHAR(128) NOT NULL REFERENCES indicator_definition(indicator_code),
    version INT NOT NULL,
    expression JSONB NOT NULL,
    input_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    threshold_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    algorithm_id VARCHAR(64),
    algorithm_version VARCHAR(32),
    execution_package_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (indicator_code, version)
);

CREATE TABLE IF NOT EXISTS scenario_indicator_binding (
    id BIGSERIAL PRIMARY KEY,
    binding_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    indicator_code VARCHAR(128) NOT NULL REFERENCES indicator_definition(indicator_code),
    indicator_version INT NOT NULL,
    weight NUMERIC(10,6) NOT NULL DEFAULT 1,
    required BOOLEAN NOT NULL DEFAULT false,
    execution_package_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bank_code, scenario_code, indicator_code, indicator_version),
    FOREIGN KEY (indicator_code, indicator_version)
        REFERENCES indicator_version(indicator_code, version)
);

CREATE TABLE IF NOT EXISTS scenario_graph_view (
    id BIGSERIAL PRIMARY KEY,
    view_id VARCHAR(64) NOT NULL,
    view_version INT NOT NULL,
    bank_code VARCHAR(32),
    scenario_code VARCHAR(64) NOT NULL,
    view_name VARCHAR(256) NOT NULL,
    node_filter JSONB NOT NULL DEFAULT '{}'::jsonb,
    edge_filter JSONB NOT NULL DEFAULT '{}'::jsonb,
    weight_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    query_template TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (view_id, view_version)
);

CREATE TABLE IF NOT EXISTS indicator_calculation_result (
    id BIGSERIAL PRIMARY KEY,
    calculation_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    indicator_code VARCHAR(128) NOT NULL,
    indicator_version INT NOT NULL,
    numeric_value NUMERIC(24,8),
    text_value TEXT,
    risk_level VARCHAR(16),
    explanation JSONB NOT NULL DEFAULT '{}'::jsonb,
    input_snapshot_sha256 CHAR(64) NOT NULL,
    algorithm_execution_id VARCHAR(64),
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (indicator_code, indicator_version)
        REFERENCES indicator_version(indicator_code, version)
);

CREATE INDEX IF NOT EXISTS idx_indicator_result_subject
    ON indicator_calculation_result(bank_code, subject_type, subject_id, calculated_at DESC);

CREATE TABLE IF NOT EXISTS analytics_dataset_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(128),
    schema_version VARCHAR(32),
    feature_definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot_sha256 CHAR(64) NOT NULL,
    record_count BIGINT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analytics_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    run_type VARCHAR(32) NOT NULL,
    input_snapshot_id VARCHAR(64) REFERENCES analytics_dataset_snapshot(snapshot_id),
    model_id VARCHAR(64),
    model_version VARCHAR(32),
    algorithm_id VARCHAR(64),
    algorithm_version VARCHAR(32),
    code_hash CHAR(64),
    request_payload JSONB NOT NULL,
    result_payload JSONB,
    metrics JSONB,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    error_message TEXT,
    created_by VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_analytics_run_bank_time
    ON analytics_run(bank_code, started_at DESC);

CREATE TABLE IF NOT EXISTS inference_evidence (
    id BIGSERIAL PRIMARY KEY,
    evidence_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL REFERENCES analytics_run(run_id) ON DELETE CASCADE,
    subject_id VARCHAR(128),
    score NUMERIC(10,6),
    decision VARCHAR(32),
    reason_codes TEXT[],
    contributions JSONB NOT NULL DEFAULT '[]'::jsonb,
    path_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_subgraph_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analytics_replay (
    id BIGSERIAL PRIMARY KEY,
    replay_id VARCHAR(64) NOT NULL UNIQUE,
    original_run_id VARCHAR(64) NOT NULL REFERENCES analytics_run(run_id),
    replay_run_id VARCHAR(64) NOT NULL REFERENCES analytics_run(run_id),
    comparison JSONB NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exchange_partner (
    id BIGSERIAL PRIMARY KEY,
    partner_code VARCHAR(64) NOT NULL UNIQUE,
    partner_name VARCHAR(256) NOT NULL,
    partner_type VARCHAR(24) NOT NULL,
    certificate_fingerprint VARCHAR(128) NOT NULL,
    signing_key_ref VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    allowed_message_types TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exchange_message (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL UNIQUE,
    partner_code VARCHAR(64) NOT NULL REFERENCES exchange_partner(partner_code),
    direction VARCHAR(16) NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    signature TEXT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (partner_code, nonce)
);

CREATE TABLE IF NOT EXISTS exchange_nonce (
    id BIGSERIAL PRIMARY KEY,
    partner_code VARCHAR(64) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    request_timestamp TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (partner_code, nonce)
);

CREATE INDEX IF NOT EXISTS idx_exchange_nonce_expiry ON exchange_nonce(expires_at);

CREATE TABLE IF NOT EXISTS anonymous_identity_map (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    identity_type VARCHAR(32) NOT NULL,
    anonymous_id CHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bank_code, identity_type, anonymous_id, key_version)
);

CREATE TABLE IF NOT EXISTS privacy_budget_ledger (
    id BIGSERIAL PRIMARY KEY,
    budget_id VARCHAR(64) NOT NULL UNIQUE,
    partner_code VARCHAR(64) NOT NULL,
    dataset_code VARCHAR(128) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    epsilon_allocated NUMERIC(12,6) NOT NULL,
    epsilon_consumed NUMERIC(12,6) NOT NULL DEFAULT 0,
    delta_allocated NUMERIC(18,12) NOT NULL DEFAULT 0,
    delta_consumed NUMERIC(18,12) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_privacy_epsilon CHECK
        (epsilon_allocated > 0 AND epsilon_consumed >= 0 AND epsilon_consumed <= epsilon_allocated),
    CONSTRAINT chk_privacy_delta CHECK
        (delta_allocated >= 0 AND delta_consumed >= 0 AND delta_consumed <= delta_allocated)
);

CREATE TABLE IF NOT EXISTS privacy_query_audit (
    id BIGSERIAL PRIMARY KEY,
    query_id VARCHAR(64) NOT NULL UNIQUE,
    budget_id VARCHAR(64) NOT NULL REFERENCES privacy_budget_ledger(budget_id),
    partner_code VARCHAR(64) NOT NULL,
    query_type VARCHAR(64) NOT NULL,
    epsilon_cost NUMERIC(12,6) NOT NULL,
    delta_cost NUMERIC(18,12) NOT NULL DEFAULT 0,
    input_count BIGINT,
    raw_result_count BIGINT,
    released_result NUMERIC(24,8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
