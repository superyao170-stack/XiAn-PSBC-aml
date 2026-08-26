ALTER TABLE cf_risk_case
    ADD COLUMN IF NOT EXISTS source_case_no VARCHAR(64);

COMMENT ON COLUMN cf_risk_case.source_case_no IS
    'Source/business case number; never used as the semantic case title';

UPDATE cf_risk_case
SET source_case_no = 'ML-2023-001',
    case_name = '孙某集中收款、分散转账及跨境汇款案',
    updated_at = CURRENT_TIMESTAMP
WHERE case_id = 'CASE-T-B2C82729E6DE4FFF8F34'
  AND case_name = 'ML-2023-001';

UPDATE case_core_chain_node
SET display_name = '孙某集中收款、分散转账及跨境汇款案',
    properties = jsonb_set(
        jsonb_set(COALESCE(properties, '{}'::jsonb),
                  '{name}', to_jsonb('孙某集中收款、分散转账及跨境汇款案'::text), true),
        '{caseName}', to_jsonb('孙某集中收款、分散转账及跨境汇款案'::text), true)
WHERE case_id = 'CASE-T-B2C82729E6DE4FFF8F34'
  AND canonical_type = 'CASE';

CREATE INDEX IF NOT EXISTS idx_cf_risk_case_source_case_no
    ON cf_risk_case(bank_code, source_case_no)
    WHERE source_case_no IS NOT NULL AND btrim(source_case_no) <> '';
