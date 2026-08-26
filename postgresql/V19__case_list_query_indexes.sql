CREATE INDEX IF NOT EXISTS idx_cf_risk_event_case_active
    ON cf_risk_event(case_id) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_case_signal_rel_case
    ON case_signal_rel(case_id);
