-- AMLTRIX hierarchy and five-dimensional indicator governance.
-- A single indicator keeps one primary dimension and may carry several secondary dimensions.

CREATE TABLE IF NOT EXISTS indicator_dimension_definition (
    dimension_code VARCHAR(32) PRIMARY KEY,
    dimension_name VARCHAR(64) NOT NULL,
    description VARCHAR(512) NOT NULL,
    display_order INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO indicator_dimension_definition
    (dimension_code,dimension_name,description,display_order,status,created_by)
VALUES
    ('BEHAVIOR','行为维度','关注行为组合、操作方式、交易结构和规避动作。',1,'ACTIVE','migration-v49'),
    ('SUBJECT','主体维度','关注客户、企业、员工、实益所有人及其身份和关联特征。',2,'ACTIVE','migration-v49'),
    ('FUND','资金维度','关注金额、现金、资金来源去向、余额、资产与价值变化。',3,'ACTIVE','migration-v49'),
    ('TIME','时间维度','关注频率、持续时间、突发变化、时间窗口和时序特征。',4,'ACTIVE','migration-v49'),
    ('CONTEXT','情境维度','关注地域、行业、渠道、监管、尽调及经济合理性情境。',5,'ACTIVE','migration-v49')
ON CONFLICT (dimension_code) DO UPDATE SET
    dimension_name=EXCLUDED.dimension_name,
    description=EXCLUDED.description,
    display_order=EXCLUDED.display_order,
    status=EXCLUDED.status,
    updated_at=CURRENT_TIMESTAMP;

ALTER TABLE indicator_definition
    ADD COLUMN IF NOT EXISTS secondary_dimension_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS dimension_assignment_source VARCHAR(32) NOT NULL DEFAULT 'SOURCE',
    ADD COLUMN IF NOT EXISTS dimension_rule_version VARCHAR(32);

-- The AMLTRIX workbook provides detection descriptions but did not provide the platform's
-- five-dimensional classification. Classify deterministically and retain provenance.
WITH classified AS (
    SELECT indicator_code,
           CASE
             WHEN concat_ws(' ',indicator_name,description) ~
                  '(短时间|时间内|频繁|突然|快速|连续|周期|夜间|非工作时间|长期|时序|间隔|突发|持续|立即|随后)'
               THEN 'TIME'
             WHEN concat_ws(' ',indicator_name,description) ~
                  '(司法管辖区|跨境|国家|地区|地域|行业|职业|业务类型|经济理由|商业理由|制裁|监管|KYC|尽调|文件|发票|渠道|设备|IP地址|高风险地区)'
               THEN 'CONTEXT'
             WHEN concat_ws(' ',indicator_name,description) ~
                  '(客户|主体|人员|个人|公司|企业|法人|实益所有人|员工|高管|股东|账户持有人|代理人|受益人|关联方)'
               THEN 'SUBJECT'
             WHEN concat_ws(' ',indicator_name,description) ~
                  '(资金|金额|现金|存款|取款|汇款|转账|支付|交易量|余额|资产|收入|支出|价值|货币|币种|贷款|退款)'
               THEN 'FUND'
             ELSE 'BEHAVIOR'
           END AS primary_dimension,
           (
             '[]'::jsonb
             || CASE WHEN concat_ws(' ',indicator_name,description) ~
                  '(短时间|时间内|频繁|突然|快速|连续|周期|夜间|非工作时间|长期|时序|间隔|突发|持续|立即|随后)'
                THEN '["TIME"]'::jsonb ELSE '[]'::jsonb END
             || CASE WHEN concat_ws(' ',indicator_name,description) ~
                  '(司法管辖区|跨境|国家|地区|地域|行业|职业|业务类型|经济理由|商业理由|制裁|监管|KYC|尽调|文件|发票|渠道|设备|IP地址|高风险地区)'
                THEN '["CONTEXT"]'::jsonb ELSE '[]'::jsonb END
             || CASE WHEN concat_ws(' ',indicator_name,description) ~
                  '(客户|主体|人员|个人|公司|企业|法人|实益所有人|员工|高管|股东|账户持有人|代理人|受益人|关联方)'
                THEN '["SUBJECT"]'::jsonb ELSE '[]'::jsonb END
             || CASE WHEN concat_ws(' ',indicator_name,description) ~
                  '(资金|金额|现金|存款|取款|汇款|转账|支付|交易量|余额|资产|收入|支出|价值|货币|币种|贷款|退款)'
                THEN '["FUND"]'::jsonb ELSE '[]'::jsonb END
             || '["BEHAVIOR"]'::jsonb
           ) AS matched_dimensions
    FROM indicator_definition
    WHERE dimension_assignment_source='SOURCE'
      AND dimension_code='BEHAVIOR'
)
UPDATE indicator_definition d
SET dimension_code=c.primary_dimension,
    secondary_dimension_codes=(
      SELECT COALESCE(jsonb_agg(DISTINCT value),'[]'::jsonb)
      FROM jsonb_array_elements(c.matched_dimensions) item(value)
      WHERE value <> to_jsonb(c.primary_dimension)
    ),
    dimension_assignment_source='DERIVED_RULE',
    dimension_rule_version='AML-FIVE-DIM-1.0',
    updated_at=CURRENT_TIMESTAMP
FROM classified c
WHERE d.indicator_code=c.indicator_code;

ALTER TABLE indicator_definition
    DROP CONSTRAINT IF EXISTS chk_indicator_primary_dimension;

ALTER TABLE indicator_definition
    ADD CONSTRAINT chk_indicator_primary_dimension
    CHECK (dimension_code IN ('BEHAVIOR','SUBJECT','FUND','TIME','CONTEXT'));

CREATE INDEX IF NOT EXISTS idx_indicator_definition_dimension
    ON indicator_definition(dimension_code,status);
