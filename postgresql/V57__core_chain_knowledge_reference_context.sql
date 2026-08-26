ALTER TABLE case_core_chain_knowledge_ref
    ADD COLUMN IF NOT EXISTS definition_name VARCHAR(256),
    ADD COLUMN IF NOT EXISTS definition_description TEXT,
    ADD COLUMN IF NOT EXISTS classification_path JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN case_core_chain_knowledge_ref.definition_name IS
    'Business-readable name frozen from the referenced knowledge definition.';
COMMENT ON COLUMN case_core_chain_knowledge_ref.definition_description IS
    'Business-readable definition frozen for case explanation and review.';
COMMENT ON COLUMN case_core_chain_knowledge_ref.classification_path IS
    'Frozen higher-level classification, such as AMLTRIX tactic metadata.';
