CREATE TABLE IF NOT EXISTS analytics_dataset_version (
    id BIGSERIAL PRIMARY KEY,
    dataset_version_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    dataset_code VARCHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    source_snapshot_id VARCHAR(64) REFERENCES analytics_dataset_snapshot(snapshot_id),
    schema_version VARCHAR(32),
    manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
    split_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_sha256 CHAR(64) NOT NULL,
    record_count BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bank_code, dataset_code, version)
);

CREATE INDEX IF NOT EXISTS idx_analytics_dataset_version_scope
    ON analytics_dataset_version(bank_code, scenario_code, dataset_code, created_at DESC);

CREATE TABLE IF NOT EXISTS analytics_dataset_member (
    dataset_version_id VARCHAR(64) NOT NULL
        REFERENCES analytics_dataset_version(dataset_version_id) ON DELETE CASCADE,
    record_key VARCHAR(256) NOT NULL,
    split_name VARCHAR(16) NOT NULL
        CHECK (split_name IN ('TRAIN', 'VALIDATION', 'TEST')),
    record_sha256 CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ,
    label VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (dataset_version_id, record_key)
);

CREATE INDEX IF NOT EXISTS idx_analytics_dataset_member_split
    ON analytics_dataset_member(dataset_version_id, split_name);

CREATE TABLE IF NOT EXISTS analytics_artifact (
    id BIGSERIAL PRIMARY KEY,
    artifact_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL REFERENCES analytics_run(run_id) ON DELETE CASCADE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    artifact_type VARCHAR(40) NOT NULL,
    artifact_key VARCHAR(128) NOT NULL,
    schema_version VARCHAR(32) NOT NULL DEFAULT '1.0',
    payload JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, artifact_type, artifact_key)
);

CREATE INDEX IF NOT EXISTS idx_analytics_artifact_scope
    ON analytics_artifact(bank_code, scenario_code, artifact_type, created_at DESC);
