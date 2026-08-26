-- Production structured case clustering V1.1:
-- prevent unrelated transactions on a long-lived shared account from being
-- collapsed into one case by adding a configurable temporal session boundary.

UPDATE algorithm_definition
SET current_version='1.1.0',
    description='消费存疑交易结果，按照账户关系和时间会话形成结构化案例；保留原始全时段账户连通分量作为可回退策略。',
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='STRUCTURED_CASE_GRAPH_CLUSTER';

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,inference_config,source_file,source_root,
     status,changelog,created_by,published_at)
VALUES
    ('STRUCTURED_CASE_GRAPH_CLUSTER','1.1.0','PYTHON',
     '{"type":"object","required":["accounts","transactions","signals"]}'::jsonb,
     '{"type":"object","required":["componentCases"],"properties":{"componentCases":{"type":"array"},"clusterStrategy":{"type":"string"}}}'::jsonb,
     '{"type":"object","properties":{"clusterStrategy":{"enum":["TEMPORAL_ACCOUNT_COMPONENT","ACCOUNT_COMPONENT"]},"maxGapMinutes":{"type":"integer","minimum":1,"maximum":10080},"maxCases":{"type":"integer","minimum":1,"maximum":500},"maxTransactionsPerCase":{"type":"integer","minimum":1,"maximum":10000}}}'::jsonb,
     '{"clusterStrategy":"TEMPORAL_ACCOUNT_COMPONENT","maxGapMinutes":1440,"maxCases":500,"maxTransactionsPerCase":10000}'::jsonb,
     '{"requestMode":"BATCH","requiresModel":false,"candidateSource":"UPSTREAM_SIGNAL","deterministic":true,"fallbackStrategy":"ACCOUNT_COMPONENT"}'::jsonb,
     './worker/structured/src/main.py','./worker/structured',
     'ACTIVE',
     '增加账户关系加时间会话聚类，输出时间范围、金额汇总和币种；避免长期共享账户将无关交易错误合并。',
     'system',CURRENT_TIMESTAMP)
ON CONFLICT (algorithm_id,algorithm_version) DO UPDATE SET
    parameter_schema=EXCLUDED.parameter_schema,
    default_parameters=EXCLUDED.default_parameters,
    inference_config=EXCLUDED.inference_config,
    source_file=EXCLUDED.source_file,
    source_root=EXCLUDED.source_root,
    status=EXCLUDED.status,
    changelog=EXCLUDED.changelog,
    published_at=COALESCE(algorithm_version_registry.published_at,CURRENT_TIMESTAMP);

UPDATE algorithm_assembly
SET algorithm_version='1.1.0',
    parameters='{"clusterStrategy":"TEMPORAL_ACCOUNT_COMPONENT","maxGapMinutes":1440,"maxCases":500,"maxTransactionsPerCase":10000}'::jsonb,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP,
    published_at=COALESCE(published_at,CURRENT_TIMESTAMP)
WHERE assembly_id='ASM-STRUCTURED-AML-DEFAULT';

UPDATE worker_registry
SET runtime_config=COALESCE(runtime_config,'{}'::jsonb)
        || '{"supportedClusterStrategies":["TEMPORAL_ACCOUNT_COMPONENT","ACCOUNT_COMPONENT"],"defaultClusterStrategy":"TEMPORAL_ACCOUNT_COMPONENT","maxGapMinutes":1440}'::jsonb,
    updated_at=CURRENT_TIMESTAMP
WHERE worker_id='STRUCTURED_CASE_WORKER'
  AND worker_version='1.0.0';
