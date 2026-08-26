-- Source format is provenance, not event semantics. Replace the temporary
-- source-oriented frames with a deterministic business event dictionary.

INSERT INTO semantic_frame_definition
    (frame_id,frame_version,bank_code,frame_code,frame_name,scenario_code,
     description,json_schema,status,created_by)
VALUES
    ('FRAME-FUNDS-TRANSFER',1,NULL,'FUNDS_TRANSFER','资金转移事件',NULL,
     '账户或主体之间发生资金转移；文本或结构化来源记录在实例证据中.',
     '{"type":"object","properties":{"subject":{"type":"string"},"counterparty":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-CASH-DEPOSIT',1,NULL,'CASH_DEPOSIT','现金存入事件',NULL,
     '现金被存入账户或金融渠道.',
     '{"type":"object","properties":{"subject":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-CASH-WITHDRAWAL',1,NULL,'CASH_WITHDRAWAL','现金取出事件',NULL,
     '账户或主体从金融渠道提取现金.',
     '{"type":"object","properties":{"subject":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-PRECIOUS-METAL-PURCHASE',1,NULL,'PRECIOUS_METAL_PURCHASE','贵金属购买事件',NULL,
     '使用资金购买黄金等贵金属资产.',
     '{"type":"object","properties":{"subject":{"type":"string"},"asset":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-PRECIOUS-METAL-LIQUIDATION',1,NULL,'PRECIOUS_METAL_LIQUIDATION','贵金属变现事件',NULL,
     '黄金等贵金属资产被出售或兑换为资金.',
     '{"type":"object","properties":{"subject":{"type":"string"},"asset":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-CRYPTO-ASSET-EXCHANGE',1,NULL,'CRYPTO_ASSET_EXCHANGE','加密资产兑换事件',NULL,
     '法币、稳定币或其他加密资产之间发生兑换.',
     '{"type":"object","properties":{"subject":{"type":"string"},"asset":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-CRYPTO-CROSS-CHAIN',1,NULL,'CRYPTO_CROSS_CHAIN_TRANSFER','加密资产跨链转移事件',NULL,
     '加密资产在链、协议或地址之间发生跨链转移.',
     '{"type":"object","properties":{"subject":{"type":"string"},"sourceChain":{"type":"string"},"targetChain":{"type":"string"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-INTERMEDIARY-TRANSFER',1,NULL,'INTERMEDIARY_TRANSFER','中介转让事件',NULL,
     '来源材料陈述由中介或代理主体完成资产转让.',
     '{"type":"object","properties":{"subject":{"type":"string"},"intermediary":{"type":"string"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-CRYPTO-ASSET-INVESTMENT',1,NULL,'CRYPTO_ASSET_INVESTMENT','加密资产投资事件',NULL,
     '来源材料陈述发生加密资产投资或持有行为.',
     '{"type":"object","properties":{"subject":{"type":"string"},"asset":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-LOAN-ARRANGEMENT',1,NULL,'LOAN_ARRANGEMENT','贷款安排事件',NULL,
     '主体签订、发放、接收或偿还贷款安排.',
     '{"type":"object","properties":{"subject":{"type":"string"},"counterparty":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44'),
    ('FRAME-SALES-TRANSACTION',1,NULL,'SALES_TRANSACTION','销售交易事件',NULL,
     '主体以商品或服务销售名义发生交易或资金回流.',
     '{"type":"object","properties":{"subject":{"type":"string"},"counterparty":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,'ACTIVE','migration-v44')
ON CONFLICT (frame_code,frame_version) DO UPDATE
SET frame_name=EXCLUDED.frame_name,
    description=EXCLUDED.description,
    json_schema=EXCLUDED.json_schema,
    status='ACTIVE';

INSERT INTO semantic_slot_definition
    (frame_id,frame_version,slot_code,slot_name,value_type,required,multi_valued,display_order)
SELECT f.frame_id,1,s.slot_code,s.slot_name,s.value_type,false,false,s.display_order
FROM (VALUES
    ('FRAME-FUNDS-TRANSFER'),('FRAME-CASH-DEPOSIT'),('FRAME-CASH-WITHDRAWAL'),
    ('FRAME-PRECIOUS-METAL-PURCHASE'),('FRAME-PRECIOUS-METAL-LIQUIDATION'),
    ('FRAME-CRYPTO-ASSET-EXCHANGE'),('FRAME-CRYPTO-CROSS-CHAIN'),
    ('FRAME-INTERMEDIARY-TRANSFER'),('FRAME-CRYPTO-ASSET-INVESTMENT'),
    ('FRAME-LOAN-ARRANGEMENT'),('FRAME-SALES-TRANSACTION')
) AS f(frame_id)
CROSS JOIN (VALUES
    ('subject','行为主体','ENTITY_REF',1),
    ('counterparty','交易对手','ENTITY_REF',2),
    ('amount','金额','NUMBER',3),
    ('eventTime','发生时间','DATETIME',4),
    ('sourceRef','来源引用','TEXT',5)
) AS s(slot_code,slot_name,value_type,display_order)
ON CONFLICT (frame_id,frame_version,slot_code) DO NOTHING;

-- Unmatched records remain explicitly UNBOUND instead of being forced into a
-- fabricated event type.
UPDATE cf_risk_event
SET event_frame_code=NULL,event_frame_version=NULL,
    definition_binding_status='UNBOUND',
    definition_match_method='SOURCE_FRAME_RETIRED',
    definition_match_confidence=NULL
WHERE event_frame_code IN ('TEXT_ILLEGAL_BEHAVIOR','STRUCTURED_TRANSFER');

UPDATE cf_risk_event
SET event_frame_code=CASE
        WHEN event_type LIKE '01-%' THEN 'FUNDS_TRANSFER'
        WHEN event_type LIKE '03-%' THEN 'CASH_DEPOSIT'
        WHEN event_type LIKE '04-%' THEN 'CASH_WITHDRAWAL'
    END,
    event_frame_version=1,
    definition_binding_status='BOUND',
    definition_match_method='EVENT_TYPE_DICTIONARY',
    definition_match_confidence=1.0000
WHERE event_type LIKE '01-%'
   OR event_type LIKE '03-%'
   OR event_type LIKE '04-%';

UPDATE cf_risk_event
SET event_frame_code=CASE event_name
        WHEN '购买黄金' THEN 'PRECIOUS_METAL_PURCHASE'
        WHEN '贵金属交易变现' THEN 'PRECIOUS_METAL_LIQUIDATION'
        WHEN '加密ATM兑换USDT' THEN 'CRYPTO_ASSET_EXCHANGE'
        WHEN 'DeFi跨链混币' THEN 'CRYPTO_CROSS_CHAIN_TRANSFER'
        WHEN '中介转让' THEN 'INTERMEDIARY_TRANSFER'
        WHEN '加密货币投资' THEN 'CRYPTO_ASSET_INVESTMENT'
        WHEN '贷款方案伪装' THEN 'LOAN_ARRANGEMENT'
        WHEN '虚构销售回流' THEN 'SALES_TRANSACTION'
    END,
    event_frame_version=1,
    definition_binding_status='BOUND',
    definition_match_method='EVENT_NAME_DICTIONARY',
    definition_match_confidence=0.9500
WHERE event_name IN (
    '购买黄金','贵金属交易变现','加密ATM兑换USDT','DeFi跨链混币',
    '中介转让','加密货币投资','贷款方案伪装','虚构销售回流'
);

UPDATE semantic_frame_definition
SET status='RETIRED'
WHERE frame_code IN ('TEXT_ILLEGAL_BEHAVIOR','STRUCTURED_TRANSFER');
