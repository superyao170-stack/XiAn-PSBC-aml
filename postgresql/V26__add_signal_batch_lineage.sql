-- Persist batch lineage on the signal itself so filtered pagination and auditing do
-- not need to scan/join the full transaction table.
ALTER TABLE risk_signal
    ADD COLUMN IF NOT EXISTS source_batch_id BIGINT REFERENCES risk_ingest_batch(id);

UPDATE risk_signal s
SET source_batch_id = t.batch_id
FROM risk_transaction_materialized t
WHERE s.signal_type = 'TRANSACTION'
  AND s.source_batch_id IS NULL
  AND t.id = s.source_transaction_id;

CREATE INDEX IF NOT EXISTS idx_risk_signal_source_batch_created
    ON risk_signal(source_batch_id, created_at DESC)
    WHERE signal_type = 'TRANSACTION';
