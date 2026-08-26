ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS manual_scenario_code VARCHAR(64);

UPDATE risk_signal s
SET manual_scenario_code=s.scenario_code
WHERE EXISTS (
    SELECT 1 FROM risk_signal_review_record r
    WHERE r.signal_id=s.signal_id AND r.action='EDIT'
);
