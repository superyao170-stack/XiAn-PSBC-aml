-- The retained structured-case pipeline starts from case material and runs
-- report generation, framework extraction, similarity matching and graph
-- persistence. The earlier raw-transaction scoring and transaction-component
-- clustering assemblies are no longer deployed.

UPDATE algorithm_assembly
SET status = 'RETIRED',
    required = false,
    updated_at = CURRENT_TIMESTAMP
WHERE task_type = 'IDENTIFICATION'
   OR algorithm_id IN (
       'STRUCTURED_CASE_GRAPH_CLUSTER',
       'TX_LINE_BFS_CLUSTER',
       'TX_LINE_DFS_CLUSTER',
       'TX_LINE_SCC_CLUSTER',
       'TX_LINE_LOUVAIN_CLUSTER',
       'TX_LINE_LEIDEN_CLUSTER',
       'TX_GRAPHSAGE_INFONCE_CLUSTER'
   );

UPDATE worker_algorithm_capability
SET enabled = false,
    updated_at = CURRENT_TIMESTAMP
WHERE worker_id IN ('TRANSACTION_RISK_CLASSIFIER_WORKER', 'STRUCTURED_CASE_WORKER');

UPDATE worker_registry
SET status = 'RETIRED',
    endpoint = NULL,
    health_check_url = NULL,
    package_path = NULL,
    entrypoint = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE worker_id IN ('TRANSACTION_RISK_CLASSIFIER_WORKER', 'STRUCTURED_CASE_WORKER');

UPDATE algorithm_version_registry
SET status = 'RETIRED',
    source_root = NULL,
    source_file = NULL
WHERE algorithm_id IN (
    'AML_DUAL_VIEW_GNN',
    'AML_GAT',
    'AML_GCN',
    'AML_GRAPHSAGE',
    'AML_LIGHTGBM',
    'AML_LOGISTIC_REGRESSION',
    'AML_RANDOM_FOREST',
    'AML_XGBOOST',
    'AML_XGB_GNN_ENSEMBLE',
    'AMOUNT_THRESHOLD',
    'FAST_TRANSFER_SCORE',
    'STRUCTURED_CASE_GRAPH_CLUSTER',
    'TX_LINE_BFS_CLUSTER',
    'TX_LINE_DFS_CLUSTER',
    'TX_LINE_SCC_CLUSTER',
    'TX_LINE_LOUVAIN_CLUSTER',
    'TX_LINE_LEIDEN_CLUSTER',
    'TX_GRAPHSAGE_INFONCE_CLUSTER'
);

UPDATE algorithm_definition
SET status = 'RETIRED',
    updated_at = CURRENT_TIMESTAMP
WHERE task_type = 'IDENTIFICATION'
   OR algorithm_id IN (
       'STRUCTURED_CASE_GRAPH_CLUSTER',
       'TX_LINE_BFS_CLUSTER',
       'TX_LINE_DFS_CLUSTER',
       'TX_LINE_SCC_CLUSTER',
       'TX_LINE_LOUVAIN_CLUSTER',
       'TX_LINE_LEIDEN_CLUSTER',
       'TX_GRAPHSAGE_INFONCE_CLUSTER'
   );

UPDATE analysis_job_step
SET status = 'CANCELLED',
    error_message = '旧交易识别/聚类流程已退役',
    completed_at = CURRENT_TIMESTAMP
WHERE status IN ('PENDING', 'RUNNING')
  AND job_id IN (
      SELECT job_id FROM analysis_job
      WHERE job_type IN ('IDENTIFICATION', 'PATTERN')
  );

UPDATE analysis_job
SET status = 'CANCELLED',
    error_message = '旧交易识别/聚类流程已退役',
    completed_at = CURRENT_TIMESTAMP
WHERE job_type IN ('IDENTIFICATION', 'PATTERN')
  AND status IN ('PENDING', 'RUNNING');

DELETE FROM sys_menu
WHERE path IN ('/analysis/identify', '/analysis/pattern');
