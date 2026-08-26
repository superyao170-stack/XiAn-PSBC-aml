ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(64);
ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS review_opinion TEXT;
ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS risk_signal_review_record (
    id BIGSERIAL PRIMARY KEY,
    review_id VARCHAR(64) NOT NULL UNIQUE,
    signal_id VARCHAR(64) NOT NULL REFERENCES risk_signal(signal_id),
    bank_code VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_status VARCHAR(24),
    after_status VARCHAR(24) NOT NULL,
    decision VARCHAR(32),
    recommended_action VARCHAR(255),
    review_opinion TEXT,
    target_case_id VARCHAR(64),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_signal_review_signal_created
    ON risk_signal_review_record(signal_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_risk_signal_bank_manual_queue
    ON risk_signal(bank_code, status, score DESC, created_at DESC)
    WHERE signal_type='TRANSACTION';
