CREATE INDEX IF NOT EXISTS idx_analysis_job_batch_type_status
    ON analysis_job (batch_id, job_type, status);

CREATE INDEX IF NOT EXISTS idx_risk_signal_batch_decision_type
    ON risk_signal (source_batch_id, decision, signal_type, signal_id);

CREATE INDEX IF NOT EXISTS idx_signal_job_rel_signal_job
    ON risk_signal_analysis_job_rel (signal_id, job_id);

CREATE INDEX IF NOT EXISTS idx_knowledge_relation_scope_order
    ON knowledge_asset_relation
       (bank_code, source_type, source_code, relation_type, target_type, target_code);

CREATE INDEX IF NOT EXISTS idx_analytics_artifact_run_type_created
    ON analytics_artifact (run_id, artifact_type, created_at DESC);
