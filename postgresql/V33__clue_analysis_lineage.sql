CREATE TABLE IF NOT EXISTS risk_clue_analysis_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(64) NOT NULL,
    scenario_code VARCHAR(64),
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    requested_case_ids TEXT[],
    input_case_count INTEGER NOT NULL DEFAULT 0,
    scanned_account_nodes INTEGER NOT NULL DEFAULT 0,
    scanned_event_nodes INTEGER NOT NULL DEFAULT 0,
    generated_clues INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

ALTER TABLE risk_clue ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'GRAPH_ANALYSIS';
ALTER TABLE risk_clue ADD COLUMN IF NOT EXISTS analysis_run_id VARCHAR(64);

UPDATE risk_clue SET source_type='MANUAL' WHERE algorithm_code='MANUAL';

CREATE INDEX IF NOT EXISTS idx_clue_analysis_run_bank_time
    ON risk_clue_analysis_run(bank_code, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_clue_analysis_run
    ON risk_clue(analysis_run_id) WHERE analysis_run_id IS NOT NULL;
