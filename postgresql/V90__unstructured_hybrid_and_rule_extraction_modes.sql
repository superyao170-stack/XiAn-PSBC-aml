-- Non-structured case extraction can run as the original all-LLM pipeline,
-- a hybrid pipeline with deterministic graph assembly and model fallback, or
-- a fully deterministic rule-only degradation path.

INSERT INTO algorithm_definition
    (algorithm_id,algorithm_name,algorithm_type,task_type,description,
     current_version,status,created_by)
VALUES
    ('UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION',
     '非结构化案例混合图谱抽取',
     'HYBRID_IE_PIPELINE','UNSTRUCTURED',
     '模型负责语义节点抽取，案例关系由确定性程序装配；模型不可用时逐阶段降级到规则抽取。',
     '2.0.0','ACTIVE','system'),
    ('UNSTRUCTURED_RULE_GRAPH_EXTRACTION',
     '非结构化案例规则降级抽取',
     'RULE_IE_PIPELINE','UNSTRUCTURED',
     '不依赖大模型，基于词典、模式、原文锚点和确定性关系装配生成待人工完善案例。',
     '1.0.0','ACTIVE','system')
ON CONFLICT (algorithm_id) DO UPDATE SET
    algorithm_name=EXCLUDED.algorithm_name,
    algorithm_type=EXCLUDED.algorithm_type,
    description=EXCLUDED.description,
    current_version=EXCLUDED.current_version,
    status=EXCLUDED.status,
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,inference_config,source_file,source_root,
     status,changelog,created_by,published_at)
VALUES
    ('UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION','2.0.0','PYTHON_HYBRID',
     '{"type":"object","required":["document"]}'::jsonb,
     '{"type":"object","required":["case","events","entities","evidences"]}'::jsonb,
     '{"type":"object","properties":{"executionMode":{"enum":["HYBRID_AUTO"]},"llmProvider":{"type":"string"},"llmModel":{"type":"string"}}}'::jsonb,
     '{"executionMode":"HYBRID_AUTO","structuredMode":"json_object"}'::jsonb,
     '{"requestMode":"DOCUMENT","fallback":"RULE_ONLY","deterministicEdges":true,"maximumLlmStages":5}'::jsonb,
     './worker/unstructured/src/main.py','./worker/unstructured',
     'ACTIVE','减少四个关系阶段的模型调用，并支持逐阶段规则降级。','system',CURRENT_TIMESTAMP),
    ('UNSTRUCTURED_RULE_GRAPH_EXTRACTION','1.0.0','PYTHON_RULE',
     '{"type":"object","required":["document"]}'::jsonb,
     '{"type":"object","required":["case","events","entities","evidences"]}'::jsonb,
     '{"type":"object","properties":{"executionMode":{"enum":["RULE_ONLY"]}}}'::jsonb,
     '{"executionMode":"RULE_ONLY"}'::jsonb,
     '{"requestMode":"DOCUMENT","requiresModel":false,"maximumLlmStages":0}'::jsonb,
     './worker/unstructured/src/main.py','./worker/unstructured',
     'ACTIVE','新增完全不依赖模型的最低可用抽取路径。','system',CURRENT_TIMESTAMP)
ON CONFLICT (algorithm_id,algorithm_version) DO UPDATE SET
    parameter_schema=EXCLUDED.parameter_schema,
    default_parameters=EXCLUDED.default_parameters,
    inference_config=EXCLUDED.inference_config,
    source_file=EXCLUDED.source_file,
    source_root=EXCLUDED.source_root,
    status=EXCLUDED.status,
    changelog=EXCLUDED.changelog,
    published_at=COALESCE(algorithm_version_registry.published_at,CURRENT_TIMESTAMP);

UPDATE algorithm_version_registry
SET default_parameters =
        COALESCE(default_parameters,'{}'::jsonb)
        || '{"executionMode":"LEGACY_LLM"}'::jsonb,
    inference_config =
        COALESCE(inference_config,'{}'::jsonb)
        || '{"requiresModel":true,"maximumLlmStages":9}'::jsonb
WHERE algorithm_id='UNSTRUCTURED_CASE_GRAPH_EXTRACTION'
  AND algorithm_version='1.0.0';

UPDATE worker_registry
SET runtime_config =
        COALESCE(runtime_config,'{}'::jsonb)
        || '{"supportedExecutionModes":["LEGACY_LLM","HYBRID_AUTO","RULE_ONLY"],"defaultExecutionMode":"HYBRID_AUTO","modelOptional":true}'::jsonb,
    resource_requirements =
        COALESCE(resource_requirements,'{}'::jsonb)
        || '{"llm":"optional"}'::jsonb,
    updated_at=CURRENT_TIMESTAMP
WHERE worker_id='UNSTRUCTURED_CASE_WORKER'
  AND worker_version='1.0.0';

UPDATE algorithm_assembly
SET status='DRAFT',
    parameters=COALESCE(parameters,'{}'::jsonb)
        || '{"executionMode":"LEGACY_LLM"}'::jsonb,
    updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-AML-DEFAULT';

INSERT INTO algorithm_assembly
    (assembly_id,assembly_name,task_type,scenario_code,bank_code,
     algorithm_id,algorithm_version,worker_id,worker_version,parameters,
     priority,weight,required,status,created_by,published_at)
VALUES
    ('ASM-UNSTRUCTURED-HYBRID-AML-DEFAULT',
     '反洗钱非结构化混合抽取默认装配',
     'UNSTRUCTURED','AML',NULL,
     'UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION','2.0.0',
     'UNSTRUCTURED_CASE_WORKER','1.0.0',
     '{"executionMode":"HYBRID_AUTO","structuredMode":"json_object"}'::jsonb,
     200,1.0,true,'ACTIVE','system',CURRENT_TIMESTAMP),
    ('ASM-UNSTRUCTURED-RULE-AML-FALLBACK',
     '反洗钱非结构化规则降级装配',
     'UNSTRUCTURED','AML',NULL,
     'UNSTRUCTURED_RULE_GRAPH_EXTRACTION','1.0.0',
     'UNSTRUCTURED_CASE_WORKER','1.0.0',
     '{"executionMode":"RULE_ONLY"}'::jsonb,
     190,1.0,false,'DRAFT','system',NULL)
ON CONFLICT (assembly_id) DO UPDATE SET
    algorithm_id=EXCLUDED.algorithm_id,
    algorithm_version=EXCLUDED.algorithm_version,
    worker_id=EXCLUDED.worker_id,
    worker_version=EXCLUDED.worker_version,
    parameters=EXCLUDED.parameters,
    priority=EXCLUDED.priority,
    weight=EXCLUDED.weight,
    required=EXCLUDED.required,
    status=EXCLUDED.status,
    updated_at=CURRENT_TIMESTAMP,
    published_at=EXCLUDED.published_at;
