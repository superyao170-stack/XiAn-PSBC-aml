-- A signal has a stable business identity and may be produced again by a rerun,
-- so persist the many-to-many execution lineage separately.
CREATE TABLE IF NOT EXISTS risk_signal_analysis_job_rel (
    signal_id VARCHAR(64) NOT NULL REFERENCES risk_signal(signal_id),
    job_id VARCHAR(64) NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    bank_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (signal_id, job_id)
);

CREATE INDEX IF NOT EXISTS idx_signal_analysis_job_job
    ON risk_signal_analysis_job_rel(job_id);

INSERT INTO risk_signal_analysis_job_rel(signal_id,job_id,bank_code)
SELECT DISTINCT s.signal_id,j.job_id,s.bank_code
FROM risk_signal s
JOIN analysis_job j
  ON j.job_type='IDENTIFICATION'
 AND j.bank_code=s.bank_code
 AND j.workspace_id=s.workspace_id
 AND j.batch_id=s.source_batch_id
 AND j.scenario_code=s.scenario_code
WHERE s.signal_type='TRANSACTION'
ON CONFLICT (signal_id,job_id) DO NOTHING;

-- Link manually reviewed suspicious-transaction cases back to their originating
-- identification jobs so the five-step guide reflects the persisted business state.
INSERT INTO case_analysis_job_rel(case_id,job_id,batch_id,bank_code,job_type)
SELECT DISTINCT csr.case_id,sjr.job_id,s.source_batch_id,s.bank_code,j.job_type
FROM case_signal_rel csr
JOIN risk_signal s ON s.signal_id=csr.signal_id
JOIN risk_signal_analysis_job_rel sjr ON sjr.signal_id=s.signal_id
JOIN analysis_job j ON j.job_id=sjr.job_id
ON CONFLICT (case_id,job_id) DO NOTHING;
