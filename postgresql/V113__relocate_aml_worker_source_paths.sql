UPDATE algorithm_version_registry
SET source_root = './workers/aml-intelligence',
    source_file = CASE
        WHEN algorithm_id = 'AML_ANALYSIS_TEXT_GENERATION'
            THEN './workers/aml-intelligence/aml_analysis_workflow/workflow.py'
        WHEN algorithm_id = 'CASE_GRAPH_SIMILARITY_GED'
            THEN './workers/aml-intelligence/case_similarity.py'
        ELSE source_file
    END
WHERE algorithm_id IN ('AML_ANALYSIS_TEXT_GENERATION', 'CASE_GRAPH_SIMILARITY_GED');

UPDATE worker_registry
SET package_path = './workers/aml-intelligence',
    updated_at = CURRENT_TIMESTAMP
WHERE worker_id = 'AML_INTELLIGENCE_WORKER';
