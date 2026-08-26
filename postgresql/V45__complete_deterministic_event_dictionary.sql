-- Complete the business event dictionary for every event family currently
-- present in cf_risk_event. A quarantined fallback preserves unnameable source
-- assertions without pretending that their business semantics are known.

INSERT INTO semantic_frame_definition
    (frame_id,frame_version,bank_code,frame_code,frame_name,scenario_code,
     description,json_schema,status,created_by)
SELECT 'FRAME-' || v.frame_code,1,NULL,v.frame_code,v.frame_name,NULL,v.description,
       '{"type":"object","properties":{"subject":{"type":"string"},"counterparty":{"type":"string"},"amount":{"type":"number"},"eventTime":{"type":"string"},"sourceRef":{"type":"string"}}}'::jsonb,
       v.status,'migration-v45'
FROM (VALUES
    ('FUNDS_RECEIPT','资金收取事件','账户、商户码或主体收到资金。','ACTIVE'),
    ('ACCOUNT_OPENING','账户开立事件','银行账户、银行卡或商户账户被开立。','ACTIVE'),
    ('ACCOUNT_CLOSURE','账户销户事件','银行账户或银行卡被注销。','ACTIVE'),
    ('ACCOUNT_RESTRICTION','账户冻结止付事件','账户、银行卡或支付能力被冻结、止付、限制或解除限制。','ACTIVE'),
    ('CUSTOMER_DUE_DILIGENCE','客户尽职调查事件','金融机构执行客户识别、尽调或账户管控核查。','ACTIVE'),
    ('SUSPICIOUS_ACTIVITY_REPORT','可疑活动报送事件','可疑活动被预警、报警或向有权机构报送。','ACTIVE'),
    ('INVESTIGATION_ACTION','调查核查事件','侦查、调查、传唤、扣押或到案核查动作。','ACTIVE'),
    ('JUDICIAL_PROCEEDING','司法程序事件','拘留、逮捕、起诉、审理、判决、裁定或执行等司法程序。','ACTIVE'),
    ('MANUAL_REVIEW_DECISION','人工复核确认事件','调查人员对存疑交易聚合结果作出的确认或案例化决定。','ACTIVE'),
    ('RESTITUTION_OR_DISGORGEMENT','退赃退赔事件','退缴违法所得、退赃、退赔或赔偿被害人。','ACTIVE'),
    ('ACCOUNT_OR_CARD_PROVISION','账户或银行卡提供事件','向他人提供、出借、出售或交付银行账户、银行卡及认证信息。','ACTIVE'),
    ('TOOL_OR_DEVICE_PROVISION','设备或工具提供事件','提供POS机、通信设备、软件、验证码或其他操作工具。','ACTIVE'),
    ('CASH_HANDOVER','现金交付事件','现金或现金包裹被交付、携带或转手。','ACTIVE'),
    ('INTERMEDIARY_ORGANIZATION','中介组织事件','介绍、邀集或组织他人参与账户、资金或资产操作。','ACTIVE'),
    ('CONTACT_OR_COORDINATION','联系协调事件','围绕资金、账户或操作流程进行联系、商议、咨询或协调。','ACTIVE'),
    ('GOODS_OR_ASSET_TRADE','商品或资产交易事件','购买、出售、收购或转让普通商品及非金融资产。','ACTIVE'),
    ('VICTIM_PAYMENT_OR_LOSS','被害人付款或损失事件','来源材料陈述被害人付款、被骗或发生资金损失。','ACTIVE'),
    ('COMPANY_OR_BUSINESS_REGISTRATION','企业或业务设立事件','注册公司、设立经营主体、租赁经营场所或建设业务平台。','ACTIVE'),
    ('FINANCIAL_SERVICE_PAYMENT','金融服务支付事件','押金、罚金、贷款、充值、信用卡或银行扣款等支付。','ACTIVE'),
    ('TRAVEL_OR_PHYSICAL_MOVEMENT','人员物理移动事件','人员为相关活动发生出行、住宿、接送或跨境移动。','ACTIVE'),
    ('CONCEALMENT_ACTION','掩饰隐瞒行为事件','来源材料明确陈述掩饰或隐瞒犯罪所得的行为。','ACTIVE'),
    ('COOPERATION_OR_MERITORIOUS_ACTION','配合调查或立功事件','配合调查、协助抓捕、获得表扬或被认定有立功表现。','ACTIVE'),
    ('INVESTMENT_OR_FUNDING','投资或资金投入事件','向平台、项目或资产投入资金。','ACTIVE'),
    ('CURRENCY_EXCHANGE','货币兑换事件','法币币种之间发生兑换。','ACTIVE'),
    ('PRIOR_OFFENSE','既往违法犯罪事件','来源材料记录与当前案件相关的既往违法犯罪情况。','ACTIVE'),
    ('UNCLASSIFIED_REPORTED_ACTION','待分类来源行为','来源记录缺少可判定业务动作的名称，仅用于隔离和后续分类，不参与推理。','QUARANTINED')
) AS v(frame_code,frame_name,description,status)
ON CONFLICT (frame_code,frame_version) DO UPDATE
SET frame_name=EXCLUDED.frame_name,
    description=EXCLUDED.description,
    json_schema=EXCLUDED.json_schema,
    status=EXCLUDED.status;

