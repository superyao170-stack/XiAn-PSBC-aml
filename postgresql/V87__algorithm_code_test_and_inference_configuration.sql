-- Complete the algorithm asset lifecycle: versioned source, inference
-- configuration and auditable test runs. Published versions remain immutable.

ALTER TABLE algorithm_version_registry
    ADD COLUMN IF NOT EXISTS source_file VARCHAR(500),
    ADD COLUMN IF NOT EXISTS source_code TEXT,
    ADD COLUMN IF NOT EXISTS inference_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS code_updated_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS code_updated_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS algorithm_test_run (
    test_run_id VARCHAR(64) PRIMARY KEY,
    algorithm_id VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    worker_id VARCHAR(64),
    worker_version VARCHAR(32),
    test_mode VARCHAR(32) NOT NULL,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_payload JSONB,
    status VARCHAR(24) NOT NULL,
    duration_ms BIGINT,
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_algorithm_test_version
        FOREIGN KEY (algorithm_id, algorithm_version)
        REFERENCES algorithm_version_registry(algorithm_id, algorithm_version)
);

CREATE INDEX IF NOT EXISTS idx_algorithm_test_run_asset
    ON algorithm_test_run(algorithm_id, algorithm_version, created_at DESC);

UPDATE algorithm_version_registry
SET inference_config = jsonb_build_object(
        'timeoutSeconds', 15,
        'decisionThreshold', COALESCE(default_parameters -> 'decisionThreshold', '0.5'::jsonb),
        'requestMode', CASE WHEN implementation_type LIKE '%LLM%' THEN 'DOCUMENT'
                            ELSE 'TRANSACTIONS' END
    )
WHERE inference_config = '{}'::jsonb;
