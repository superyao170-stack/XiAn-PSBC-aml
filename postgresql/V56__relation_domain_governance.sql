-- V1.8 relation-governance revision 2.
-- The registry is the executable contract for case-graph edges.  Relation
-- display names, domains, endpoint types and evidence requirements must not
-- be redefined independently by graph builders or frontends.

ALTER TABLE graph_relation_type_registry
    ADD COLUMN IF NOT EXISTS semantic_level VARCHAR(32),
    ADD COLUMN IF NOT EXISTS direction_semantics VARCHAR(160),
    ADD COLUMN IF NOT EXISTS required_edge_properties JSONB NOT NULL DEFAULT '[]'::jsonb;

INSERT INTO graph_relation_type_registry
    (relation_type,display_name,edge_category,allowed_source_types,allowed_target_types,
     cardinality,participates_in_reasoning,requires_evidence,requires_definition_version,
     schema_version,status,graph_domain,description,metadata_version,updated_by,
     semantic_level,direction_semantics,required_edge_properties)
VALUES
    ('INVOLVES_SUBJECT','涉及主体','FACT','["CASE"]',
     '["CUSTOMER","ORGANIZATION","MERCHANT"]','1:N',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','案件调查范围内涉及的主体实例。',2,'migration-v56',
     'CASE_SCOPE','案件 → 主体','["edgeOrigin","sourceRef"]'),
    ('INVOLVES_ASSET','涉及资产','FACT','["CASE"]',
     '["ACCOUNT","WALLET"]','1:N',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','案件调查范围内涉及的账户或钱包实例。',2,'migration-v56',
     'CASE_SCOPE','案件 → 资产','["edgeOrigin","sourceRef"]'),
    ('INVOLVES_ENVIRONMENT','涉及环境','FACT','["CASE"]',
     '["DEVICE","IP_ADDRESS","ADDRESS"]','1:N',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','案件调查范围内涉及的设备、IP或地址实例。',2,'migration-v56',
     'CASE_SCOPE','案件 → 环境','["edgeOrigin","sourceRef"]'),
    ('HAS_EVIDENCE','拥有证据','FACT','["CASE"]','["EVIDENCE"]','1:N',false,false,false,
     '1.8','ACTIVE','EVENT_GRAPH','案件材料范围包含的证据实例。',2,'migration-v56',
     'CASE_SCOPE','案件 → 证据','["edgeOrigin"]'),
    ('INVESTIGATION_SCOPE','纳入调查范围','FACT','["CASE"]','["EVENT"]','1:N',true,false,false,
     '1.8','ACTIVE','EVENT_GRAPH','当前核心链修订实际选择分析的事件。',2,'migration-v56',
     'CASE_SCOPE','案件 → 当前调查事件','["edgeOrigin"]'),
    ('OWNS_ACCOUNT','持有账户','FACT','["CUSTOMER","ORGANIZATION"]','["ACCOUNT"]','N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','有材料或主数据依据的账户持有关系。',2,'migration-v56',
     'ENTITY_FACT','主体 → 账户','["edgeOrigin","sourceRef"]'),
    ('CONTROLS','控制','FACT','["CUSTOMER","ORGANIZATION"]',
     '["ACCOUNT","WALLET","DEVICE","ORGANIZATION"]','N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','有证据依据的实际控制关系。',2,'migration-v56',
     'ENTITY_FACT','控制方 → 被控制对象','["edgeOrigin","sourceRef"]'),
    ('OPERATES','经营','FACT','["ORGANIZATION"]','["MERCHANT"]','N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','机构运营商户的事实关系。',2,'migration-v56',
     'ENTITY_FACT','机构 → 商户','["edgeOrigin","sourceRef"]'),
    ('HAS_ADDRESS','拥有地址','FACT','["CUSTOMER","ORGANIZATION","MERCHANT","WALLET"]',
     '["ADDRESS"]','N:M',false,true,false,'1.8','ACTIVE','EVENT_GRAPH',
     '主体、商户或钱包的有来源地址关系。',2,'migration-v56',
     'ENTITY_FACT','对象 → 地址','["edgeOrigin","sourceRef"]'),
    ('ACTOR','行为主体','FACT','["EVENT"]','["CUSTOMER","ORGANIZATION","MERCHANT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','发起、执行或主导事件的主体。',2,'migration-v56',
     'EVENT_ROLE','事件 → 行为主体','["edgeOrigin","role","sourceRef"]'),
    ('SUBJECT','事件对象','FACT','["EVENT"]','["CUSTOMER","ORGANIZATION","MERCHANT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','事件作用或调查指向的主体。',2,'migration-v56',
     'EVENT_ROLE','事件 → 事件对象','["edgeOrigin","role","sourceRef"]'),
    ('BENEFICIARY','受益主体','FACT','["EVENT"]','["CUSTOMER","ORGANIZATION","MERCHANT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','从事件结果中受益的主体。',2,'migration-v56',
     'EVENT_ROLE','事件 → 受益主体','["edgeOrigin","role","sourceRef"]'),
    ('SUBJECT_ACCOUNT','事件涉及账户','FACT','["EVENT"]','["ACCOUNT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','无法判定资金方向时，账户作为事件对象参与。',2,'migration-v56',
     'EVENT_ROLE','事件 → 涉及账户','["edgeOrigin","role","sourceRef"]'),
    ('SOURCE_ACCOUNT','来源账户','FACT','["EVENT"]','["ACCOUNT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','资金事件的来源账户。',2,'migration-v56',
     'EVENT_ROLE','事件 → 来源账户','["edgeOrigin","role","sourceRef"]'),
    ('TARGET_ACCOUNT','目标账户','FACT','["EVENT"]','["ACCOUNT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','资金事件的目标账户。',2,'migration-v56',
     'EVENT_ROLE','事件 → 目标账户','["edgeOrigin","role","sourceRef"]'),
    ('SOURCE_WALLET','来源钱包','FACT','["EVENT"]','["WALLET"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','虚拟资产事件的来源钱包。',2,'migration-v56',
     'EVENT_ROLE','事件 → 来源钱包','["edgeOrigin","role","sourceRef"]'),
    ('TARGET_WALLET','目标钱包','FACT','["EVENT"]','["WALLET"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','虚拟资产事件的目标钱包。',2,'migration-v56',
     'EVENT_ROLE','事件 → 目标钱包','["edgeOrigin","role","sourceRef"]'),
    ('USES_DEVICE','使用设备','FACT','["EVENT"]','["DEVICE"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','事件发生时使用的设备。',2,'migration-v56',
     'EVENT_ROLE','事件 → 设备','["edgeOrigin","sourceRef"]'),
    ('FROM_IP','来源IP','FACT','["EVENT"]','["IP_ADDRESS"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','事件的网络来源IP。',2,'migration-v56',
     'EVENT_ROLE','事件 → IP地址','["edgeOrigin","sourceRef"]'),
    ('AT_MERCHANT','发生于商户','FACT','["EVENT"]','["MERCHANT"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','支付或商业事件发生的商户。',2,'migration-v56',
     'EVENT_ROLE','事件 → 商户','["edgeOrigin","sourceRef"]'),
    ('INVOLVES_ADDRESS','涉及地址','FACT','["EVENT"]','["ADDRESS"]','N:M',true,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','事件涉及的可定位地址。',2,'migration-v56',
     'EVENT_ROLE','事件 → 地址','["edgeOrigin","sourceRef"]'),
    ('ASSOCIATED_WITH','事实关联','FACT',
     '["CASE","CUSTOMER","EVENT","EVIDENCE"]','["CASE","CUSTOMER","EVENT","EVIDENCE"]',
     'N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','材料明确陈述的客户关联或事件共同线索关系，不因同案自动生成。',2,'migration-v56',
     'ENTITY_OR_EVENT_FACT','对象/事件 → 关联对象/事件','["edgeOrigin","sourceRef"]'),
    ('FOLLOWS','晚于','FACT','["EVENT"]','["EVENT"]','N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','有时间依据的后序关系；通常仅保留 PRECEDES 方向之一。',2,'migration-v56',
     'EVENT_SEQUENCE','较晚事件 → 较早事件','["edgeOrigin","sourceRef"]'),
    ('OVERLAPS','时间重叠','FACT','["EVENT"]','["EVENT"]','N:M',false,true,false,
     '1.8','ACTIVE','EVENT_GRAPH','两个事件的有效时间窗口发生重叠。',2,'migration-v56',
     'EVENT_SEQUENCE','事件 ↔ 时间重叠事件','["edgeOrigin","sourceRef"]'),
    ('EVIDENCE_SUPPORTS','证据支持','INFERENCE','["EVIDENCE"]',
     '["CASE","CUSTOMER","ORGANIZATION","MERCHANT","ACCOUNT","WALLET","DEVICE","EVIDENCE",
       "IP_ADDRESS","ADDRESS","EVENT","BEHAVIOR_PATTERN","RISK_HYPOTHESIS"]',
     'N:M',true,false,false,'1.8','ACTIVE','REASONING_GRAPH',
     '证据支撑事实实例、行为模式或风险假设。',2,'migration-v56',
     'EVIDENCE_SUPPORT','证据 → 被支持对象','["edgeOrigin","evidenceRefs"]'),
    ('EVIDENCE_CONTRADICTS','证据反驳','INFERENCE','["EVIDENCE"]',
     '["EVIDENCE","BEHAVIOR_PATTERN","RISK_HYPOTHESIS"]','N:M',true,false,false,
     '1.8','ACTIVE','REASONING_GRAPH','证据与模式或风险假设冲突。',2,'migration-v56',
     'EVIDENCE_SUPPORT','证据 → 被反驳判断','["edgeOrigin","evidenceRefs"]'),
    ('DERIVED_FROM_SOURCE','派生自来源','FACT','["EVIDENCE"]','["EVIDENCE"]','N:M',false,false,false,
     '1.8','ACTIVE','EVENT_GRAPH','派生证据回溯到原始证据。',2,'migration-v56',
     'EVIDENCE_LINEAGE','派生证据 → 原始证据','["edgeOrigin","sourceRef"]'),
    ('INPUT_TO_INDICATOR_RESULT','输入指标计算','INFERENCE','["EVENT"]',
     '["INDICATOR_RESULT"]','N:M',true,true,false,'1.8','ACTIVE','REASONING_GRAPH',
     '事件作为指标计算输入。',2,'migration-v56',
     'REASONING_SUPPORT','事件 → 指标结果','["edgeOrigin","inputSnapshotSha256"]'),
    ('TARGETS','调查指向','INFERENCE','["INVESTIGATION_HYPOTHESIS"]',
     '["EVENT","CUSTOMER","ORGANIZATION","MERCHANT","ACCOUNT","WALLET","DEVICE",
       "IP_ADDRESS","ADDRESS"]','N:M',true,true,false,'1.8','ACTIVE','REASONING_GRAPH',
     '调查假设明确指向待核查的事实对象。',2,'migration-v56',
     'REASONING_SPINE','调查假设 → 调查对象','["edgeOrigin"]')
