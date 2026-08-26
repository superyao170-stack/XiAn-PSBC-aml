-- Keep every Python Worker under one ./worker directory convention.
UPDATE worker_registry
SET package_path='./worker/doubtful',
    entrypoint='app.py',
    runtime_config=COALESCE(runtime_config,'{}'::jsonb)
        || '{"directoryConvention":"./worker/{worker-name}","serviceEntrypoint":"http://127.0.0.1:18081/v1/inference"}'::jsonb,
    updated_at=CURRENT_TIMESTAMP
WHERE worker_id='TRANSACTION_RISK_CLASSIFIER_WORKER'
  AND worker_version='1.0.0';
