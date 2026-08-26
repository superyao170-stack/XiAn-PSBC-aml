UPDATE cf_risk_case
SET case_name = regexp_replace(case_name, '^[[:space:]—–-]+', ''),
    updated_at = CURRENT_TIMESTAMP
WHERE case_source = 'STRUCTURED_CASE'
  AND deleted = false;
