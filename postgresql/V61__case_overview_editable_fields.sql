ALTER TABLE cf_risk_case
    ADD COLUMN IF NOT EXISTS business_domain VARCHAR(64) DEFAULT '01-反洗钱',
    ADD COLUMN IF NOT EXISTS business_case_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reporting_direction VARCHAR(255),
    ADD COLUMN IF NOT EXISTS trigger_point VARCHAR(255),
    ADD COLUMN IF NOT EXISTS urgency_level VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reported_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS business_case_status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS business_risk_level VARCHAR(64),
    ADD COLUMN IF NOT EXISTS suspected_crime_type VARCHAR(255),
    ADD COLUMN IF NOT EXISTS suspicious_transaction_feature_code TEXT,
    ADD COLUMN IF NOT EXISTS disposal_measure TEXT;

UPDATE cf_risk_case
SET business_domain = '01-反洗钱'
WHERE business_domain IS NULL OR btrim(business_domain) = '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_cf_risk_case_display_id'
    ) THEN
        ALTER TABLE cf_risk_case
            ADD CONSTRAINT chk_cf_risk_case_display_id
            CHECK (id BETWEEN 1 AND 1000000);
    END IF;
END $$;

DO $$
DECLARE
    sequence_name TEXT;
BEGIN
    sequence_name := pg_get_serial_sequence('cf_risk_case', 'id');
    IF sequence_name IS NOT NULL THEN
        EXECUTE format('ALTER SEQUENCE %s MAXVALUE 1000000', sequence_name);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS case_framework_option_metadata (
    id BIGSERIAL PRIMARY KEY,
    field_code VARCHAR(64) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    option_code VARCHAR(64) NOT NULL,
    option_label VARCHAR(255) NOT NULL,
    option_value VARCHAR(320) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    source_ref VARCHAR(255) NOT NULL DEFAULT '副本非法行为案例框架-图谱映射逻辑-框架-0415pm.xlsx',
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    CONSTRAINT uk_case_framework_option UNIQUE (field_code, option_code),
    CONSTRAINT chk_case_framework_option_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_case_framework_option_field
    ON case_framework_option_metadata(field_code, status, sort_order);

INSERT INTO case_framework_option_metadata
    (field_code, field_name, option_code, option_label, option_value, sort_order, description)
VALUES
    ('reportingDirection','报送方向','01','中国反洗钱监测分析中心','01-中国反洗钱监测分析中心',10,'案例材料报送至中国反洗钱监测分析中心'),
    ('reportingDirection','报送方向','02','中国反洗钱监测分析中心和人民银行当地分支机构','02-中国反洗钱监测分析中心和人民银行当地分支机构',20,'同步报送监测分析中心和属地人民银行'),
    ('reportingDirection','报送方向','03','中国反洗钱监测分析中心和当地公安机关','03-中国反洗钱监测分析中心和当地公安机关',30,'同步报送监测分析中心和属地公安机关'),
    ('triggerPoint','案例触发点','01','模型筛选','01-模型筛选',10,'由监测或分析模型筛选触发'),
    ('triggerPoint','案例触发点','02','执法部门指令（公安、纪检、安全等部门的境内冻结、协查等）','02-执法部门指令（公安、纪检、安全等部门的境内冻结、协查等）',20,'由执法部门指令触发'),
    ('triggerPoint','案例触发点','03','监管部门指令（如央行、证监会、交易所等部门的警示或协查等）','03-监管部门指令（如央行、证监会、交易所等部门的警示或协查等）',30,'由监管部门指令触发'),
    ('triggerPoint','案例触发点','04','金融机构内部案件（机构内部违规违法行为等）','04-金融机构内部案件（机构内部违规违法行为等）',40,'由金融机构内部案件触发'),
    ('triggerPoint','案例触发点','05','社会舆情','05-社会舆情',50,'由公开舆情线索触发'),
    ('triggerPoint','案例触发点','06','金融机构从业人员发现的身份、行为等异常状况','06-金融机构从业人员发现的身份、行为等异常状况',60,'由从业人员人工发现触发'),
    ('triggerPoint','案例触发点','99','其他','99-其他',99,'其他触发来源'),
    ('urgencyLevel','紧急程度','01','非特别紧急','01-非特别紧急',10,'按常规时限处理'),
    ('urgencyLevel','紧急程度','02','特别紧急','02-特别紧急',20,'需按特别紧急流程处理'),
    ('businessCaseStatus','案例状态','INITIAL_SUSPICIOUS','初步可疑','初步可疑',10,'业务案例处于初步可疑阶段'),
    ('businessCaseStatus','案例状态','FOCUS_REVIEW','重点核查','重点核查',20,'业务案例进入重点核查'),
    ('businessCaseStatus','案例状态','FILED','已立案','已立案',30,'相关案件已立案'),
    ('businessCaseStatus','案例状态','JUDGED','已判决','已判决',40,'相关案件已有判决'),
    ('businessCaseStatus','案例状态','EXCLUDED','已排除','已排除',50,'可疑情况已排除'),
    ('businessCaseStatus','案例状态','OTHER','其他','其他',99,'其他业务状态'),
    ('businessRiskLevel','风险等级','GENERAL','一般可疑','一般可疑',10,'一般可疑风险等级'),
    ('businessRiskLevel','风险等级','FOCUS','重点可疑','重点可疑',20,'重点可疑风险等级')
ON CONFLICT (field_code, option_code) DO UPDATE SET
    field_name = EXCLUDED.field_name,
    option_label = EXCLUDED.option_label,
    option_value = EXCLUDED.option_value,
    sort_order = EXCLUDED.sort_order,
    source_ref = EXCLUDED.source_ref,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_menu(parent_id,menu_name,path,component,sort_order,type,permission,visible)
SELECT p.id,'案例框架元数据','/knowledge/case-framework-metadata',
       'views/knowledge/case-framework-metadata.vue',3,'MENU','knowledge:view',true
FROM sys_menu p
WHERE p.path='/knowledge'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu m WHERE m.path='/knowledge/case-framework-metadata'
  );
