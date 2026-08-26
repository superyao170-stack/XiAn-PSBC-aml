-- Historical case risk comes from final_case.basic_info.risk_level.
-- Supported source vocabulary follows the worker contract:
-- 01/general/low -> LOW, 02/key/medium -> MEDIUM, 03/high -> HIGH.
WITH historical_risk AS (
    SELECT p.case_id,
           CASE
             WHEN upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    ~ '^03([^0-9].*)?$'
               OR upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    IN ('高','高风险','HIGH') THEN 'HIGH'
             WHEN upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    ~ '^02([^0-9].*)?$'
               OR upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    IN ('中','中风险','中等风险','重点可疑','MEDIUM') THEN 'MEDIUM'
             WHEN upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    ~ '^01([^0-9].*)?$'
               OR upper(regexp_replace(btrim(COALESCE(
                    p.framework_result->'basic_info'->>'risk_level',
                    l.case_document->'basic_info'->>'risk_level', '')), '\s+', '', 'g'))
                    IN ('低','低风险','一般可疑','LOW') THEN 'LOW'
             ELSE NULL
           END AS risk_level
      FROM case_processing_pool p
      LEFT JOIN structured_case_library l ON l.case_id=p.case_id
     WHERE p.recognition_mode='HISTORICAL'
), refreshed_pool AS (
    UPDATE case_processing_pool p
       SET processing_stage=CASE WHEN r.risk_level IS NULL THEN 'PENDING_APPROVAL' ELSE 'APPROVED' END,
           recommended_risk_level=r.risk_level,
           risk_score=NULL,
           risk_breakdown=NULL,
           similarity_result=NULL,
           last_error=NULL,
           approved_at=CASE WHEN r.risk_level IS NULL THEN NULL ELSE COALESCE(p.approved_at,CURRENT_TIMESTAMP) END,
           stage_completed_at=CURRENT_TIMESTAMP,
           updated_at=CURRENT_TIMESTAMP
      FROM historical_risk r
     WHERE r.case_id=p.case_id
    RETURNING p.case_id,p.processing_stage,p.recommended_risk_level
)
UPDATE cf_risk_case c
   SET case_status=r.processing_stage,
       risk_level=r.recommended_risk_level,
       risk_score=NULL,
       approver=CASE WHEN r.recommended_risk_level IS NULL THEN NULL ELSE COALESCE(c.approver,'analysis-worker') END,
       updated_at=CURRENT_TIMESTAMP
  FROM refreshed_pool r
 WHERE r.case_id=c.case_id;

UPDATE structured_case_library l
   SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=l.case_id
   AND p.recognition_mode='HISTORICAL';

COMMENT ON COLUMN case_processing_pool.recommended_risk_level IS
  'Default approval level mapped from final_case.basic_info.risk_level; missing or unknown values require manual approval';
