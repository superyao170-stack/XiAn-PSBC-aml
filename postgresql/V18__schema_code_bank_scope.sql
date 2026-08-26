ALTER TABLE struct_schema
    DROP CONSTRAINT IF EXISTS struct_schema_schema_code_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_struct_schema_code_bank
    ON struct_schema (schema_code, COALESCE(bank_code, ''));
