CREATE TABLE IF NOT EXISTS unstructured_ingest_batch (
    id BIGSERIAL PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    schema_id BIGINT REFERENCES struct_schema(id),
    auto_schema BOOLEAN NOT NULL DEFAULT false,
    source_count BIGINT NOT NULL DEFAULT 0,
    accepted_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS unstructured_document (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES unstructured_ingest_batch(id) ON DELETE CASCADE,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128),
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_path TEXT NOT NULL,
    extraction_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    extracted_text TEXT,
    validation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS unstructured_schema_validation (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES unstructured_document(id) ON DELETE CASCADE,
    schema_id BIGINT REFERENCES struct_schema(id),
    status VARCHAR(24) NOT NULL,
    matched_fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    missing_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    validated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
