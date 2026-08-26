-- Register the deployed implementation locations for algorithms that predate
-- versioned source management. Source remains readable from the deployed
-- package until a draft version stores an edited snapshot in source_code.

UPDATE algorithm_version_registry
SET source_file='./worker/doubtful/app.py'
WHERE algorithm_id IN ('AMOUNT_THRESHOLD','FAST_TRANSFER_SCORE')
  AND source_file IS NULL;

UPDATE algorithm_version_registry
SET source_file='./worker/algorithm-lab/src/DetectionAlgorithms/classifiers.py'
WHERE algorithm_id IN (
    'AML_DUAL_VIEW_GNN','AML_LOGISTIC_REGRESSION','AML_RANDOM_FOREST',
    'AML_LIGHTGBM','AML_XGBOOST','AML_GCN','AML_GAT','AML_GRAPHSAGE',
    'AML_XGB_GNN_ENSEMBLE'
) AND source_file IS NULL;

UPDATE algorithm_version_registry
SET source_file='./worker/algorithm-lab/src/CaseClustering/clustering.py'
WHERE algorithm_id IN (
    'TX_LINE_BFS_CLUSTER','TX_LINE_DFS_CLUSTER','TX_LINE_SCC_CLUSTER',
    'TX_LINE_LOUVAIN_CLUSTER','TX_LINE_LEIDEN_CLUSTER'
) AND source_file IS NULL;

UPDATE algorithm_version_registry
SET source_file='./worker/algorithm-lab/src/LearningBasedCaseClustering/model.py'
WHERE algorithm_id='TX_GRAPHSAGE_INFONCE_CLUSTER' AND source_file IS NULL;

UPDATE algorithm_version_registry
SET source_file='./worker/structured/src/main.py'
WHERE algorithm_id='STRUCTURED_CASE_GRAPH_CLUSTER' AND source_file IS NULL;

UPDATE algorithm_version_registry
SET source_file='./worker/unstructured/src/main.py'
WHERE algorithm_id='UNSTRUCTURED_CASE_GRAPH_EXTRACTION' AND source_file IS NULL;
