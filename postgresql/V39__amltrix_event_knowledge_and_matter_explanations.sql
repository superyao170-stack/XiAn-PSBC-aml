-- AMLTRIX knowledge governance, explicit event-definition bindings and explainable case matters.

CREATE TABLE IF NOT EXISTS knowledge_asset_relation (
    id BIGSERIAL PRIMARY KEY,
    relation_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32),
    source_type VARCHAR(32) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    source_version VARCHAR(32) NOT NULL,
    relation_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_code VARCHAR(128) NOT NULL,
    target_version VARCHAR(32) NOT NULL,
    applicability VARCHAR(32),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    evidence_ref JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_standard_version VARCHAR(64),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    content_sha256 VARCHAR(64) NOT NULL,
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_type, source_code, source_version, relation_type,
            target_type, target_code, target_version, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_relation_source
    ON knowledge_asset_relation(source_type, source_code, source_version, status);
CREATE INDEX IF NOT EXISTS idx_knowledge_relation_target
    ON knowledge_asset_relation(target_type, target_code, target_version, status);

CREATE TABLE IF NOT EXISTS event_pattern_definition (
    id BIGSERIAL PRIMARY KEY,
    pattern_code VARCHAR(128) NOT NULL UNIQUE,
    pattern_name VARCHAR(256) NOT NULL,
    pattern_type VARCHAR(32) NOT NULL DEFAULT 'DAG',
    scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    owner VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_pattern_version (
    id BIGSERIAL PRIMARY KEY,
    pattern_code VARCHAR(128) NOT NULL REFERENCES event_pattern_definition(pattern_code),
    version VARCHAR(32) NOT NULL,
    contract_instance_id VARCHAR(64),
    pattern_payload JSONB NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(pattern_code, version)
);

CREATE TABLE IF NOT EXISTS technique_occurrence (
    id BIGSERIAL PRIMARY KEY,
    occurrence_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    technique_code VARCHAR(128) NOT NULL,
    technique_version VARCHAR(32) NOT NULL DEFAULT '1.0',
    tactic_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    subject_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    indicator_result_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    raw_score NUMERIC(12,8),
    calibrated_probability NUMERIC(12,8),
    decision VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    status VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
    inference_evidence_ref VARCHAR(64),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    explanation TEXT,
    dedup_key VARCHAR(128) NOT NULL UNIQUE,
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_technique_occurrence_case
    ON technique_occurrence(case_id, technique_code, status);

CREATE TABLE IF NOT EXISTS technique_occurrence_event_rel (
    occurrence_id VARCHAR(64) NOT NULL REFERENCES technique_occurrence(occurrence_id) ON DELETE CASCADE,
    event_id VARCHAR(64) NOT NULL REFERENCES cf_risk_event(event_id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL DEFAULT 'SUPPORT',
    contribution NUMERIC(12,8),
    evidence_ref JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(occurrence_id, event_id, role)
);

CREATE TABLE IF NOT EXISTS pattern_match (
    id BIGSERIAL PRIMARY KEY,
    pattern_match_id VARCHAR(64) NOT NULL UNIQUE,
    pattern_code VARCHAR(128) NOT NULL,
    pattern_version VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    input_snapshot_id VARCHAR(64),
    raw_score NUMERIC(12,8),
    calibrated_probability NUMERIC(12,8),
    decision VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    matched_steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    missing_steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    contradictions JSONB NOT NULL DEFAULT '[]'::jsonb,
    inference_evidence_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id, pattern_code, pattern_version, input_snapshot_id)
);

CREATE TABLE IF NOT EXISTS attack_path_hypothesis (
    id BIGSERIAL PRIMARY KEY,
    path_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    path_payload JSONB NOT NULL,
    score NUMERIC(12,8),
    calibrated_probability NUMERIC(12,8),
    next_step_predictions JSONB NOT NULL DEFAULT '[]'::jsonb,
    inference_evidence_id VARCHAR(64),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    revision INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(case_id, revision, inference_evidence_id)
);

CREATE TABLE IF NOT EXISTS case_matter_explanation (
    id BIGSERIAL PRIMARY KEY,
    matter_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    matter_type VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    business_value TEXT NOT NULL,
    certainty VARCHAR(24) NOT NULL,
    event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    indicator_result_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    technique_occurrence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    relation_type VARCHAR(64),
    explanation_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    input_snapshot_sha256 VARCHAR(64) NOT NULL,
    producer VARCHAR(64) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    dedup_key VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    review_feedback VARCHAR(24),
    feedback_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_case_matter_case
    ON case_matter_explanation(case_id, matter_type, created_at);

CREATE TABLE IF NOT EXISTS case_review_suggestion (
    id BIGSERIAL PRIMARY KEY,
    suggestion_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64),
    topic VARCHAR(256) NOT NULL,
    reason TEXT NOT NULL,
    expected_material TEXT,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    blocking BOOLEAN NOT NULL DEFAULT false CHECK (blocking = false),
    source_inference_id VARCHAR(64),
    resolution TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    UNIQUE(case_id, target_type, target_id, topic)
);

ALTER TABLE cf_risk_event
    ADD COLUMN IF NOT EXISTS event_frame_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS event_frame_version INT,
    ADD COLUMN IF NOT EXISTS definition_binding_status VARCHAR(24) NOT NULL DEFAULT 'UNBOUND',
    ADD COLUMN IF NOT EXISTS definition_match_method VARCHAR(32),
    ADD COLUMN IF NOT EXISTS definition_match_confidence NUMERIC(6,4);

CREATE INDEX IF NOT EXISTS idx_cf_risk_event_frame
    ON cf_risk_event(event_frame_code, event_frame_version, case_id);

-- Minimal published event definitions used by the current two recognition paths.
INSERT INTO semantic_frame_definition
    (frame_id,frame_version,bank_code,frame_code,frame_name,scenario_code,description,json_schema,status,created_by)
VALUES
    ('FRAME-STRUCTURED-TRANSFER',1,NULL,'STRUCTURED_TRANSFER','结构化资金交易事件',NULL,
     '由结构化交易记录形成的原子资金事件',
     '{"type":"object","required":["source","target","amount","timestamp"]}'::jsonb,'ACTIVE','migration-v39'),
    ('FRAME-TEXT-ILLEGAL-BEHAVIOR',1,NULL,'TEXT_ILLEGAL_BEHAVIOR','文本非法行为事件',NULL,
     '由报告、文书或调查文本抽取的行为事件断言',
     '{"type":"object","required":["sourceText"]}'::jsonb,'ACTIVE','migration-v39')
ON CONFLICT (frame_code,frame_version) DO NOTHING;

INSERT INTO semantic_slot_definition
    (frame_id,frame_version,slot_code,slot_name,value_type,required,multi_valued,display_order)
VALUES
    ('FRAME-STRUCTURED-TRANSFER',1,'source','付款账户','ENTITY_REF',true,false,1),
    ('FRAME-STRUCTURED-TRANSFER',1,'target','收款账户','ENTITY_REF',true,false,2),
    ('FRAME-STRUCTURED-TRANSFER',1,'amount','金额','NUMBER',true,false,3),
    ('FRAME-STRUCTURED-TRANSFER',1,'timestamp','交易时间','DATETIME',true,false,4),
    ('FRAME-TEXT-ILLEGAL-BEHAVIOR',1,'sourceText','来源文本','TEXT',true,false,1),
    ('FRAME-TEXT-ILLEGAL-BEHAVIOR',1,'participants','参与主体','ENTITY_REF',false,true,2)
ON CONFLICT (frame_id,frame_version,slot_code) DO NOTHING;

-- A case event is a runtime assertion of one immutable event-frame version.
-- Unbound events may remain null, but a BOUND event cannot point to a missing definition.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname='fk_cf_risk_event_frame_definition'
    ) THEN
        ALTER TABLE cf_risk_event
            ADD CONSTRAINT fk_cf_risk_event_frame_definition
            FOREIGN KEY (event_frame_code,event_frame_version)
            REFERENCES semantic_frame_definition(frame_code,frame_version);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname='chk_cf_risk_event_frame_pair'
    ) THEN
        ALTER TABLE cf_risk_event
            ADD CONSTRAINT chk_cf_risk_event_frame_pair
            CHECK ((event_frame_code IS NULL) = (event_frame_version IS NULL));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname='chk_cf_risk_event_bound_definition'
    ) THEN
        ALTER TABLE cf_risk_event
            ADD CONSTRAINT chk_cf_risk_event_bound_definition
            CHECK (
                definition_binding_status <> 'BOUND'
                OR (event_frame_code IS NOT NULL AND event_frame_version IS NOT NULL)
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname='chk_cf_risk_event_definition_confidence'
    ) THEN
        ALTER TABLE cf_risk_event
            ADD CONSTRAINT chk_cf_risk_event_definition_confidence
            CHECK (
                definition_match_confidence IS NULL
                OR definition_match_confidence BETWEEN 0 AND 1
            );
    END IF;
