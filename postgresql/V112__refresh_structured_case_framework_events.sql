-- Repair cases that were re-recognized while the old INSERT-only persistence
-- path was active. The latest structured_case_library document is authoritative.
UPDATE cf_risk_event event
SET deleted = true,
    updated_at = CURRENT_TIMESTAMP
WHERE event.rule_name = 'XI_AN_CASE_FRAMEWORK_EXTRACTION'
  AND event.deleted = false
  AND EXISTS (
      SELECT 1 FROM structured_case_library library
      WHERE library.case_id = event.case_id AND library.status = 'ACTIVE'
  );

INSERT INTO cf_risk_event
    (event_id,case_id,case_version,bank_code,event_name,event_type,event_standard_code,
     confidence,evidence_refs,risk_score,risk_level,rule_name,subject_count,deleted)
SELECT
    'EVT-XI-' || upper(md5(library.case_id || ':' ||
        COALESCE(item.event->>'event_id', item.ordinality::text))),
    library.case_id,
    1,
    library.bank_code,
    left(COALESCE(NULLIF(item.event->>'event_name',''), item.event->>'event_id', '未命名事件'), 200),
    left(COALESCE(item.event->>'event_type',''), 50),
    left(COALESCE(item.event->>'event_type_id', item.event->>'event_standard_code',''), 64),
    COALESCE(risk_case.risk_score, 0),
    item.event,
    COALESCE(risk_case.risk_score, 0),
    COALESCE(risk_case.risk_level, 'LOW'),
    'XI_AN_CASE_FRAMEWORK_EXTRACTION',
    1,
    false
FROM structured_case_library library
JOIN cf_risk_case risk_case ON risk_case.case_id = library.case_id
CROSS JOIN LATERAL jsonb_array_elements(
    COALESCE(library.case_document->'events', '[]'::jsonb)
) WITH ORDINALITY AS item(event, ordinality)
WHERE library.status = 'ACTIVE'
ON CONFLICT (event_id) DO UPDATE SET
    bank_code = EXCLUDED.bank_code,
    event_name = EXCLUDED.event_name,
    event_type = EXCLUDED.event_type,
    event_standard_code = EXCLUDED.event_standard_code,
    confidence = EXCLUDED.confidence,
    evidence_refs = EXCLUDED.evidence_refs,
    risk_score = EXCLUDED.risk_score,
    risk_level = EXCLUDED.risk_level,
    rule_name = EXCLUDED.rule_name,
    subject_count = EXCLUDED.subject_count,
    deleted = false,
    updated_at = CURRENT_TIMESTAMP;
