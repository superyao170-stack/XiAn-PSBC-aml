-- Algorithm Center V2: separate business algorithms, immutable versions,
-- worker runtimes and task/scenario assemblies.

CREATE TABLE IF NOT EXISTS algorithm_definition (
    algorithm_id VARCHAR(64) PRIMARY KEY,
    algorithm_name VARCHAR(128) NOT NULL,
    algorithm_type VARCHAR(48) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    description TEXT,
    current_version VARCHAR(32),
    status VARCHAR(24) NOT NULL DEFAULT 'REGISTERED',
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS algorithm_version_registry (
    algorithm_id VARCHAR(64) NOT NULL REFERENCES algorithm_definition(algorithm_id),
    algorithm_version VARCHAR(32) NOT NULL,
    implementation_type VARCHAR(32) NOT NULL DEFAULT 'PYTHON',
    input_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    parameter_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    default_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_id VARCHAR(64),
    model_version VARCHAR(32),
    code_hash CHAR(64),
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    changelog TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    PRIMARY KEY (algorithm_id, algorithm_version)
);

CREATE TABLE IF NOT EXISTS worker_registry (
    worker_id VARCHAR(64) NOT NULL,
    worker_version VARCHAR(32) NOT NULL,
    worker_name VARCHAR(128) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    worker_type VARCHAR(32) NOT NULL DEFAULT 'PYTHON',
    execution_mode VARCHAR(24) NOT NULL DEFAULT 'BATCH',
    endpoint VARCHAR(255),
    health_check_url VARCHAR(255),
    package_path VARCHAR(500),
    entrypoint VARCHAR(255),
    runtime_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    resource_requirements JSONB NOT NULL DEFAULT '{}'::jsonb,
    input_contract_version VARCHAR(32),
    output_contract_version VARCHAR(32),
    status VARCHAR(24) NOT NULL DEFAULT 'REGISTERED',
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (worker_id, worker_version)
);

CREATE TABLE IF NOT EXISTS algorithm_assembly (
    assembly_id VARCHAR(64) PRIMARY KEY,
    assembly_name VARCHAR(128) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32),
    algorithm_id VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    worker_version VARCHAR(32) NOT NULL,
    model_id VARCHAR(64),
    model_version VARCHAR(32),
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 0,
    weight DECIMAL(8,4) NOT NULL DEFAULT 1.0,
    required BOOLEAN NOT NULL DEFAULT false,
    fallback_assembly_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    CONSTRAINT fk_assembly_algorithm_version
        FOREIGN KEY (algorithm_id, algorithm_version)
        REFERENCES algorithm_version_registry(algorithm_id, algorithm_version),
    CONSTRAINT fk_assembly_worker_version
        FOREIGN KEY (worker_id, worker_version)
        REFERENCES worker_registry(worker_id, worker_version)
);

CREATE INDEX IF NOT EXISTS idx_algorithm_assembly_route
    ON algorithm_assembly(task_type, scenario_code, bank_code, status, priority DESC);

