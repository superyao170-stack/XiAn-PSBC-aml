CREATE TABLE IF NOT EXISTS risk_knowledge_entry (
    id BIGSERIAL PRIMARY KEY,
    knowledge_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    scope VARCHAR(24) NOT NULL DEFAULT 'LOCAL_BANK'
        CHECK (scope = 'LOCAL_BANK'),
    knowledge_type VARCHAR(64) NOT NULL,
    knowledge_code VARCHAR(128) NOT NULL,
    version VARCHAR(32) NOT NULL DEFAULT '1.0',
    title VARCHAR(256) NOT NULL,
    payload JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    source_artifact_id VARCHAR(64) NOT NULL
        REFERENCES analytics_artifact(artifact_id),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMPTZ,
    UNIQUE (bank_code, knowledge_code, version)
);

CREATE INDEX IF NOT EXISTS idx_risk_knowledge_entry_scope
    ON risk_knowledge_entry(bank_code, scenario_code, knowledge_type, status, created_at DESC);
