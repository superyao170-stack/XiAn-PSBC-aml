-- Graph instance governance:
-- case graphs contain instances only; their node/relation types are governed
-- as metadata in the knowledge-management domain.

ALTER TABLE graph_node_type_registry
    ADD COLUMN IF NOT EXISTS graph_domain VARCHAR(32),
    ADD COLUMN IF NOT EXISTS object_semantics VARCHAR(24) NOT NULL DEFAULT 'INSTANCE',
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS required_properties JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS display_property VARCHAR(64) NOT NULL DEFAULT 'name',
    ADD COLUMN IF NOT EXISTS metadata_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);

ALTER TABLE graph_relation_type_registry
    ADD COLUMN IF NOT EXISTS graph_domain VARCHAR(32),
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS metadata_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);

UPDATE graph_node_type_registry
SET graph_domain=CASE
        WHEN node_type IN ('INDICATOR_RESULT','BEHAVIOR_PATTERN',
                           'RISK_HYPOTHESIS','INVESTIGATION_HYPOTHESIS')
            THEN 'REASONING_GRAPH'
        ELSE 'EVENT_GRAPH'
    END,
    object_semantics='INSTANCE',
    required_properties='["instanceId","nodeType","caseId"]'::jsonb,
    display_property=CASE node_type
        WHEN 'CASE' THEN 'caseName'
        WHEN 'EVENT' THEN 'event_name'
        WHEN 'EVIDENCE' THEN 'summary'
        WHEN 'INDICATOR_RESULT' THEN 'indicator_code'
        WHEN 'BEHAVIOR_PATTERN' THEN 'pattern_code'
        WHEN 'RISK_HYPOTHESIS' THEN 'title'
        WHEN 'INVESTIGATION_HYPOTHESIS' THEN 'title'
        ELSE 'name'
    END,
    description=CASE node_type
        WHEN 'CASE' THEN '具体案件实例；案例图谱的连通根节点。'
        WHEN 'CUSTOMER' THEN '当前案件范围内被识别或引用的具体客户实例。'
        WHEN 'ORGANIZATION' THEN '当前案件范围内被识别或引用的具体机构实例。'
        WHEN 'MERCHANT' THEN '当前案件范围内被识别或引用的具体商户实例。'
        WHEN 'ACCOUNT' THEN '当前案件范围内被识别或引用的具体账户实例。'
        WHEN 'WALLET' THEN '当前案件范围内被识别或引用的具体钱包实例。'
        WHEN 'DEVICE' THEN '当前案件范围内被识别或引用的具体设备实例。'
        WHEN 'IP_ADDRESS' THEN '当前案件范围内被识别或引用的具体IP地址实例。'
        WHEN 'ADDRESS' THEN '当前案件范围内被识别或引用的具体地址实例。'
        WHEN 'EVIDENCE' THEN '支撑当前案件事实或推理结论的具体证据实例。'
        WHEN 'EVENT' THEN '在明确时间和参与对象下发生的具体事件实例。'
        WHEN 'INDICATOR_RESULT' THEN '针对具体数据执行一次指标计算产生的结果实例。'
        WHEN 'BEHAVIOR_PATTERN' THEN '由具体事件或指标结果识别出的行为模式实例。'
        WHEN 'RISK_HYPOTHESIS' THEN '针对当前案件形成并有上游依据的风险假设实例。'
        WHEN 'INVESTIGATION_HYPOTHESIS' THEN '针对当前案件提出、尚待核实的调查假设实例。'
        ELSE description
    END,
    updated_by=COALESCE(updated_by,'migration-v55'),
    updated_at=CURRENT_TIMESTAMP
WHERE schema_version='1.8' AND status='ACTIVE';

UPDATE graph_relation_type_registry
SET graph_domain=CASE
        WHEN edge_category='INFERENCE' THEN 'REASONING_GRAPH'
        ELSE 'EVENT_GRAPH'
    END,
    description=COALESCE(description,display_name || '的实例关系元数据。'),
    updated_by=COALESCE(updated_by,'migration-v55'),
    updated_at=CURRENT_TIMESTAMP
WHERE schema_version='1.8' AND status='ACTIVE';

CREATE TABLE IF NOT EXISTS graph_metadata_change_log (
    change_id BIGSERIAL PRIMARY KEY,
    metadata_kind VARCHAR(24) NOT NULL,
    metadata_code VARCHAR(64) NOT NULL,
    metadata_version INTEGER NOT NULL,
    before_value JSONB,
    after_value JSONB NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (metadata_kind IN ('NODE_TYPE','RELATION_TYPE'))
);

CREATE INDEX IF NOT EXISTS idx_graph_metadata_change_log_code
    ON graph_metadata_change_log(metadata_kind,metadata_code,metadata_version DESC);

INSERT INTO sys_menu(parent_id,menu_name,path,component,sort_order,type,permission,visible)
SELECT p.id,'图谱元数据','/knowledge/graph-metadata',
       'views/knowledge/graph-metadata.vue',2,'MENU','knowledge:view',true
FROM sys_menu p
WHERE p.path='/knowledge'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu m WHERE m.path='/knowledge/graph-metadata'
  );

UPDATE sys_menu
SET sort_order=CASE
    WHEN path='/knowledge/events' THEN 3
    WHEN path='/knowledge/amltrix' THEN 4
    WHEN path='/knowledge/indicators' THEN 5
    WHEN path='/knowledge/patterns' THEN 6
    WHEN path='/knowledge/mappings' THEN 7
    WHEN path='/knowledge/coverage' THEN 8
    WHEN path='/knowledge/releases' THEN 9
    WHEN path='/knowledge/models' THEN 10
    ELSE sort_order
END
WHERE path LIKE '/knowledge/%';
