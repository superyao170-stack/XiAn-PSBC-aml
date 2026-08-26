CREATE INDEX IF NOT EXISTS idx_risk_transaction_signal_lookup
    ON risk_transaction_materialized(bank_code, workspace_id, source_record_id);

CREATE INDEX IF NOT EXISTS idx_risk_signal_transaction_created
    ON risk_signal(created_at DESC)
    WHERE signal_type = 'TRANSACTION';

CREATE INDEX IF NOT EXISTS idx_risk_signal_bank_transaction_created
    ON risk_signal(bank_code, created_at DESC)
    WHERE signal_type = 'TRANSACTION';

CREATE INDEX IF NOT EXISTS idx_case_signal_rel_signal
    ON case_signal_rel(signal_id);
