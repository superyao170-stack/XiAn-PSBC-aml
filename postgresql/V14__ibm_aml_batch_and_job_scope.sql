ALTER TABLE analysis_job ADD COLUMN IF NOT EXISTS batch_id BIGINT REFERENCES risk_ingest_batch(id);
CREATE INDEX IF NOT EXISTS idx_analysis_job_batch ON analysis_job(batch_id);

CREATE TABLE IF NOT EXISTS structured_ingest_asset (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES risk_ingest_batch(id) ON DELETE CASCADE,
    asset_type VARCHAR(24) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    row_count BIGINT DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(batch_id, asset_type)
);

CREATE TABLE IF NOT EXISTS structured_pattern_label (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES risk_ingest_batch(id) ON DELETE CASCADE,
    pattern_code VARCHAR(128) NOT NULL,
    pattern_name VARCHAR(255) NOT NULL,
    start_line BIGINT,
    end_line BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_structured_pattern_batch ON structured_pattern_label(batch_id);