END $$;

-- AMLTRIX tactics.
INSERT INTO ontology_term
    (term_id,term_version,bank_code,namespace,term_code,term_name,term_type,definition,status,created_by)
VALUES
    ('AMLTRIX-TACTIC-ILLEGAL-ACQUISITION',1,NULL,'AMLTRIX_TACTIC','ILLEGAL_ACQUISITION','非法获取','TACTIC','非法资金或资产获取阶段','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-CONCEALMENT',1,NULL,'AMLTRIX_TACTIC','CONCEALMENT','隐蔽机制','TACTIC','身份、所有权或交易关系隐蔽','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-ACCESS',1,NULL,'AMLTRIX_TACTIC','ACCESS_FACILITATION','准入便利','TACTIC','账户、产品或渠道准入便利','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-PLACEMENT',1,NULL,'AMLTRIX_TACTIC','PLACEMENT','放置','TACTIC','非法资金进入金融或资产体系','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-LAYERING',1,NULL,'AMLTRIX_TACTIC','LAYERING','离析','TACTIC','通过多层交易和资产转换削弱可追踪性','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-INTEGRATION',1,NULL,'AMLTRIX_TACTIC','INTEGRATION','整合','TACTIC','以表面合法形式回流或持有','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-PROTECTION',1,NULL,'AMLTRIX_TACTIC','ASSET_PROTECTION','资产保护','TACTIC','保护或保存最终资产价值','ACTIVE','migration-v39'),
    ('AMLTRIX-TACTIC-EVASION',1,NULL,'AMLTRIX_TACTIC','OPERATION_EVASION','操作规避','TACTIC','贯穿多个阶段的控制规避','ACTIVE','migration-v39')
