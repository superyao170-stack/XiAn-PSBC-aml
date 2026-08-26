-- Expand the Algorithm Center from three coarse pipelines into concrete
-- algorithms, explicit worker capabilities and a runnable identification worker.

CREATE TABLE IF NOT EXISTS worker_algorithm_capability (
    worker_id VARCHAR(64) NOT NULL,
    worker_version VARCHAR(32) NOT NULL,
    algorithm_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    capability_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (worker_id, worker_version, algorithm_id),
    CONSTRAINT fk_worker_capability_worker
        FOREIGN KEY (worker_id, worker_version)
        REFERENCES worker_registry(worker_id, worker_version) ON DELETE CASCADE,
    CONSTRAINT fk_worker_capability_algorithm
        FOREIGN KEY (algorithm_id)
        REFERENCES algorithm_definition(algorithm_id) ON DELETE CASCADE
);

INSERT INTO algorithm_definition
    (algorithm_id,algorithm_name,algorithm_type,task_type,description,current_version,status,created_by)
VALUES
    ('AMOUNT_THRESHOLD','金额阈值存疑交易识别','RULE','IDENTIFICATION',
     '透明可解释的金额阈值基线算法，可作为远程Worker算法或故障回退算法。','1.0.0','ACTIVE','system'),
    ('FAST_TRANSFER_SCORE','大额快速转移评分','BINARY_CLASSIFICATION','IDENTIFICATION',
     '综合交易金额和通道信息输出存疑交易评分的可执行基线算法。','1.0.0','ACTIVE','system'),
    ('AML_LOGISTIC_REGRESSION','AML逻辑回归二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction EDA基线中的逻辑回归实验算法。','0.1.0','REGISTERED','system'),
    ('AML_RANDOM_FOREST','AML随机森林二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction EDA基线中的随机森林实验算法。','0.1.0','REGISTERED','system'),
    ('AML_LIGHTGBM','AML LightGBM二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction EDA基线中的LightGBM实验算法。','0.1.0','REGISTERED','system'),
    ('AML_XGBOOST','AML XGBoost二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction图检测实验中的XGBoost算法。','0.1.0','REGISTERED','system'),
    ('AML_GCN','AML GCN图二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction图检测实验中的GCN算法。','0.1.0','REGISTERED','system'),
    ('AML_GAT','AML GAT图二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction图检测实验中的GAT算法。','0.1.0','REGISTERED','system'),
    ('AML_GRAPHSAGE','AML GraphSAGE图二分类','BINARY_CLASSIFICATION','IDENTIFICATION',
     'DataGraph-TransactionExtraction图检测实验中的GraphSAGE算法。','0.1.0','REGISTERED','system'),
    ('AML_XGB_GNN_ENSEMBLE','AML XGBoost与GNN融合二分类','ENSEMBLE','IDENTIFICATION',
     'DataGraph-TransactionExtraction中的XGBoost与最佳GNN融合实验方案。','0.1.0','REGISTERED','system'),
    ('TX_LINE_BFS_CLUSTER','交易线图BFS聚类','GRAPH_CLUSTERING','STRUCTURED',
     '按有向交易线图广度优先遍历形成案例连通分量。','0.1.0','REGISTERED','system'),
    ('TX_LINE_DFS_CLUSTER','交易线图DFS聚类','GRAPH_CLUSTERING','STRUCTURED',
     '按有向交易线图深度优先遍历形成案例连通分量。','0.1.0','REGISTERED','system'),
    ('TX_LINE_SCC_CLUSTER','交易线图强连通分量聚类','GRAPH_CLUSTERING','STRUCTURED',
     '按有向交易线图强连通分量形成案例。','0.1.0','REGISTERED','system'),
    ('TX_LINE_LOUVAIN_CLUSTER','交易线图Louvain社区聚类','GRAPH_CLUSTERING','STRUCTURED',
     '在无向加权交易线图上执行Louvain社区发现。','0.1.0','REGISTERED','system'),
    ('TX_LINE_LEIDEN_CLUSTER','交易线图Leiden社区聚类','GRAPH_CLUSTERING','STRUCTURED',
     '在无向加权交易线图上执行Leiden社区发现。','0.1.0','REGISTERED','system'),
    ('TX_GRAPHSAGE_INFONCE_CLUSTER','GraphSAGE与InfoNCE案例聚类','GRAPH_CLUSTERING','STRUCTURED',
     'GraphSAGE编码、监督InfoNCE训练及基于余弦距离的层次聚类。','0.1.0','REGISTERED','system')
