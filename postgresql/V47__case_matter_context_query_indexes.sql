CREATE INDEX IF NOT EXISTS idx_risk_tx_batch_account_time
    ON risk_transaction_materialized(batch_id, account_hash, occurred_at);

CREATE INDEX IF NOT EXISTS idx_risk_tx_batch_counterparty_time
    ON risk_transaction_materialized(batch_id, counterparty_hash, occurred_at);
