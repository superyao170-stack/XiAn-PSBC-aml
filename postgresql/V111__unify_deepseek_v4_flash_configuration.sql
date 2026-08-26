-- Keep model-governance metadata aligned with the runtime Worker configuration.
UPDATE algorithm_version_registry
SET default_parameters = jsonb_set(
        COALESCE(default_parameters, '{}'::jsonb),
        '{model}', '"deepseek-v4-flash"'::jsonb, true),
    inference_config = jsonb_set(
        COALESCE(inference_config, '{}'::jsonb),
        '{llmConfiguration}', '"WORKER_ENVIRONMENT"'::jsonb, true),
    code_updated_at = CURRENT_TIMESTAMP
WHERE algorithm_id = 'AML_ANALYSIS_TEXT_GENERATION';

UPDATE worker_registry
SET runtime_config = jsonb_set(
        COALESCE(runtime_config, '{}'::jsonb),
        '{llmConfiguration}', '"WORKER_ENVIRONMENT"'::jsonb, true),
    updated_at = CURRENT_TIMESTAMP
WHERE worker_id = 'AML_INTELLIGENCE_WORKER';
