UPDATE sys_menu SET sort_order=99 WHERE path='/system';

UPDATE struct_schema SET status='DRAFT' WHERE status IS NULL;

ALTER TABLE struct_schema
    DROP CONSTRAINT IF EXISTS ck_struct_schema_status;
ALTER TABLE struct_schema
    ADD CONSTRAINT ck_struct_schema_status CHECK (status IN ('DRAFT','ACTIVE','INACTIVE'));