ON CONFLICT (namespace,term_code,term_version) DO NOTHING;

-- Techniques explicitly used by the current reference cases. The full catalog is imported through the knowledge API.
INSERT INTO ontology_term
    (term_id,term_version,bank_code,namespace,term_code,term_name,term_type,parent_term_id,definition,constraints,status,created_by)
VALUES
    ('AMLTRIX-TECH-T0002',1,NULL,'AMLTRIX_TECHNIQUE','T0002','中介促进转让','TECHNIQUE',NULL,'通过中介增加资金转让层级','{"applicationStatus":"EXECUTABLE"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0015.001',1,NULL,'AMLTRIX_TECHNIQUE','T0015.001','VPN','SUB_TECHNIQUE','AMLTRIX-TECH-T0015','使用VPN隐藏操作来源','{"applicationStatus":"PATTERN_BOUND"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0016.001',1,NULL,'AMLTRIX_TECHNIQUE','T0016.001','微结构化','SUB_TECHNIQUE','AMLTRIX-TECH-T0016','低于阈值拆分交易','{"applicationStatus":"ACTIVE"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0023.001',1,NULL,'AMLTRIX_TECHNIQUE','T0023.001','伪造KYC','SUB_TECHNIQUE','AMLTRIX-TECH-T0023','伪造客户尽调材料','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0031',1,NULL,'AMLTRIX_TECHNIQUE','T0031','虚构销售','TECHNIQUE',NULL,'以虚构销售或服务回流资金','{"applicationStatus":"PATTERN_BOUND"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0035',1,NULL,'AMLTRIX_TECHNIQUE','T0035','测试支付探测','TECHNIQUE',NULL,'在较大操作前通过小额交易探测控制','{"applicationStatus":"EXECUTABLE"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0038',1,NULL,'AMLTRIX_TECHNIQUE','T0038','代理安排','TECHNIQUE',NULL,'安排代理人或名义主体执行操作','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0055',1,NULL,'AMLTRIX_TECHNIQUE','T0055','贵金属与宝石交易','TECHNIQUE',NULL,'使用贵金属或宝石转换和持有价值','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0055.001',1,NULL,'AMLTRIX_TECHNIQUE','T0055.001','黄金转换','SUB_TECHNIQUE','AMLTRIX-TECH-T0055','将资金转换为黄金','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0063',1,NULL,'AMLTRIX_TECHNIQUE','T0063','加密货币ATM','TECHNIQUE',NULL,'通过加密货币ATM转换资产','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0067.004',1,NULL,'AMLTRIX_TECHNIQUE','T0067.004','DeFi交易','SUB_TECHNIQUE','AMLTRIX-TECH-T0067','通过DeFi或跨链交易离析资金','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0098',1,NULL,'AMLTRIX_TECHNIQUE','T0098','贷款方案','TECHNIQUE',NULL,'以贷款安排形成表面合法资金来源','{"applicationStatus":"QUARANTINED"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0119',1,NULL,'AMLTRIX_TECHNIQUE','T0119','国内大宗现金配送','TECHNIQUE',NULL,'通过国内大宗现金配送转移资金','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0128',1,NULL,'AMLTRIX_TECHNIQUE','T0128','加密货币投资','TECHNIQUE',NULL,'以加密货币投资或持有资产','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39'),
    ('AMLTRIX-TECH-T0149',1,NULL,'AMLTRIX_TECHNIQUE','T0149','知识分隔','TECHNIQUE',NULL,'通过知识和角色分区降低整体暴露','{"applicationStatus":"EXTERNAL_ONLY"}'::jsonb,'ACTIVE','migration-v39')
