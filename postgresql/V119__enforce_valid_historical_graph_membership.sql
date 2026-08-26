-- Every historical case shown in the panorama must have an urgency-derived risk
-- level. Remove legacy exceptions and keep invalid history out of the matching DB.
WITH invalid_historical AS (
    SELECT p.case_id
      FROM case_processing_pool p
      LEFT JOIN structured_case_library l ON l.case_id=p.case_id
     WHERE p.recognition_mode='HISTORICAL'
       AND btrim(COALESCE(
             p.framework_result->'basic_info'->>'urgency',
             l.case_document->'basic_info'->>'urgency', ''))
           !~ '^0[123]([^0-9].*)?$'
)
UPDATE case_processing_pool p
   SET processing_stage='FAILED',
       recommended_risk_level=NULL,
       risk_score=NULL,
       risk_breakdown=NULL,
       similarity_result=NULL,
       last_error='历史案例缺少合法的 final_case.basic_info.urgency（仅允许 01、02、03），无法自动定级入图，请修正数据后重试上传',
       approved_at=NULL,
       stage_completed_at=CURRENT_TIMESTAMP,
       updated_at=CURRENT_TIMESTAMP
  FROM invalid_historical invalid
 WHERE invalid.case_id=p.case_id;

UPDATE cf_risk_case c
   SET case_status='FAILED',
       risk_level=NULL,
       risk_score=NULL,
       approver=NULL,
       updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=c.case_id
   AND p.recognition_mode='HISTORICAL'
   AND p.processing_stage='FAILED';

UPDATE structured_case_library l
   SET status='INACTIVE', updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=l.case_id
   AND p.recognition_mode='HISTORICAL'
   AND p.processing_stage='FAILED';
