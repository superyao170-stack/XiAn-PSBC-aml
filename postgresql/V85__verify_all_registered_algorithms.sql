-- All algorithms now have runnable implementations and repeatable functional tests.
-- Verification is independent of publication: research algorithms remain DRAFT until
-- a bank dataset benchmark and model artifact are approved for production.

UPDATE algorithm_version_registry
SET verification_status='VERIFIED',
    verification_summary='标准化可执行实现已通过合成AML数据训练、预测、确定性及输出契约测试；版本仍为DRAFT，发布前需完成银行数据指标验收',
    last_verified_at=CURRENT_TIMESTAMP,
    input_schema='{"type":"object","required":["features","labels"],"properties":{"features":{"type":"array"},"labels":{"type":"array"},"edgeIndex":{"type":"array"},"contextEdgeIndex":{"type":"array"}}}'::jsonb,
    output_schema='{"type":"object","required":["probabilities"],"properties":{"probabilities":{"type":"array","items":{"type":"number","minimum":0,"maximum":1}}}}'::jsonb
WHERE algorithm_id IN (
    'AML_DUAL_VIEW_GNN','AML_LOGISTIC_REGRESSION','AML_RANDOM_FOREST','AML_LIGHTGBM',
    'AML_XGBOOST','AML_GCN','AML_GAT','AML_GRAPHSAGE','AML_XGB_GNN_ENSEMBLE'
);

UPDATE algorithm_version_registry
SET verification_status='VERIFIED',
    verification_summary='已在完整python-igraph、leidenalg、PyTorch环境通过交易线图构建、聚类结果、标签隔离、指标及边界条件测试',
    last_verified_at=CURRENT_TIMESTAMP,
    input_schema='{"type":"object","required":["transactions"],"properties":{"transactions":{"type":"array"},"parameters":{"type":"object"}}}'::jsonb,
    output_schema='{"type":"object","required":["clusters","edgeToCluster"],"properties":{"clusters":{"type":"array"},"edgeToCluster":{"type":"object"}}}'::jsonb
WHERE algorithm_id IN (
    'TX_LINE_BFS_CLUSTER','TX_LINE_DFS_CLUSTER','TX_LINE_SCC_CLUSTER',
    'TX_LINE_LOUVAIN_CLUSTER','TX_LINE_LEIDEN_CLUSTER','TX_GRAPHSAGE_INFONCE_CLUSTER'
);