ON CONFLICT (algorithm_id) DO NOTHING;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,status,changelog,created_by,published_at)
VALUES
    ('AMOUNT_THRESHOLD','1.0.0','PYTHON',
     '{"type":"object","required":["transactions"]}'::jsonb,
     '{"type":"object","required":["results"]}'::jsonb,
     '{"type":"object","properties":{"thresholdAmount":{"type":"number"},"decisionThreshold":{"type":"number"}}}'::jsonb,
     '{"thresholdAmount":100000,"decisionThreshold":0.5}'::jsonb,
     'ACTIVE','独立存疑交易Worker可执行基线','system',CURRENT_TIMESTAMP),
    ('FAST_TRANSFER_SCORE','1.0.0','PYTHON',
     '{"type":"object","required":["transactions"]}'::jsonb,
     '{"type":"object","required":["results"]}'::jsonb,
     '{"type":"object","properties":{"minimumAmount":{"type":"number"},"decisionThreshold":{"type":"number"}}}'::jsonb,
     '{"minimumAmount":100000,"decisionThreshold":0.5}'::jsonb,
     'ACTIVE','独立存疑交易Worker首个生产算法','system',CURRENT_TIMESTAMP),
    ('AML_LOGISTIC_REGRESSION','0.1.0','SCIKIT_LEARN','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由EDA基线Notebook登记，待导出模型与验证','system',NULL),
    ('AML_RANDOM_FOREST','0.1.0','SCIKIT_LEARN','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由EDA基线Notebook登记，待导出模型与验证','system',NULL),
    ('AML_LIGHTGBM','0.1.0','LIGHTGBM','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由EDA基线Notebook登记，待导出模型与验证','system',NULL),
    ('AML_XGBOOST','0.1.0','XGBOOST','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由图检测Notebook登记，待导出模型与验证','system',NULL),
    ('AML_GCN','0.1.0','PYTORCH_GEOMETRIC','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由图检测Notebook登记，待导出模型与验证','system',NULL),
    ('AML_GAT','0.1.0','PYTORCH_GEOMETRIC','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由图检测Notebook登记，待导出模型与验证','system',NULL),
    ('AML_GRAPHSAGE','0.1.0','PYTORCH_GEOMETRIC','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由图检测Notebook登记，待导出模型与验证','system',NULL),
    ('AML_XGB_GNN_ENSEMBLE','0.1.0','PYTHON_ENSEMBLE','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','由图检测Notebook登记，待完成制品装配','system',NULL),
    ('TX_LINE_BFS_CLUSTER','0.1.0','PYTHON','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','CaseClustering基线源码登记','system',NULL),
    ('TX_LINE_DFS_CLUSTER','0.1.0','PYTHON','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','CaseClustering基线源码登记','system',NULL),
    ('TX_LINE_SCC_CLUSTER','0.1.0','PYTHON_IGRAPH','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,
     'DRAFT','CaseClustering基线源码登记','system',NULL),
    ('TX_LINE_LOUVAIN_CLUSTER','0.1.0','PYTHON_IGRAPH','{}'::jsonb,'{}'::jsonb,
     '{"type":"object","properties":{"resolution":{"type":"number"}}}'::jsonb,'{"resolution":1.0}'::jsonb,
     'DRAFT','CaseClustering基线源码登记','system',NULL),
    ('TX_LINE_LEIDEN_CLUSTER','0.1.0','PYTHON_LEIDENALG','{}'::jsonb,'{}'::jsonb,
     '{"type":"object","properties":{"resolution":{"type":"number"},"seed":{"type":"integer"}}}'::jsonb,
     '{"resolution":1.0,"seed":42}'::jsonb,
     'DRAFT','CaseClustering基线源码登记','system',NULL),
    ('TX_GRAPHSAGE_INFONCE_CLUSTER','0.1.0','PYTORCH','{}'::jsonb,'{}'::jsonb,
     '{"type":"object","properties":{"similarityThreshold":{"type":"number"}}}'::jsonb,
     '{"similarityThreshold":0.5}'::jsonb,
     'DRAFT','LearningBasedCaseClustering源码登记，待训练制品验证','system',NULL)
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

