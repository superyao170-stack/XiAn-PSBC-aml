UPDATE struct_schema
SET latest_version = COALESCE(latest_version, '1.0.0'),
    version_count = CASE WHEN COALESCE(version_count, 0) < 1 THEN 1 ELSE version_count END,
    updated_at = CURRENT_TIMESTAMP
WHERE deleted = false;

INSERT INTO struct_schema_version
    (schema_id, version, schema_definition, field_definitions, semantic_mapping,
     graph_mapping, changelog, status, created_by)
SELECT s.id, s.latest_version, '{}'::jsonb, '[]'::jsonb, '{}'::jsonb, '{}'::jsonb,
       '历史Schema版本补全', s.status, 'migration'
FROM struct_schema s
WHERE s.deleted = false
  AND NOT EXISTS (
      SELECT 1 FROM struct_schema_version v
      WHERE v.schema_id = s.id AND v.version = s.latest_version
  );