ON CONFLICT (relation_type) DO UPDATE SET
    display_name=EXCLUDED.display_name,
    edge_category=EXCLUDED.edge_category,
    allowed_source_types=EXCLUDED.allowed_source_types,
    allowed_target_types=EXCLUDED.allowed_target_types,
    cardinality=EXCLUDED.cardinality,
    participates_in_reasoning=EXCLUDED.participates_in_reasoning,
    requires_evidence=EXCLUDED.requires_evidence,
    requires_definition_version=EXCLUDED.requires_definition_version,
    graph_domain=EXCLUDED.graph_domain,
    description=EXCLUDED.description,
    semantic_level=EXCLUDED.semantic_level,
    direction_semantics=EXCLUDED.direction_semantics,
    required_edge_properties=EXCLUDED.required_edge_properties,
    metadata_version=EXCLUDED.metadata_version,
    updated_by=EXCLUDED.updated_by,
    schema_version='1.8',
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

UPDATE graph_relation_type_registry
SET semantic_level=CASE relation_type
        WHEN 'CONTAINS_EVENT' THEN 'CASE_SCOPE'
        WHEN 'PRECEDES' THEN 'EVENT_SEQUENCE'
        WHEN 'FUNDS_FLOW_TO' THEN 'EVENT_FLOW'
        WHEN 'SAME_SESSION_NEXT' THEN 'EVENT_SEQUENCE'
        WHEN 'MATCHES_BEHAVIOR_PATTERN' THEN 'REASONING_SPINE'
        WHEN 'SUPPORTS_BEHAVIOR_PATTERN' THEN 'REASONING_SUPPORT'
        WHEN 'SUPPORTS_RISK_HYPOTHESIS' THEN 'REASONING_SPINE'
        WHEN 'RAISES_INVESTIGATION_HYPOTHESIS' THEN 'REASONING_SPINE'
        ELSE semantic_level
    END,
    direction_semantics=CASE relation_type
        WHEN 'CONTAINS_EVENT' THEN '案件 → 全部关联事件'
        WHEN 'PRECEDES' THEN '较早事件 → 较晚事件'
        WHEN 'FUNDS_FLOW_TO' THEN '上游资金事件 → 下游资金事件'
        WHEN 'SAME_SESSION_NEXT' THEN '会话前序事件 → 会话后序事件'
        WHEN 'MATCHES_BEHAVIOR_PATTERN' THEN '事件 → 行为模式'
        WHEN 'SUPPORTS_BEHAVIOR_PATTERN' THEN '指标结果 → 行为模式'
        WHEN 'SUPPORTS_RISK_HYPOTHESIS' THEN '行为模式/指标结果 → 风险假设'
        WHEN 'RAISES_INVESTIGATION_HYPOTHESIS' THEN '风险假设 → 调查假设'
        ELSE direction_semantics
    END,
    metadata_version=GREATEST(metadata_version,2),
    updated_by='migration-v56',
    updated_at=CURRENT_TIMESTAMP
WHERE schema_version='1.8' AND status='ACTIVE';

UPDATE graph_relation_type_registry
SET status='RETIRED',updated_by='migration-v56',updated_at=CURRENT_TIMESTAMP
WHERE relation_type IN (
    'CONTAINS_EVIDENCE','INVOLVES_CUSTOMER','INVOLVES_ACCOUNT',
    'CONTROLS_ACCOUNT','RELATED_CUSTOMER','RELATED_EVIDENCE',
    'RELATED_CASE','PROVES_EVENT','SUPPORTS_EVENT'
);