UPDATE worker_registry
SET status='INACTIVE',updated_at=CURRENT_TIMESTAMP
WHERE worker_id='TRANSACTION_RISK_CLASSIFIER_WORKER' AND worker_version='0.1.0';

INSERT INTO worker_registry
    (worker_id,worker_version,worker_name,task_type,worker_type,execution_mode,
     endpoint,health_check_url,package_path,entrypoint,runtime_config,resource_requirements,
     input_contract_version,output_contract_version,status,created_by)
VALUES
    ('TRANSACTION_RISK_CLASSIFIER_WORKER','1.0.0','存疑交易识别Worker','IDENTIFICATION',
     'PYTHON','HTTP','http://127.0.0.1:18081/v1/inference','http://127.0.0.1:18081/health',
     './worker/identification','app.py',
     '{"port":18081,"contract":"algorithm-dispatch-v1"}'::jsonb,
     '{"cpu":"2","memory":"2Gi"}'::jsonb,'1.0','1.0','ACTIVE','system')
ON CONFLICT (worker_id,worker_version) DO UPDATE SET
    endpoint=EXCLUDED.endpoint,health_check_url=EXCLUDED.health_check_url,
    package_path=EXCLUDED.package_path,entrypoint=EXCLUDED.entrypoint,
    runtime_config=EXCLUDED.runtime_config,status='ACTIVE',updated_at=CURRENT_TIMESTAMP;

INSERT INTO worker_algorithm_capability
    (worker_id,worker_version,algorithm_id,enabled,capability_config)
VALUES
    ('TRANSACTION_RISK_CLASSIFIER_WORKER','1.0.0','AMOUNT_THRESHOLD',true,'{}'::jsonb),
    ('TRANSACTION_RISK_CLASSIFIER_WORKER','1.0.0','FAST_TRANSFER_SCORE',true,'{}'::jsonb),
    ('STRUCTURED_CASE_WORKER','1.0.0','STRUCTURED_CASE_GRAPH_CLUSTER',true,'{}'::jsonb),
    ('UNSTRUCTURED_CASE_WORKER','1.0.0','UNSTRUCTURED_CASE_GRAPH_EXTRACTION',true,'{}'::jsonb)
ON CONFLICT (worker_id,worker_version,algorithm_id) DO UPDATE SET
    enabled=EXCLUDED.enabled,capability_config=EXCLUDED.capability_config,
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO algorithm_assembly
    (assembly_id,assembly_name,task_type,scenario_code,bank_code,
     algorithm_id,algorithm_version,worker_id,worker_version,parameters,
     priority,weight,required,status,created_by,published_at)
VALUES
    ('ASM-IDENTIFICATION-AML-DEFAULT','反洗钱存疑交易识别默认装配','IDENTIFICATION','AML',NULL,
     'FAST_TRANSFER_SCORE','1.0.0','TRANSACTION_RISK_CLASSIFIER_WORKER','1.0.0',
     '{"minimumAmount":100000,"decisionThreshold":0.5}'::jsonb,
     100,1.0,true,'ACTIVE','system',CURRENT_TIMESTAMP)
ON CONFLICT (assembly_id) DO UPDATE SET
    algorithm_id=EXCLUDED.algorithm_id,algorithm_version=EXCLUDED.algorithm_version,
    worker_id=EXCLUDED.worker_id,worker_version=EXCLUDED.worker_version,
    parameters=EXCLUDED.parameters,status='ACTIVE',updated_at=CURRENT_TIMESTAMP;
