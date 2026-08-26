-- Personal profile is only the default bank. Business objects retain their own bank_code,
-- and downstream jobs inherit it from the selected source batch.
INSERT INTO bank_profile
    (bank_code, bank_name, bank_full_name, bank_short_name, bank_type, status)
VALUES
    ('中国测试银行', '中国测试银行', '中国测试银行', '测试银行', 'COMMERCIAL', 'ACTIVE')
ON CONFLICT (bank_code) DO UPDATE SET
    bank_name = EXCLUDED.bank_name,
    bank_full_name = EXCLUDED.bank_full_name,
    bank_short_name = EXCLUDED.bank_short_name,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- The historical BANK001 and postal-bank tenants can contain the same logical Schema.
-- Merge references by schema_code before changing the bank key, preserving the postal-bank
-- record (the one referenced by the current production batches) as the canonical row.
CREATE TEMP TABLE schema_bank_merge ON COMMIT DROP AS
SELECT old_schema.id AS old_id, canonical.id AS canonical_id
FROM struct_schema old_schema
JOIN struct_schema canonical
  ON canonical.schema_code = old_schema.schema_code
 AND canonical.bank_code IN ('中国邮储银行', '中国邮政储蓄银行')
WHERE old_schema.bank_code = 'BANK001';

DELETE FROM struct_schema_version old_version
USING schema_bank_merge merge
WHERE old_version.schema_id = merge.old_id
  AND EXISTS (
      SELECT 1 FROM struct_schema_version canonical_version
      WHERE canonical_version.schema_id = merge.canonical_id
        AND canonical_version.version = old_version.version
  );

DO $$
DECLARE
    reference RECORD;
BEGIN
    FOR reference IN
        SELECT child.relname AS table_name, attribute.attname AS column_name
        FROM pg_constraint constraint_row
        JOIN pg_class child ON child.oid = constraint_row.conrelid
        JOIN pg_attribute attribute
          ON attribute.attrelid = constraint_row.conrelid
         AND attribute.attnum = constraint_row.conkey[1]
        WHERE constraint_row.contype = 'f'
          AND constraint_row.confrelid = 'struct_schema'::regclass
          AND array_length(constraint_row.conkey, 1) = 1
    LOOP
        EXECUTE format(
            'UPDATE %I target SET %I = merge.canonical_id FROM schema_bank_merge merge WHERE target.%I = merge.old_id',
            reference.table_name, reference.column_name, reference.column_name
        );
    END LOOP;
END $$;

DELETE FROM struct_schema old_schema
USING schema_bank_merge merge
WHERE old_schema.id = merge.old_id;

DO $$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name <> 'bank_profile'
          AND column_name IN ('bank_code', 'source_bank_code', 'target_bank_code')
          AND data_type IN ('character varying', 'character', 'text')
    LOOP
        EXECUTE format(
            'UPDATE %I SET %I = $1 WHERE %I IN ($2, $3, $4)',
            target.table_name, target.column_name, target.column_name
        ) USING '中国测试银行', '中国邮储银行', '中国邮政储蓄银行', 'BANK001';
    END LOOP;
END $$;

DELETE FROM bank_profile
WHERE bank_code IN ('中国邮储银行', '中国邮政储蓄银行', 'BANK001');
