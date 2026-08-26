-- Make the distinction between "registered from source/notebook" and
-- "actually verified as a runnable production algorithm" explicit.

ALTER TABLE algorithm_version_registry
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN IF NOT EXISTS verification_summary TEXT,
    ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMP;

UPDATE algorithm_version_registry
SET verification_status='RESEARCH_ONLY',
    verification_summary='来源于Notebook实验，尚未形成经过平台验收的模型制品'
WHERE algorithm_id IN (
    'AML_DUAL_VIEW_GNN','AML_LOGISTIC_REGRESSION','AML_RANDOM_FOREST','AML_LIGHTGBM',
    'AML_XGBOOST','AML_GCN','AML_GAT','AML_GRAPHSAGE','AML_XGB_GNN_ENSEMBLE'
);

UPDATE algorithm_version_registry
SET verification_status='TEST_PENDING',
    verification_summary='源码包含测试，但当前平台环境缺少python-igraph/leidenalg等依赖，尚未完成生产验收'
WHERE algorithm_id IN (
    'TX_LINE_BFS_CLUSTER','TX_LINE_DFS_CLUSTER','TX_LINE_SCC_CLUSTER',
    'TX_LINE_LOUVAIN_CLUSTER','TX_LINE_LEIDEN_CLUSTER','TX_GRAPHSAGE_INFONCE_CLUSTER'
);

UPDATE algorithm_version_registry
SET verification_status='VERIFIED',
    verification_summary='已通过平台单元测试、Worker健康检查和94服务器真实任务验收',
    last_verified_at=CURRENT_TIMESTAMP
WHERE algorithm_id IN (
    'AMOUNT_THRESHOLD','FAST_TRANSFER_SCORE',
    'STRUCTURED_CASE_GRAPH_CLUSTER','UNSTRUCTURED_CASE_GRAPH_EXTRACTION'
);
