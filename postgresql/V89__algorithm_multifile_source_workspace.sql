-- A real algorithm is a package, not a single text field. Keep the deployed
-- package as the read-only baseline and store per-version file overlays.

ALTER TABLE algorithm_version_registry
    ADD COLUMN IF NOT EXISTS source_root VARCHAR(500);

CREATE TABLE IF NOT EXISTS algorithm_version_source_file (
    algorithm_id VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    content TEXT,
    content_hash CHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT false,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (algorithm_id,algorithm_version,file_path),
    CONSTRAINT fk_algorithm_source_version
        FOREIGN KEY (algorithm_id,algorithm_version)
        REFERENCES algorithm_version_registry(algorithm_id,algorithm_version)
        ON DELETE CASCADE
);

UPDATE algorithm_version_registry
SET source_root='./worker/doubtful'
WHERE algorithm_id IN ('AMOUNT_THRESHOLD','FAST_TRANSFER_SCORE')
  AND source_root IS NULL;

UPDATE algorithm_version_registry
SET source_root='./worker/algorithm-lab/src/DetectionAlgorithms'
WHERE algorithm_id IN (
    'AML_DUAL_VIEW_GNN','AML_LOGISTIC_REGRESSION','AML_RANDOM_FOREST',
    'AML_LIGHTGBM','AML_XGBOOST','AML_GCN','AML_GAT','AML_GRAPHSAGE',
    'AML_XGB_GNN_ENSEMBLE'
) AND source_root IS NULL;

UPDATE algorithm_version_registry
SET source_root='./worker/algorithm-lab/src/CaseClustering'
WHERE algorithm_id IN (
    'TX_LINE_BFS_CLUSTER','TX_LINE_DFS_CLUSTER','TX_LINE_SCC_CLUSTER',
    'TX_LINE_LOUVAIN_CLUSTER','TX_LINE_LEIDEN_CLUSTER'
) AND source_root IS NULL;

UPDATE algorithm_version_registry
SET source_root='./worker/algorithm-lab/src/LearningBasedCaseClustering'
WHERE algorithm_id='TX_GRAPHSAGE_INFONCE_CLUSTER' AND source_root IS NULL;

UPDATE algorithm_version_registry
SET source_root='./worker/structured'
WHERE algorithm_id='STRUCTURED_CASE_GRAPH_CLUSTER' AND source_root IS NULL;

UPDATE algorithm_version_registry
SET source_root='./worker/unstructured'
WHERE algorithm_id='UNSTRUCTURED_CASE_GRAPH_EXTRACTION' AND source_root IS NULL;
