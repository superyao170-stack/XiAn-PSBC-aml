-- Text-derived indicators preserve what the submitted material reports. They
-- are not equivalent to transaction-calculated observations.
INSERT INTO indicator_definition
  (indicator_code,indicator_name,dimension_code,description,value_type,status,
   created_by,observability_type,source_ref)
VALUES
  ('TXT_HIGH_FREQUENCY_ACTIVITY','材料记载的高频密集交易','BEHAVIOR',
   '案例材料出现高频、密集、日均笔数或明确交易笔数等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_RAPID_PASS_THROUGH','材料记载的资金快进快出','BEHAVIOR',
   '案例材料出现快进快出、不留余额、过渡性质或收款后快速转出等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_ROUND_AMOUNT_PATTERN','材料记载的整数倍金额交易','FUND',
   '案例材料出现整数倍、小额或重复固定金额等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_NIGHT_ACTIVITY','材料记载的夜间集中交易','TIME',
   '案例材料出现夜间、凌晨或跨午夜交易时间窗口等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_MULTI_ACCOUNT_LAYERING','材料记载的多账户分层转移','BEHAVIOR',
   '案例材料出现多账户、逐级转移、层层归集或资金转移网络等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_CROSS_REGION_ACTIVITY','材料记载的跨区域交易','CONTEXT',
   '案例材料出现交易对手或开户地跨区域、分散至多个省份等表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_PROFILE_TRANSACTION_MISMATCH','材料记载的客户背景与交易不匹配','SUBJECT',
   '案例材料明确指出职业、收入、经营背景与交易规模不匹配。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}'),
  ('TXT_THIRD_PARTY_PAYMENT','材料记载的第三方支付通道交易','CONTEXT',
   '案例材料出现第三方支付、支付宝或微信支付等通道表述。','NUMBER','ACTIVE',
   'migration-v58','TEXT_REPORTED','{"source":"case description","assertionMode":"REPORTED"}')
ON CONFLICT (indicator_code) DO UPDATE SET
  indicator_name=EXCLUDED.indicator_name,
  description=EXCLUDED.description,
  observability_type=EXCLUDED.observability_type,
  source_ref=EXCLUDED.source_ref,
  status='ACTIVE',
  updated_at=CURRENT_TIMESTAMP;

INSERT INTO indicator_version
  (indicator_code,version,expression,input_schema,threshold_config,
   algorithm_id,algorithm_version,status,effective_from,created_by)
SELECT code,1,'{"kind":"FIELD","path":"value"}'::jsonb,
       '{"required":["value","evidenceRefs","assertionMode"]}'::jsonb,
       '{"medium":1,"high":2}'::jsonb,
       'TEXT_INDICATOR_EXTRACTOR','1.3.0','ACTIVE',CURRENT_TIMESTAMP,'migration-v58'
FROM (VALUES
  ('TXT_HIGH_FREQUENCY_ACTIVITY'),
  ('TXT_RAPID_PASS_THROUGH'),
  ('TXT_ROUND_AMOUNT_PATTERN'),
  ('TXT_NIGHT_ACTIVITY'),
  ('TXT_MULTI_ACCOUNT_LAYERING'),
  ('TXT_CROSS_REGION_ACTIVITY'),
  ('TXT_PROFILE_TRANSACTION_MISMATCH'),
  ('TXT_THIRD_PARTY_PAYMENT')
) AS x(code)
ON CONFLICT (indicator_code,version) DO UPDATE SET
  input_schema=EXCLUDED.input_schema,
  threshold_config=EXCLUDED.threshold_config,
  algorithm_id=EXCLUDED.algorithm_id,
  algorithm_version=EXCLUDED.algorithm_version,
  status='ACTIVE';

INSERT INTO event_pattern_definition
  (pattern_code,pattern_name,pattern_type,scope,owner,status)
VALUES
  ('TEXT_HIGH_FREQUENCY_PASS_THROUGH','材料记载的高频快进快出模式','DAG','GLOBAL','bankgraph','ACTIVE'),
  ('TEXT_ROUND_AMOUNT_STRUCTURING','材料记载的整数倍拆分交易模式','DAG','GLOBAL','bankgraph','ACTIVE'),
  ('TEXT_MULTI_ACCOUNT_LAYERING','材料记载的多账户分层转移模式','DAG','GLOBAL','bankgraph','ACTIVE')
ON CONFLICT (pattern_code) DO UPDATE SET
  pattern_name=EXCLUDED.pattern_name,status='ACTIVE';

INSERT INTO event_pattern_version
  (pattern_code,version,pattern_payload,content_sha256,review_status,created_by)
VALUES
  ('TEXT_HIGH_FREQUENCY_PASS_THROUGH','1.0',
   '{"observationMode":"REPORTED","requiredIndicators":["TXT_HIGH_FREQUENCY_ACTIVITY","TXT_RAPID_PASS_THROUGH"],"minimumEvents":1}',
   md5('TEXT_HIGH_FREQUENCY_PASS_THROUGH-v1')||md5('TEXT_HIGH_FREQUENCY_PASS_THROUGH-v1-bankgraph'),
   'APPROVED','migration-v58'),
  ('TEXT_ROUND_AMOUNT_STRUCTURING','1.0',
   '{"observationMode":"REPORTED","requiredIndicators":["TXT_HIGH_FREQUENCY_ACTIVITY","TXT_ROUND_AMOUNT_PATTERN"],"minimumEvents":1}',
   md5('TEXT_ROUND_AMOUNT_STRUCTURING-v1')||md5('TEXT_ROUND_AMOUNT_STRUCTURING-v1-bankgraph'),
   'APPROVED','migration-v58'),
  ('TEXT_MULTI_ACCOUNT_LAYERING','1.0',
   '{"observationMode":"REPORTED","requiredIndicators":["TXT_RAPID_PASS_THROUGH","TXT_MULTI_ACCOUNT_LAYERING"],"minimumEvents":1}',
   md5('TEXT_MULTI_ACCOUNT_LAYERING-v1')||md5('TEXT_MULTI_ACCOUNT_LAYERING-v1-bankgraph'),
   'APPROVED','migration-v58')
