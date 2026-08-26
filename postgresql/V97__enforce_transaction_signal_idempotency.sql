-- A transaction risk signal is a reusable business result. Re-running the same
-- algorithm version for the same transaction and scenario must not append a
-- second logical signal.
CREATE TEMP TABLE tmp_duplicate_transaction_signal ON COMMIT DROP AS
WITH ranked AS (
    SELECT
        s.signal_id,
        FIRST_VALUE(s.signal_id) OVER (
            PARTITION BY s.bank_code,s.workspace_id,s.source_transaction_id,
                         s.scenario_code,COALESCE(s.algorithm_id,''),
                         COALESCE(s.algorithm_version,'')
            ORDER BY
                CASE WHEN EXISTS (
                    SELECT 1 FROM case_signal_rel csr WHERE csr.signal_id=s.signal_id
                ) THEN 0 ELSE 1 END,
                s.created_at,
                s.id
        ) AS keeper_signal_id,
        ROW_NUMBER() OVER (
            PARTITION BY s.bank_code,s.workspace_id,s.source_transaction_id,
                         s.scenario_code,COALESCE(s.algorithm_id,''),
                         COALESCE(s.algorithm_version,'')
            ORDER BY
                CASE WHEN EXISTS (
                    SELECT 1 FROM case_signal_rel csr WHERE csr.signal_id=s.signal_id
                ) THEN 0 ELSE 1 END,
                s.created_at,
                s.id
        ) AS duplicate_rank
    FROM risk_signal s
    WHERE s.signal_type='TRANSACTION'
      AND s.source_transaction_id IS NOT NULL
)
SELECT signal_id AS duplicate_signal_id,keeper_signal_id
FROM ranked
WHERE duplicate_rank>1;

INSERT INTO risk_signal_analysis_job_rel(signal_id,job_id,bank_code,created_at)
SELECT m.keeper_signal_id,r.job_id,r.bank_code,r.created_at
FROM risk_signal_analysis_job_rel r
JOIN tmp_duplicate_transaction_signal m ON m.duplicate_signal_id=r.signal_id
ON CONFLICT (signal_id,job_id) DO NOTHING;

INSERT INTO case_signal_rel(case_id,case_version,signal_id,bank_code,signal_role,created_at)
SELECT r.case_id,r.case_version,m.keeper_signal_id,r.bank_code,r.signal_role,r.created_at
FROM case_signal_rel r
JOIN tmp_duplicate_transaction_signal m ON m.duplicate_signal_id=r.signal_id
ON CONFLICT (case_id,signal_id) DO NOTHING;

INSERT INTO entity_signal_ref(entity_uid,signal_id,ref_type,ref_source_id,confidence,created_at)
SELECT r.entity_uid,m.keeper_signal_id,r.ref_type,r.ref_source_id,r.confidence,r.created_at
FROM entity_signal_ref r
JOIN tmp_duplicate_transaction_signal m ON m.duplicate_signal_id=r.signal_id
ON CONFLICT (entity_uid,signal_id,ref_type) DO NOTHING;

UPDATE risk_signal_review_record r
SET signal_id=m.keeper_signal_id
FROM tmp_duplicate_transaction_signal m
WHERE r.signal_id=m.duplicate_signal_id;

DELETE FROM risk_signal_analysis_job_rel
WHERE signal_id IN (SELECT duplicate_signal_id FROM tmp_duplicate_transaction_signal);
DELETE FROM case_signal_rel
WHERE signal_id IN (SELECT duplicate_signal_id FROM tmp_duplicate_transaction_signal);
DELETE FROM entity_signal_ref
WHERE signal_id IN (SELECT duplicate_signal_id FROM tmp_duplicate_transaction_signal);
DELETE FROM risk_signal
WHERE signal_id IN (SELECT duplicate_signal_id FROM tmp_duplicate_transaction_signal);

CREATE UNIQUE INDEX IF NOT EXISTS uk_risk_signal_transaction_algorithm
    ON risk_signal (
        bank_code,
        workspace_id,
        source_transaction_id,
        scenario_code,
        COALESCE(algorithm_id, ''),
        COALESCE(algorithm_version, '')
    )
    WHERE signal_type = 'TRANSACTION'
      AND source_transaction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_risk_signal_job_rel_signal
    ON risk_signal_analysis_job_rel(signal_id, job_id);