CREATE TABLE IF NOT EXISTS algorithm_management_audit (
    id BIGSERIAL PRIMARY KEY,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_snapshot JSONB,
    after_snapshot JSONB,
    operator VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO algorithm_definition
    (algorithm_id,algorithm_name,algorithm_type,task_type,description,current_version,status,created_by)
VALUES
    ('AML_DUAL_VIEW_GNN','AMAP双视图交易风险识别','BINARY_CLASSIFICATION','IDENTIFICATION',
     '基于交易图、3-hop子图、Proposer与双视图GraphSAGE融合的存疑交易二分类算法。','0.1.0','REGISTERED','system'),
    ('STRUCTURED_CASE_GRAPH_CLUSTER','结构化交易案例聚类','GRAPH_CLUSTERING','STRUCTURED',
     '消费存疑交易结果，按账户和交易关系形成结构化案例分量。','1.0.0','ACTIVE','system'),
    ('UNSTRUCTURED_CASE_GRAPH_EXTRACTION','非结构化案例图谱抽取','LLM_PIPELINE','UNSTRUCTURED',
     '通过多阶段结构化抽取生成案例、实体、事件、证据及其关系。','1.0.0','ACTIVE','system')
ON CONFLICT (algorithm_id) DO NOTHING;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,status,changelog,created_by,published_at)
VALUES
    ('AML_DUAL_VIEW_GNN','0.1.0','PYTORCH',
     '{"type":"object","required":["batchId"]}'::jsonb,
     '{"type":"object","required":["results"]}'::jsonb,
     '{"type":"object","properties":{"threshold":{"type":"number"}}}'::jsonb,
     '{"threshold":0.5}'::jsonb,'DRAFT','由 amap(1).ipynb 固化的待验证版本','system',NULL),
    ('STRUCTURED_CASE_GRAPH_CLUSTER','1.0.0','PYTHON',
     '{"type":"object","required":["accounts","transactions","signals"]}'::jsonb,
     '{"type":"object","required":["componentCases"]}'::jsonb,
     '{"type":"object","properties":{"maxCases":{"type":"integer"},"maxTransactionsPerCase":{"type":"integer"}}}'::jsonb,
     '{"maxCases":500,"maxTransactionsPerCase":10000}'::jsonb,'ACTIVE','现有生产结构化案例聚类适配器','system',CURRENT_TIMESTAMP),
    ('UNSTRUCTURED_CASE_GRAPH_EXTRACTION','1.0.0','PYTHON_LLM',
     '{"type":"object","required":["document"]}'::jsonb,
     '{"type":"object","required":["case","events","entities","evidences"]}'::jsonb,
     '{"type":"object","properties":{"llmProvider":{"type":"string"},"llmModel":{"type":"string"}}}'::jsonb,
     '{"structuredMode":"json_object"}'::jsonb,'ACTIVE','现有生产多阶段非结构化抽取流水线','system',CURRENT_TIMESTAMP)
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

INSERT INTO worker_registry
    (worker_id,worker_version,worker_name,task_type,worker_type,execution_mode,
     package_path,entrypoint,runtime_config,resource_requirements,
     input_contract_version,output_contract_version,status,created_by)
VALUES
    ('TRANSACTION_RISK_CLASSIFIER_WORKER','0.1.0','存疑交易分类Worker','IDENTIFICATION',
     'PYTHON','HTTP',NULL,'/v1/inference','{}'::jsonb,
     '{"cpu":"4","memory":"8Gi","gpu":"optional"}'::jsonb,'1.0','1.0','REGISTERED','system'),
    ('STRUCTURED_CASE_WORKER','1.0.0','结构化案例聚类Worker','STRUCTURED',
     'PYTHON','BATCH','./worker/structured','src/main.py','{}'::jsonb,
     '{"cpu":"2","memory":"4Gi"}'::jsonb,'1.0','1.0','ACTIVE','system'),
    ('UNSTRUCTURED_CASE_WORKER','1.0.0','非结构化案例抽取Worker','UNSTRUCTURED',
     'PYTHON_LLM','BATCH','./worker/unstructured','src/main.py',
     '{"llmConfiguration":"SOURCE_CONSTANTS"}'::jsonb,
     '{"cpu":"4","memory":"8Gi","llm":"required"}'::jsonb,'1.0','1.0','ACTIVE','system')
ON CONFLICT (worker_id,worker_version) DO NOTHING;

INSERT INTO algorithm_assembly
    (assembly_id,assembly_name,task_type,scenario_code,bank_code,
     algorithm_id,algorithm_version,worker_id,worker_version,parameters,
     priority,weight,required,status,created_by,published_at)
VALUES
    ('ASM-STRUCTURED-AML-DEFAULT','反洗钱结构化案例聚类默认装配','STRUCTURED','AML',NULL,
     'STRUCTURED_CASE_GRAPH_CLUSTER','1.0.0','STRUCTURED_CASE_WORKER','1.0.0',
     '{"maxCases":500,"maxTransactionsPerCase":10000}'::jsonb,
     100,1.0,true,'ACTIVE','system',CURRENT_TIMESTAMP),
    ('ASM-UNSTRUCTURED-AML-DEFAULT','反洗钱非结构化抽取默认装配','UNSTRUCTURED','AML',NULL,
     'UNSTRUCTURED_CASE_GRAPH_EXTRACTION','1.0.0','UNSTRUCTURED_CASE_WORKER','1.0.0',
     '{"structuredMode":"json_object"}'::jsonb,
     100,1.0,true,'ACTIVE','system',CURRENT_TIMESTAMP)
ON CONFLICT (assembly_id) DO NOTHING;
