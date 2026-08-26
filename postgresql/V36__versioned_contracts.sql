CREATE TABLE IF NOT EXISTS contract_definition (
    id BIGSERIAL PRIMARY KEY,
    contract_code VARCHAR(64) NOT NULL,
    contract_version VARCHAR(32) NOT NULL,
    contract_name VARCHAR(128) NOT NULL,
    description TEXT,
    schema_json JSONB NOT NULL,
    compatibility_policy VARCHAR(24) NOT NULL DEFAULT 'BACKWARD',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (contract_code, contract_version),
    CONSTRAINT chk_contract_definition_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED'))
);

CREATE TABLE IF NOT EXISTS contract_instance (
    id BIGSERIAL PRIMARY KEY,
    instance_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    contract_code VARCHAR(64) NOT NULL,
    contract_version VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    business_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    validation_status VARCHAR(24) NOT NULL DEFAULT 'VALID',
    lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    source_type VARCHAR(32),
    source_ref VARCHAR(160),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (instance_id, revision),
    FOREIGN KEY (contract_code, contract_version)
        REFERENCES contract_definition(contract_code, contract_version),
    CONSTRAINT chk_contract_instance_validation CHECK (validation_status IN ('VALID','INVALID')),
    CONSTRAINT chk_contract_instance_lifecycle CHECK (lifecycle_status IN ('ACTIVE','DELETED'))
);

CREATE INDEX IF NOT EXISTS idx_contract_instance_type_key
    ON contract_instance(contract_code, bank_code, business_key, revision DESC);
CREATE INDEX IF NOT EXISTS idx_contract_instance_payload
    ON contract_instance USING GIN(payload);

CREATE TABLE IF NOT EXISTS contract_validation_log (
    id BIGSERIAL PRIMARY KEY,
    validation_id VARCHAR(64) NOT NULL UNIQUE,
    contract_code VARCHAR(64) NOT NULL,
    contract_version VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32),
    source_type VARCHAR(32),
    source_ref VARCHAR(160),
    content_sha256 CHAR(64),
    valid BOOLEAN NOT NULL,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE cf_risk_case
    ADD COLUMN IF NOT EXISTS case_graph_contract_instance_id VARCHAR(96),
    ADD COLUMN IF NOT EXISTS case_graph_contract_sha256 CHAR(64);

ALTER TABLE cf_risk_event
    ADD COLUMN IF NOT EXISTS event_kind VARCHAR(24) DEFAULT 'ATOMIC',
    ADD COLUMN IF NOT EXISTS canonical_contract_instance_id VARCHAR(96);

ALTER TABLE risk_signal
    ADD COLUMN IF NOT EXISTS inference_contract_instance_id VARCHAR(96);

ALTER TABLE inference_evidence
    ADD COLUMN IF NOT EXISTS evidence_type VARCHAR(24) DEFAULT 'MODEL',
    ADD COLUMN IF NOT EXISTS target_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS target_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS decision_threshold NUMERIC(10,6),
    ADD COLUMN IF NOT EXISTS window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS window_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS execution_log_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contract_instance_id VARCHAR(96);

ALTER TABLE exchange_message
    ADD COLUMN IF NOT EXISTS sender_institution VARCHAR(64),
    ADD COLUMN IF NOT EXISTS receiver_institution VARCHAR(64),
    ADD COLUMN IF NOT EXISTS content_version VARCHAR(32) DEFAULT '1.0',
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS privacy_processing JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS envelope_contract_version VARCHAR(32) DEFAULT '1.0',
    ADD COLUMN IF NOT EXISTS contract_instance_id VARCHAR(96);

CREATE UNIQUE INDEX IF NOT EXISTS uk_exchange_sender_idempotency
    ON exchange_message(sender_institution, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