INSERT INTO semantic_slot_definition
    (frame_id,frame_version,slot_code,slot_name,value_type,required,multi_valued,display_order)
SELECT f.frame_id,f.frame_version,s.slot_code,s.slot_name,s.value_type,false,false,s.display_order
FROM semantic_frame_definition f
CROSS JOIN (VALUES
    ('subject','行为主体','ENTITY_REF',1),
    ('counterparty','交易对手','ENTITY_REF',2),
    ('amount','金额','NUMBER',3),
    ('eventTime','发生时间','DATETIME',4),
    ('sourceRef','来源引用','TEXT',5)
) AS s(slot_code,slot_name,value_type,display_order)
WHERE f.created_by='migration-v45'
ON CONFLICT (frame_id,frame_version,slot_code) DO NOTHING;

-- Deterministic families already encoded by the extraction contract.
UPDATE cf_risk_event
SET event_frame_code=CASE
        WHEN event_type LIKE '01-%' THEN 'FUNDS_TRANSFER'
        WHEN event_type LIKE '02-%' THEN 'FUNDS_RECEIPT'
        WHEN event_type LIKE '03-%' THEN 'CASH_DEPOSIT'
        WHEN event_type LIKE '04-%' THEN 'CASH_WITHDRAWAL'
        WHEN event_type LIKE '05-%' THEN 'ACCOUNT_OPENING'
        WHEN event_type LIKE '06-%' THEN 'ACCOUNT_CLOSURE'
        WHEN event_type LIKE '07-%' THEN 'ACCOUNT_RESTRICTION'
        WHEN event_type LIKE '08-%' THEN 'CUSTOMER_DUE_DILIGENCE'
        WHEN event_type LIKE '09-%' THEN 'SUSPICIOUS_ACTIVITY_REPORT'
        WHEN event_type LIKE '10-%' THEN 'INVESTIGATION_ACTION'
        WHEN event_type LIKE '11-%' THEN 'JUDICIAL_PROCEEDING'
        WHEN event_type='SUSPICIOUS_TRANSACTION_CLUSTER' THEN 'MANUAL_REVIEW_DECISION'
    END,
    event_frame_version=1,definition_binding_status='BOUND',
    definition_match_method=CASE WHEN event_type='SUSPICIOUS_TRANSACTION_CLUSTER'
                                 THEN 'LEGACY_MANUAL_CLUSTER_ALIAS'
                                 ELSE 'EVENT_TYPE_DICTIONARY' END,
    definition_match_confidence=CASE WHEN event_type='SUSPICIOUS_TRANSACTION_CLUSTER'
                                     THEN 0.9500 ELSE 1.0000 END
WHERE definition_binding_status='UNBOUND'
  AND (event_type LIKE '01-%' OR event_type LIKE '02-%' OR event_type LIKE '03-%'
    OR event_type LIKE '04-%' OR event_type LIKE '05-%' OR event_type LIKE '06-%'
    OR event_type LIKE '07-%' OR event_type LIKE '08-%' OR event_type LIKE '09-%'
    OR event_type LIKE '10-%' OR event_type LIKE '11-%'
    OR event_type='SUSPICIOUS_TRANSACTION_CLUSTER');

