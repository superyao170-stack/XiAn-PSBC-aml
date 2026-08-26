ALTER TABLE risk_ingest_batch ADD COLUMN IF NOT EXISTS schema_id BIGINT REFERENCES struct_schema(id);

UPDATE risk_ingest_batch b
SET schema_id = s.id
FROM struct_schema s
WHERE b.schema_id IS NULL
  AND s.bank_code = b.bank_code
  AND s.schema_code = 'IBM_AML_V1'
  AND s.deleted = false;

CREATE TABLE IF NOT EXISTS schema_recognition_feedback (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL UNIQUE REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    batch_id BIGINT,
    schema_id BIGINT NOT NULL REFERENCES struct_schema(id),
    bank_code VARCHAR(32) NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    discovered_definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_schema_feedback_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_schema_feedback_bank_status
    ON schema_recognition_feedback(bank_code, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_risk_transaction_batch_source
    ON risk_transaction_materialized(batch_id, source_record_id);

CREATE INDEX IF NOT EXISTS idx_risk_signal_case_candidate
    ON risk_signal(bank_code, workspace_id, scenario_code, score DESC)
    WHERE signal_type='TRANSACTION' AND status='CANDIDATE';
