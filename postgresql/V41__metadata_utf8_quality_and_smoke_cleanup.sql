-- Remove deployment smoke-test metadata that must never be part of the
-- deterministic production dictionary. Exact prefixes and creators keep the
-- cleanup scoped to assets produced by smoke-new-capabilities.ps1.
DELETE FROM indicator_calculation_result
WHERE indicator_code LIKE 'SMOKE\_RISK\_%' ESCAPE '\';

DELETE FROM scenario_indicator_binding
WHERE indicator_code LIKE 'SMOKE\_RISK\_%' ESCAPE '\';

DELETE FROM indicator_version
WHERE indicator_code LIKE 'SMOKE\_RISK\_%' ESCAPE '\';

DELETE FROM indicator_definition
WHERE indicator_code LIKE 'SMOKE\_RISK\_%' ESCAPE '\'
  AND created_by = 'sadmin';

DELETE FROM semantic_frame_definition f
WHERE f.frame_code LIKE 'SMOKE\_FRAME\_%' ESCAPE '\'
  AND f.created_by = 'sadmin'
  AND NOT EXISTS (
      SELECT 1
      FROM cf_risk_event e
      WHERE e.event_frame_code = f.frame_code
        AND e.event_frame_version = f.frame_version
  );

DELETE FROM ontology_term
WHERE term_code LIKE 'SMOKE\_%' ESCAPE '\'
  AND created_by = 'sadmin'
  AND constraints ->> 'source' = 'server-smoke';

-- A repeated literal question mark or U+FFFD in a display name is evidence of
-- lossy request decoding, not an acceptable uncertainty marker.
ALTER TABLE ontology_term
    ADD CONSTRAINT chk_ontology_term_name_encoding
    CHECK (term_name !~ '[?]{2,}' AND position(chr(65533) in term_name) = 0);

ALTER TABLE semantic_frame_definition
    ADD CONSTRAINT chk_semantic_frame_name_encoding
    CHECK (frame_name !~ '[?]{2,}' AND position(chr(65533) in frame_name) = 0);

ALTER TABLE semantic_slot_definition
    ADD CONSTRAINT chk_semantic_slot_name_encoding
    CHECK (slot_name !~ '[?]{2,}' AND position(chr(65533) in slot_name) = 0);

ALTER TABLE indicator_definition
    ADD CONSTRAINT chk_indicator_name_encoding
    CHECK (indicator_name !~ '[?]{2,}' AND position(chr(65533) in indicator_name) = 0);