-- Refine the source's "99-other" bucket from the words that are actually
-- present. Ordering protects stronger business semantics from broad keywords.
UPDATE cf_risk_event
SET event_frame_code=CASE
        WHEN event_name ~ '退缴|退赃|退赔|赔偿|退还|上缴违法所得|退出违法所得|补偿被害|谅解'
          THEN 'RESTITUTION_OR_DISGORGEMENT'
        WHEN event_name ~ '刑事拘留|行政拘留|取保候审|投案|自首|处罚|被罚款'
          THEN 'JUDICIAL_PROCEEDING'
        WHEN event_name ~ '出售黄金|销赃黄金|黄金.*转卖|贵金属.*变现'
          THEN 'PRECIOUS_METAL_LIQUIDATION'
        WHEN event_name ~ '黄金|贵金属' THEN 'PRECIOUS_METAL_PURCHASE'
        WHEN event_name ~* 'USDT|泰达币|虚拟币|虚拟货币|加密|跨链|DeFi'
          THEN 'CRYPTO_ASSET_EXCHANGE'
        WHEN event_name ~ '银行卡|银行账户|提供账户|租借账户|借用账户|开卡'
          THEN 'ACCOUNT_OR_CARD_PROVISION'
        WHEN event_name ~* 'POS机|VOIP|刷脸|验证码|作案工具|APP|软件|手机'
          THEN 'TOOL_OR_DEVICE_PROVISION'
        WHEN event_name ~ '现金|包裹' THEN 'CASH_HANDOVER'
        WHEN event_name ~ '转账|转移|跑分|洗钱|犯罪所得|赃款' THEN 'FUNDS_TRANSFER'
        WHEN event_name ~ '获利|好处费|佣金|分赃|非法所得' THEN 'FUNDS_RECEIPT'
        WHEN event_name ~ '介绍|邀集|组织' THEN 'INTERMEDIARY_ORGANIZATION'
        WHEN event_name ~ '联系|商议|提醒|学习|咨询|对接' THEN 'CONTACT_OR_COORDINATION'
        WHEN event_name ~ '购买|出售|收购|销售|股权转让|白酒|钢板|购物卡|消费'
          THEN 'GOODS_OR_ASSET_TRADE'
        WHEN event_name ~ '被骗|诈骗|被害人' THEN 'VICTIM_PAYMENT_OR_LOSS'
        WHEN event_name ~ '注册公司|租赁店面|平台开发' THEN 'COMPANY_OR_BUSINESS_REGISTRATION'
        WHEN event_name ~ '押金|贷款|扣除|充值|信用卡|缴纳罚金|预交罚金|罚金'
          THEN 'FINANCIAL_SERVICE_PAYMENT'
        WHEN event_name ~ '前往|入住|香港|送人' THEN 'TRAVEL_OR_PHYSICAL_MOVEMENT'
        WHEN event_name ~ '掩饰|隐瞒' THEN 'CONCEALMENT_ACTION'
        WHEN event_name ~ '表扬|协助抓捕|立功' THEN 'COOPERATION_OR_MERITORIOUS_ACTION'
        WHEN event_name ~ '投入资金|投资' THEN 'INVESTMENT_OR_FUNDING'
        WHEN event_name ~ '兑换港币|外币兑换' THEN 'CURRENCY_EXCHANGE'
        WHEN event_name ~ '套现' THEN 'CASH_WITHDRAWAL'
        WHEN event_name ~ '提供资金' THEN 'FUNDS_TRANSFER'
        WHEN event_name ~ '解封|解除管控' THEN 'ACCOUNT_RESTRICTION'
        WHEN event_name ~ '危险驾驶' THEN 'PRIOR_OFFENSE'
        ELSE 'UNCLASSIFIED_REPORTED_ACTION'
    END,
    event_frame_version=1,definition_binding_status='BOUND',
    definition_match_method=CASE WHEN event_name='UNNAMED_WORKER_EVENT'
                                 THEN 'PENDING_EVENT_CLASSIFICATION'
                                 ELSE 'EVENT_NAME_DICTIONARY' END,
    definition_match_confidence=CASE WHEN event_name='UNNAMED_WORKER_EVENT'
                                     THEN 0.2500 ELSE 0.9500 END
WHERE definition_binding_status='UNBOUND' AND event_type LIKE '99-%';

-- Any old worker assertion created between V44 and this migration is routed
-- through the same safe fallback instead of remaining attached to a retired
-- source frame.
UPDATE cf_risk_event
SET event_frame_code='UNCLASSIFIED_REPORTED_ACTION',event_frame_version=1,
    definition_binding_status='BOUND',
    definition_match_method='PENDING_EVENT_CLASSIFICATION',
    definition_match_confidence=0.2500
WHERE event_frame_code IN ('TEXT_ILLEGAL_BEHAVIOR','STRUCTURED_TRANSFER');

