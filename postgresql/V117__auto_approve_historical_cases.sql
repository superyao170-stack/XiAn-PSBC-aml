-- Historical cases build the initial approved knowledge base. They finish after
-- framework extraction and never enter similarity matching or manual approval.
WITH historical_risk AS (
    SELECT p.case_id,
           CASE
             WHEN btrim(COALESCE(
                    p.framework_result->'basic_info'->>'urgency',
                    l.case_document->'basic_info'->>'urgency', '')) ~ '^01([^0-9].*)?$' THEN 'HIGH'
             WHEN btrim(COALESCE(
                    p.framework_result->'basic_info'->>'urgency',
                    l.case_document->'basic_info'->>'urgency', '')) ~ '^02([^0-9].*)?$' THEN 'MEDIUM'
             WHEN btrim(COALESCE(
                    p.framework_result->'basic_info'->>'urgency',
                    l.case_document->'basic_info'->>'urgency', '')) ~ '^03([^0-9].*)?$' THEN 'LOW'
             ELSE NULL
           END AS risk_level
      FROM case_processing_pool p
      LEFT JOIN structured_case_library l ON l.case_id=p.case_id
     WHERE p.recognition_mode='HISTORICAL'
)
UPDATE case_processing_pool p
   SET processing_stage='APPROVED',
       recommended_risk_level=r.risk_level,
       risk_score=NULL,
       risk_breakdown=NULL,
       similarity_result=NULL,
       last_error=NULL,
       stage_completed_at=CURRENT_TIMESTAMP,
       approved_at=COALESCE(p.approved_at, CURRENT_TIMESTAMP),
       updated_at=CURRENT_TIMESTAMP
  FROM historical_risk r
 WHERE r.case_id=p.case_id
   AND r.risk_level IS NOT NULL;

WITH historical_risk AS (
    SELECT p.case_id, p.recommended_risk_level AS risk_level
      FROM case_processing_pool p
     WHERE p.recognition_mode='HISTORICAL'
       AND p.processing_stage='APPROVED'
       AND p.recommended_risk_level IS NOT NULL
)
UPDATE cf_risk_case c
   SET case_status='APPROVED',
       risk_level=r.risk_level,
       risk_score=NULL,
       approver=COALESCE(c.approver, 'analysis-worker'),
       updated_at=CURRENT_TIMESTAMP
  FROM historical_risk r
 WHERE r.case_id=c.case_id;

UPDATE structured_case_library l
   SET status='ACTIVE', updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=l.case_id
   AND p.recognition_mode='HISTORICAL'
   AND p.processing_stage='APPROVED';

DELETE FROM case_similarity_ranking ranking
 USING case_processing_pool historical
 WHERE historical.case_id=ranking.query_case_id
   AND historical.recognition_mode='HISTORICAL';

COMMENT ON COLUMN case_processing_pool.approved_at IS
  'Manual approval time for new cases; automatic framework-completion time for historical cases';
