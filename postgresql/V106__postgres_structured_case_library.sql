CREATE TABLE IF NOT EXISTS structured_case_library (
    case_id VARCHAR(64) PRIMARY KEY REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    recognition_mode VARCHAR(16) NOT NULL,
    case_document JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_structured_case_library_recognition_mode
        CHECK (recognition_mode IN ('NEW', 'HISTORICAL')),
    CONSTRAINT ck_structured_case_library_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_structured_case_library_scope
    ON structured_case_library(bank_code, scenario_code, status, updated_at DESC);

COMMENT ON TABLE structured_case_library IS
    'PostgreSQL-backed canonical history library for structured-case similarity matching';
COMMENT ON COLUMN structured_case_library.case_document IS
    'Canonical final_case JSON consumed by the structured GED/semantic matcher';

-- Migrate structured-case results that were already persisted in job JSON
-- before PostgreSQL became the canonical matching library.
WITH candidates AS (
    SELECT r->>'caseId' AS case_id,
           j.bank_code,
           j.scenario_code,
           CASE
               WHEN r->>'recognitionMode' IN ('NEW', 'HISTORICAL') THEN r->>'recognitionMode'
               WHEN COALESCE((r->'extractionResult'->>'generated')::boolean, false) THEN 'NEW'
               ELSE 'HISTORICAL'
           END AS recognition_mode,
           COALESCE(r->'extractionResult'->'data', r->'frameworkExtraction') AS case_document
      FROM analysis_job_step s
      JOIN analysis_job j ON j.job_id=s.job_id AND j.job_type='STRUCTURED'
      CROSS JOIN LATERAL jsonb_array_elements(s.result_json->'results') r
     WHERE s.status='SUCCEEDED'
       AND jsonb_typeof(s.result_json->'results')='array'
)
INSERT INTO structured_case_library
    (case_id,bank_code,scenario_code,recognition_mode,case_document,content_sha256,status)
SELECT c.case_id,c.bank_code,c.scenario_code,c.recognition_mode,c.case_document,
       encode(sha256(convert_to(c.case_document::text,'UTF8')),'hex'),'ACTIVE'
  FROM candidates c
  JOIN cf_risk_case rc ON rc.case_id=c.case_id AND rc.deleted=false
 WHERE c.case_id IS NOT NULL AND btrim(c.case_id)<>''
   AND jsonb_typeof(c.case_document)='object'
   AND COALESCE(c.case_document->'basic_info'->>'case_id','')<>''
ON CONFLICT (case_id) DO NOTHING;
