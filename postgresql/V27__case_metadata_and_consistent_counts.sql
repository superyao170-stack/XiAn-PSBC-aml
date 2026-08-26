ALTER TABLE cf_risk_case ADD COLUMN IF NOT EXISTS case_name VARCHAR(240);
ALTER TABLE cf_risk_case ADD COLUMN IF NOT EXISTS description TEXT;

UPDATE cf_risk_case
SET case_name = CASE
    WHEN case_source = 'TEXT_CASE' THEN '非结构化案例-' || RIGHT(case_id, 8)
    WHEN case_source = 'STRUCT_SUSPECTED' THEN '结构化案例-' || RIGHT(case_id, 8)
    ELSE '风险案例-' || RIGHT(case_id, 8)
END
WHERE case_name IS NULL OR BTRIM(case_name) = '';

CREATE INDEX IF NOT EXISTS idx_cf_risk_event_case_active
    ON cf_risk_event(case_id) WHERE deleted=false;
