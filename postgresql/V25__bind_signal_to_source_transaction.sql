-- A source_record_id may be repeated when the same dataset is uploaded more than once.
-- Bind each transaction signal to one physical transaction row to prevent duplicate
-- list results and cross-batch case construction.
ALTER TABLE risk_signal
    ADD COLUMN IF NOT EXISTS source_transaction_id BIGINT REFERENCES risk_transaction_materialized(id);

UPDATE risk_signal s
SET source_transaction_id = substring(s.signal_id FROM '^SIG-TX-([0-9]+)-')::BIGINT
WHERE s.signal_type = 'TRANSACTION'
  AND s.source_transaction_id IS NULL
  AND s.signal_id ~ '^SIG-TX-[0-9]+-'
  AND EXISTS (
      SELECT 1 FROM risk_transaction_materialized t
      WHERE t.id = substring(s.signal_id FROM '^SIG-TX-([0-9]+)-')::BIGINT
  );

UPDATE risk_signal s
SET source_transaction_id = (
    SELECT t.id
    FROM risk_transaction_materialized t
    WHERE t.bank_code = s.bank_code
      AND t.workspace_id = s.workspace_id
      AND t.source_record_id = s.source_ref_id
    ORDER BY t.id DESC
    LIMIT 1
)
WHERE s.signal_type = 'TRANSACTION'
  AND s.source_transaction_id IS NULL
  AND EXISTS (
      SELECT 1 FROM risk_transaction_materialized t
      WHERE t.bank_code = s.bank_code
        AND t.workspace_id = s.workspace_id
        AND t.source_record_id = s.source_ref_id
  );

CREATE INDEX IF NOT EXISTS idx_risk_signal_source_transaction
    ON risk_signal(source_transaction_id)
    WHERE signal_type = 'TRANSACTION';

CREATE INDEX IF NOT EXISTS idx_risk_signal_bank_status_created
    ON risk_signal(bank_code, status, created_at DESC)
    WHERE signal_type = 'TRANSACTION';
