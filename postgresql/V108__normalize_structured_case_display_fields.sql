-- Structured uploads may carry source-document titles such as
-- “医疗领域可疑案例06——…”. The source number remains in source_case_no/case_id;
-- case_name is the concise business title shown to reviewers.
UPDATE cf_risk_case
SET case_name = regexp_replace(
        case_name,
        '^.*?可疑案例[0-9０-９]+[[:space:]]*[—–-]+[[:space:]]*',
        ''
    ),
    business_domain = CASE
        WHEN upper(scenario_code) = 'FRAUD' OR business_domain LIKE '%欺诈%' THEN '02-反欺诈'
        ELSE '01-反洗钱'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE case_source = 'STRUCTURED_CASE'
  AND deleted = false;
