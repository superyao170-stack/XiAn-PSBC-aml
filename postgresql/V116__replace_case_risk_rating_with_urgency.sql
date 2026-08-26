-- Risk defaults are no longer inferred from events, relationships, keywords or similarity.
-- final_case.basic_info.urgency is the only source: 01=HIGH, 02=MEDIUM, 03=LOW.
UPDATE case_processing_pool
   SET risk_score=NULL,
       risk_breakdown=NULL,
       recommended_risk_level=CASE
         WHEN btrim(COALESCE(framework_result->'basic_info'->>'urgency','')) ~ '^01([^0-9].*)?$' THEN 'HIGH'
         WHEN btrim(COALESCE(framework_result->'basic_info'->>'urgency','')) ~ '^02([^0-9].*)?$' THEN 'MEDIUM'
         WHEN btrim(COALESCE(framework_result->'basic_info'->>'urgency','')) ~ '^03([^0-9].*)?$' THEN 'LOW'
         ELSE NULL
       END,
       updated_at=CURRENT_TIMESTAMP;

UPDATE cf_risk_case c
   SET risk_score=NULL,
       updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=c.case_id;

COMMENT ON COLUMN case_processing_pool.recommended_risk_level IS
  'Default approval level mapped only from final_case.basic_info.urgency: 01 HIGH, 02 MEDIUM, 03 LOW';
COMMENT ON COLUMN case_processing_pool.risk_score IS
  'Retained for compatibility; case risk scoring is disabled and this value remains NULL';
COMMENT ON COLUMN case_processing_pool.risk_breakdown IS
  'Retained for compatibility; the removed risk-rating module no longer writes a breakdown';