ON CONFLICT (pattern_code,version) DO UPDATE SET
  pattern_payload=EXCLUDED.pattern_payload,
  content_sha256=EXCLUDED.content_sha256,
  review_status='APPROVED';

INSERT INTO knowledge_asset_relation
  (relation_id,source_type,source_code,source_version,relation_type,
   target_type,target_code,target_version,applicability,is_primary,evidence_ref,
   source_standard_version,effective_from,status,review_status,content_sha256,created_by)
SELECT relation_id,source_type,source_code,'1',relation_type,
       target_type,target_code,target_version,'TEXT_CASE_CANDIDATE',is_primary,
       evidence_ref::jsonb,'BANKGRAPH-1.3',CURRENT_TIMESTAMP,'ACTIVE','APPROVED',
       md5(relation_id)||md5(relation_id||'bankgraph'), 'migration-v58'
FROM (VALUES
  ('KREL-TXT-HIGHFREQ-PASS','INDICATOR','TXT_HIGH_FREQUENCY_ACTIVITY',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_HIGH_FREQUENCY_PASS_THROUGH','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-RAPID-PASS','INDICATOR','TXT_RAPID_PASS_THROUGH',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_HIGH_FREQUENCY_PASS_THROUGH','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-HIGHFREQ-ROUND','INDICATOR','TXT_HIGH_FREQUENCY_ACTIVITY',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_ROUND_AMOUNT_STRUCTURING','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-ROUND-STRUCT','INDICATOR','TXT_ROUND_AMOUNT_PATTERN',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_ROUND_AMOUNT_STRUCTURING','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-RAPID-LAYER','INDICATOR','TXT_RAPID_PASS_THROUGH',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_MULTI_ACCOUNT_LAYERING','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-ACCOUNTS-LAYER','INDICATOR','TXT_MULTI_ACCOUNT_LAYERING',
   'INDICATOR_USED_BY_PATTERN','BEHAVIOR_PATTERN','TEXT_MULTI_ACCOUNT_LAYERING','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-T0016-TXT-HIGHFREQ','TECHNIQUE','T0016.001',
   'TECHNIQUE_HAS_INDICATOR','INDICATOR','TXT_HIGH_FREQUENCY_ACTIVITY','1',false,
   '{"requiredWith":["TXT_ROUND_AMOUNT_PATTERN"],"decision":"CANDIDATE"}'),
  ('KREL-T0016-TXT-ROUND','TECHNIQUE','T0016.001',
   'TECHNIQUE_HAS_INDICATOR','INDICATOR','TXT_ROUND_AMOUNT_PATTERN','1',true,
   '{"requiredWith":["TXT_HIGH_FREQUENCY_ACTIVITY"],"decision":"CANDIDATE"}'),
  ('KREL-T0002-TXT-RAPID','TECHNIQUE','T0002',
   'TECHNIQUE_HAS_INDICATOR','INDICATOR','TXT_RAPID_PASS_THROUGH','1',false,
   '{"requiredWith":["TXT_MULTI_ACCOUNT_LAYERING"],"decision":"CANDIDATE"}'),
  ('KREL-T0002-TXT-LAYER','TECHNIQUE','T0002',
   'TECHNIQUE_HAS_INDICATOR','INDICATOR','TXT_MULTI_ACCOUNT_LAYERING','1',true,
   '{"requiredWith":["TXT_RAPID_PASS_THROUGH"],"decision":"CANDIDATE"}'),
  ('KREL-TXT-PASS-RISK','BEHAVIOR_PATTERN','TEXT_HIGH_FREQUENCY_PASS_THROUGH',
   'PATTERN_SUPPORTS_RISK_EVENT','RISK_EVENT_TYPE','REPORTED_COMPOSITE_LAUNDERING_SCENARIO','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-ROUND-RISK','BEHAVIOR_PATTERN','TEXT_ROUND_AMOUNT_STRUCTURING',
   'PATTERN_SUPPORTS_RISK_EVENT','RISK_EVENT_TYPE','REPORTED_COMPOSITE_LAUNDERING_SCENARIO','1.0',true,
   '{"assertionMode":"REPORTED"}'),
  ('KREL-TXT-LAYER-RISK','BEHAVIOR_PATTERN','TEXT_MULTI_ACCOUNT_LAYERING',
   'PATTERN_SUPPORTS_RISK_EVENT','RISK_EVENT_TYPE','REPORTED_COMPOSITE_LAUNDERING_SCENARIO','1.0',true,
   '{"assertionMode":"REPORTED"}')
) AS r(relation_id,source_type,source_code,relation_type,target_type,target_code,
       target_version,is_primary,evidence_ref)
ON CONFLICT (relation_id) DO UPDATE SET
  status='ACTIVE',review_status='APPROVED',
  evidence_ref=EXCLUDED.evidence_ref,updated_at=CURRENT_TIMESTAMP;
