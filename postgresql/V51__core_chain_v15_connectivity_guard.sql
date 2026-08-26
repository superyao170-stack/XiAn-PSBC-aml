-- Core-chain V1.5: an effective graph may not contain isolated or
-- case-disconnected nodes. Runtime validation writes the audit counters here.

ALTER TABLE case_core_chain_snapshot
    ADD COLUMN IF NOT EXISTS validation_metrics JSONB NOT NULL DEFAULT
    '{"isolatedNodeCount":0,"disconnectedNodeCount":0,"danglingEdgeCount":0,"duplicateEdgeCount":0,"selfLoopCount":0,"isolatePolicy":"REJECT"}'::jsonb;

UPDATE graph_node_type_registry
SET schema_version='1.5', updated_at=CURRENT_TIMESTAMP
WHERE status='ACTIVE';

UPDATE graph_relation_type_registry
SET schema_version='1.5', updated_at=CURRENT_TIMESTAMP
WHERE status='ACTIVE';
