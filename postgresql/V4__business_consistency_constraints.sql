-- Cross-module data consistency constraints for the main business flows.

ALTER TABLE risk_ingest_batch
    ADD CONSTRAINT ck_risk_ingest_batch_counts
    CHECK (source_count >= 0 AND accepted_count >= 0 AND rejected_count >= 0 AND duplicate_count >= 0);

ALTER TABLE risk_ingest_batch
    ADD CONSTRAINT ck_risk_ingest_batch_total
    CHECK (accepted_count + rejected_count + duplicate_count <= source_count);

ALTER TABLE cf_risk_case
    ADD CONSTRAINT ck_cf_risk_case_status
    CHECK (case_status IN ('DRAFT', 'IN_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CLOSED', 'REOPENED'));

ALTER TABLE cf_risk_case
    ADD CONSTRAINT ck_cf_risk_case_risk_level
    CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

ALTER TABLE cf_risk_case
    ADD CONSTRAINT ck_cf_risk_case_counts
    CHECK (subject_count >= 0 AND transaction_count >= 0 AND (total_amount IS NULL OR total_amount >= 0));

ALTER TABLE pending_activation
    ADD CONSTRAINT ck_pending_activation_status
    CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'REJECTED'));

ALTER TABLE pending_activation
    ADD CONSTRAINT ck_pending_activation_terminal_time
    CHECK (
        (status = 'PENDING_ACTIVATION' AND activated_at IS NULL AND rejected_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL AND rejected_at IS NULL)
        OR (status = 'REJECTED' AND rejected_at IS NOT NULL AND activated_at IS NULL)
    );

ALTER TABLE shared_clue
    ADD CONSTRAINT ck_shared_clue_confidence
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1));

CREATE INDEX IF NOT EXISTS idx_case_review_case_version
    ON case_review_record(case_id, case_version, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_case_approval_case_version
    ON case_approval_record(case_id, case_version, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_case_signal_case_version
    ON case_signal_rel(case_id, case_version);
