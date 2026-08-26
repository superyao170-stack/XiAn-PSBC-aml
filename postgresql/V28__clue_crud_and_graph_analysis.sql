ALTER TABLE risk_clue ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE risk_clue ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_risk_clue_bank_status_active
    ON risk_clue(bank_code,status,created_at DESC) WHERE deleted=false;
