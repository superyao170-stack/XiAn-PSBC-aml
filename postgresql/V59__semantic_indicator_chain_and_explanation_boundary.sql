-- V1.9: semantic observations match managed indicators; indicator results are
-- explanation-chain records and no longer graph-canvas nodes.

UPDATE knowledge_asset_relation
SET status='RETIRED',updated_at=CURRENT_TIMESTAMP
WHERE status='ACTIVE'
  AND (source_code LIKE 'TXT\_%' ESCAPE '\'
       OR target_code LIKE 'TXT\_%' ESCAPE '\');

UPDATE indicator_version
SET status='RETIRED'
WHERE indicator_code LIKE 'TXT\_%' ESCAPE '\'
  AND status='ACTIVE';

UPDATE indicator_definition
SET status='RETIRED',updated_at=CURRENT_TIMESTAMP
WHERE indicator_code LIKE 'TXT\_%' ESCAPE '\'
  AND status='ACTIVE';

UPDATE graph_node_type_registry
SET status='RETIRED',
    visibility_policy='{"default":false,"surface":"EXPLANATION_CHAIN"}'::jsonb,
    updated_at=CURRENT_TIMESTAMP
WHERE node_type='INDICATOR_RESULT' AND status='ACTIVE';

UPDATE graph_relation_type_registry
SET status='RETIRED',updated_at=CURRENT_TIMESTAMP
WHERE relation_type IN ('INPUT_TO_INDICATOR_RESULT','SUPPORTS_BEHAVIOR_PATTERN')
  AND status='ACTIVE';

