-- PostgreSQL LIKE treats '_' as a wildcard. V79 used a bracket expression
-- that is not valid LIKE escaping, so normalize the remaining AML aliases.
UPDATE cf_risk_case
SET scenario_code='AML',updated_at=CURRENT_TIMESTAMP
WHERE deleted=false
  AND scenario_code<>'AML'
  AND scenario_code LIKE 'AML%';

UPDATE risk_signal
SET scenario_code='AML'
WHERE scenario_code<>'AML'
  AND scenario_code LIKE 'AML%';

UPDATE analysis_job
SET scenario_code='AML'
WHERE scenario_code<>'AML'
  AND scenario_code LIKE 'AML%';
