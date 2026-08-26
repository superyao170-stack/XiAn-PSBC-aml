-- Historical cleanup once removed version rows while retaining published parent schemas.
-- Restore a readable version for every surviving schema. Unstructured schemas have a
-- stable raw-text contract; unknown structured contracts return to DRAFT for review.
INSERT INTO struct_schema_version
    (schema_id, version, schema_definition, field_definitions, semantic_mapping,
     graph_mapping, changelog, status, created_by)
SELECT s.id,
       COALESCE(NULLIF(s.latest_version, ''), '1.0.0'),
       '{}'::jsonb,
       CASE WHEN s.category = 'UNSTRUCTURED'
            THEN '[{"name":"raw_text","label":"原始文本","dataType":"STRING","required":true,"sourceColumn":"raw_text","description":"非结构化识别输入正文"}]'::jsonb
            ELSE '[]'::jsonb END,
       '{}'::jsonb,
       '{}'::jsonb,
       '修复历史缺失的Schema版本',
       CASE WHEN s.category = 'UNSTRUCTURED' THEN s.status ELSE 'DRAFT' END,
       'migration'
FROM struct_schema s
WHERE s.deleted = false
  AND NOT EXISTS (SELECT 1 FROM struct_schema_version v WHERE v.schema_id = s.id);

UPDATE struct_schema s
SET latest_version = COALESCE(NULLIF(s.latest_version, ''), '1.0.0'),
    version_count = GREATEST(COALESCE(s.version_count, 0), 1),
    status = CASE
        WHEN s.category <> 'UNSTRUCTURED'
             AND NOT EXISTS (
                 SELECT 1 FROM struct_schema_version v
                 WHERE v.schema_id = s.id AND v.field_definitions <> '[]'::jsonb
             )
        THEN 'DRAFT'
        ELSE s.status
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE s.deleted = false;
