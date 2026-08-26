-- Correct the V39 historical backfill using the case source as the authoritative
-- observation mode. An event extracted from a text case remains a text-derived
-- assertion even when its semantic event_type resembles a structured transfer.
UPDATE cf_risk_event e
SET event_frame_code='TEXT_ILLEGAL_BEHAVIOR',
    event_frame_version=1,
    definition_binding_status='BOUND',
    definition_match_method='MIGRATION_CASE_SOURCE',
    definition_match_confidence=0.8500,
    updated_at=CURRENT_TIMESTAMP
FROM cf_risk_case c
WHERE c.case_id=e.case_id
  AND c.deleted=false
  AND c.case_source='TEXT_CASE'
  AND (
      e.event_frame_code IS DISTINCT FROM 'TEXT_ILLEGAL_BEHAVIOR'
      OR e.event_frame_version IS DISTINCT FROM 1
      OR e.definition_binding_status IS DISTINCT FROM 'BOUND'
  );

-- Historical events recognized from the structured suspected-transaction path
-- use the structured transfer frame. Other/manual case sources remain untouched.
UPDATE cf_risk_event e
SET event_frame_code='STRUCTURED_TRANSFER',
    event_frame_version=1,
    definition_binding_status='BOUND',
    definition_match_method='MIGRATION_CASE_SOURCE',
    definition_match_confidence=1.0000,
    updated_at=CURRENT_TIMESTAMP
FROM cf_risk_case c
WHERE c.case_id=e.case_id
  AND c.deleted=false
  AND c.case_source='STRUCT_SUSPECTED'
  AND (
      e.event_frame_code IS DISTINCT FROM 'STRUCTURED_TRANSFER'
      OR e.event_frame_version IS DISTINCT FROM 1
      OR e.definition_binding_status IS DISTINCT FROM 'BOUND'
  );
