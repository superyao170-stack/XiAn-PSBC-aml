CREATE TABLE IF NOT EXISTS case_analysis_job_rel (
    case_id VARCHAR(64) NOT NULL,
    job_id VARCHAR(64) NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    batch_id BIGINT,
    bank_code VARCHAR(32) NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(case_id, job_id)
);

CREATE INDEX IF NOT EXISTS idx_case_analysis_job_job ON case_analysis_job_rel(job_id);

INSERT INTO case_analysis_job_rel(case_id,job_id,batch_id,bank_code,job_type)
SELECT DISTINCT match[1],j.job_id,j.batch_id,j.bank_code,j.job_type
FROM analysis_job j
JOIN analysis_job_step s ON s.job_id=j.job_id AND s.status='SUCCEEDED',
LATERAL regexp_matches(s.result_json::text, '"caseId"\s*:\s*"([^"]+)"') match
WHERE j.job_type IN ('STRUCTURED','UNSTRUCTURED')
ON CONFLICT DO NOTHING;
