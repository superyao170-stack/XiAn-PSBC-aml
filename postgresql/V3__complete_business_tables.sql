-- Complete the seven business tables declared in the design but missing from the runtime Flyway chain.

CREATE TABLE IF NOT EXISTS quarantine_record (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    batch_id BIGINT REFERENCES risk_ingest_batch(id),
    source_record_id VARCHAR(128),
    error_type VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    original_payload JSONB,
    quality_report JSONB,
    status VARCHAR(24) NOT NULL DEFAULT 'QUARANTINED',
    fixed_by VARCHAR(64),
    fixed_at TIMESTAMPTZ,
    fixed_method VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_quarantine_status CHECK (status IN ('QUARANTINED', 'FIXING', 'FIXED', 'REPLAYED', 'IGNORED'))
);

CREATE TABLE IF NOT EXISTS event_outbox (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    business_version BIGINT NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbox_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT uk_outbox_business_event UNIQUE (aggregate_type, aggregate_id, event_type, business_version)
);

CREATE TABLE IF NOT EXISTS bank_profile (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL UNIQUE,
    bank_name VARCHAR(128) NOT NULL,
    bank_full_name VARCHAR(255),
    bank_short_name VARCHAR(64),
    bank_type VARCHAR(32),
    contact_name VARCHAR(64),
    contact_phone VARCHAR(32),
    contact_email VARCHAR(128),
    address VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bank_profile_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED'))
);

CREATE TABLE IF NOT EXISTS bank_certificate (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL REFERENCES bank_profile(bank_code),
    cert_type VARCHAR(32) NOT NULL,
    cert_name VARCHAR(128),
    cert_path VARCHAR(255),
    cert_hash CHAR(64),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bank_certificate_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE TABLE IF NOT EXISTS analysis_job (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    workspace_id BIGINT NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    job_name VARCHAR(128) NOT NULL,
    scenario_code VARCHAR(64),
    scenario_version VARCHAR(32),
    input_batch_id BIGINT REFERENCES risk_ingest_batch(id),
    input_params JSONB,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    total_steps INT NOT NULL DEFAULT 1,
    current_step VARCHAR(64),
    result_summary JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    CONSTRAINT ck_analysis_job_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_analysis_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS analysis_job_step (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    step_order INT NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    step_type VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    result_json JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_analysis_job_step UNIQUE (job_id, step_order),
    CONSTRAINT ck_analysis_step_progress CHECK (progress BETWEEN 0 AND 100)
);

CREATE TABLE IF NOT EXISTS sys_notification (
    id BIGSERIAL PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32),
    user_id BIGINT REFERENCES sys_user(id),
    notification_type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    message TEXT,
    link_url VARCHAR(255),
    is_read BOOLEAN NOT NULL DEFAULT false,
    read_at TIMESTAMPTZ,
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS strategy_replay_record (
    id BIGSERIAL PRIMARY KEY,
    replay_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    bank_code VARCHAR(32) NOT NULL,
    replay_time TIMESTAMPTZ NOT NULL,
    original_config_snapshot_id VARCHAR(64),
    current_config_snapshot_id VARCHAR(64),
    diff_result JSONB,
    assumption_analysis JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_quarantine_batch_status ON quarantine_record(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_outbox_publish ON event_outbox(status, available_at);
CREATE INDEX IF NOT EXISTS idx_analysis_job_status ON analysis_job(bank_code, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_user_read ON sys_notification(user_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_replay_case ON strategy_replay_record(case_id, replay_time DESC);
