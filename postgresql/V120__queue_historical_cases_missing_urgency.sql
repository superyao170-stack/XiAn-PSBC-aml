-- A genuinely missing urgency is a manual-decision case, not an upload failure.
WITH missing_urgency AS (
    SELECT p.case_id
      FROM case_processing_pool p
      LEFT JOIN structured_case_library l ON l.case_id=p.case_id
     WHERE p.recognition_mode='HISTORICAL'
       AND btrim(COALESCE(
             p.framework_result->'basic_info'->>'urgency',
             l.case_document->'basic_info'->>'urgency', ''))=''
)
UPDATE case_processing_pool p
   SET processing_stage='PENDING_APPROVAL',
       recommended_risk_level=NULL,
       risk_score=NULL,
       risk_breakdown=NULL,
       similarity_result=NULL,
       last_error=NULL,
       approved_at=NULL,
       stage_completed_at=CURRENT_TIMESTAMP,
       updated_at=CURRENT_TIMESTAMP
  FROM missing_urgency missing
 WHERE missing.case_id=p.case_id;

UPDATE cf_risk_case c
   SET case_status='PENDING_APPROVAL',
       risk_level=NULL,
       risk_score=NULL,
       approver=NULL,
       updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=c.case_id
   AND p.recognition_mode='HISTORICAL'
   AND p.processing_stage='PENDING_APPROVAL'
   AND p.recommended_risk_level IS NULL;

UPDATE structured_case_library l
   SET status='ACTIVE', updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=l.case_id
   AND p.recognition_mode='HISTORICAL'
   AND p.processing_stage='PENDING_APPROVAL'
   AND p.recommended_risk_level IS NULL;