ON CONFLICT (namespace,term_code,term_version) DO NOTHING;

-- Bind existing runtime events to definitions without pretending arbitrary standard codes are frame codes.
UPDATE cf_risk_event
SET event_frame_code='STRUCTURED_TRANSFER',event_frame_version=1,
    definition_binding_status='BOUND',definition_match_method='MIGRATION_SOURCE_TYPE',
    definition_match_confidence=1.0000
WHERE event_frame_code IS NULL
  AND (rule_name LIKE 'IBM_AML%' OR event_type LIKE '01-%');

UPDATE cf_risk_event
SET event_frame_code='TEXT_ILLEGAL_BEHAVIOR',event_frame_version=1,
    definition_binding_status='BOUND',definition_match_method='MIGRATION_SOURCE_TYPE',
    definition_match_confidence=0.8500
WHERE event_frame_code IS NULL
  AND case_id IN (SELECT case_id FROM cf_risk_case WHERE case_source='TEXT_CASE');

-- Dynamic navigation. Components are backed by routes added in the frontend.
INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT 0,'事理知识管理','/knowledge','Layout','el-icon-collection',25,'MENU','knowledge:view',true
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/knowledge');

INSERT INTO sys_menu(parent_id,menu_name,path,component,sort_order,type,permission,visible)
SELECT p.id,v.menu_name,v.path,v.component,v.sort_order,'MENU',v.permission,true
FROM sys_menu p
CROSS JOIN (VALUES
    ('知识总览','/knowledge/overview','views/knowledge/overview.vue',1,'knowledge:view'),
    ('事件类型','/knowledge/events','views/knowledge/events.vue',2,'knowledge:view'),
    ('战术与技术','/knowledge/amltrix','views/knowledge/amltrix.vue',3,'knowledge:view'),
    ('指标管理','/knowledge/indicators','views/knowledge/indicators.vue',4,'knowledge:view'),
    ('事理模式','/knowledge/patterns','views/knowledge/patterns.vue',5,'knowledge:view'),
    ('关系与映射','/knowledge/mappings','views/knowledge/mappings.vue',6,'knowledge:view'),
    ('应用覆盖','/knowledge/coverage','views/knowledge/coverage.vue',7,'knowledge:view'),
    ('版本发布','/knowledge/releases','views/knowledge/releases.vue',8,'knowledge:publish')
) AS v(menu_name,path,component,sort_order,permission)
WHERE p.path='/knowledge'
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path=v.path);

UPDATE sys_role
SET menus = CASE WHEN menus IS NULL THEN ARRAY['knowledge']
                 WHEN NOT ('knowledge'=ANY(menus)) THEN array_append(menus,'knowledge')
                 ELSE menus END,
    permissions = CASE WHEN role_code='sadmin' THEN permissions
                       WHEN permissions IS NULL THEN ARRAY['knowledge:view']
                       WHEN NOT ('knowledge:view'=ANY(permissions)) THEN array_append(permissions,'knowledge:view')
                       ELSE permissions END
WHERE role_code IN ('sadmin','badmin','madmin');
