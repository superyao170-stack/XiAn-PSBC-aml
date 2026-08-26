-- Managed pattern metadata for two common text-case combinations.  Runtime
-- occurrences remain gated by formal semantic indicator hits.

INSERT INTO event_pattern_definition
  (pattern_code,pattern_name,pattern_type,scope,owner,status)
VALUES
  ('TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT','高频第三方支付活动','DAG','GLOBAL','bankgraph','ACTIVE'),
  ('TEXT_CROSS_BORDER_MULTI_ACCOUNT','多账户分散后跨境转移行为','DAG','GLOBAL','bankgraph','ACTIVE')
ON CONFLICT (pattern_code) DO UPDATE SET
  pattern_name=EXCLUDED.pattern_name,status='ACTIVE';

INSERT INTO event_pattern_version
  (pattern_code,version,pattern_payload,content_sha256,review_status,created_by)
VALUES
  ('TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT','1.0',
   '{"observationMode":"REPORTED","requiredObservations":["OBS_HIGH_FREQUENCY_ACTIVITY","OBS_THIRD_PARTY_PAYMENT"],"formalIndicatorRequired":true,"minimumEvents":1}',
   md5('TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT-v1')||md5('TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT-v1-bankgraph'),
   'APPROVED','migration-v65'),
  ('TEXT_CROSS_BORDER_MULTI_ACCOUNT','1.0',
   '{"observationMode":"REPORTED","requiredObservations":["OBS_MULTI_ACCOUNT_LAYERING","OBS_CROSS_REGION_ACTIVITY"],"formalIndicatorRequired":true,"minimumEvents":1}',
   md5('TEXT_CROSS_BORDER_MULTI_ACCOUNT-v1')||md5('TEXT_CROSS_BORDER_MULTI_ACCOUNT-v1-bankgraph'),
   'APPROVED','migration-v65')
ON CONFLICT (pattern_code,version) DO UPDATE SET
  pattern_payload=EXCLUDED.pattern_payload,
  content_sha256=EXCLUDED.content_sha256,
  review_status='APPROVED';

INSERT INTO knowledge_asset_relation
  (relation_id,source_type,source_code,source_version,relation_type,
   target_type,target_code,target_version,applicability,is_primary,evidence_ref,
   source_standard_version,effective_from,status,review_status,content_sha256,created_by)
SELECT relation_id,'BEHAVIOR_PATTERN',pattern_code,'1.0',
       'PATTERN_SUPPORTS_RISK_EVENT','RISK_EVENT_TYPE',
       'REPORTED_COMPOSITE_LAUNDERING_SCENARIO','1.0','TEXT_CASE_CANDIDATE',true,
       '{"assertionMode":"REPORTED","formalIndicatorRequired":true}'::jsonb,
       'BANKGRAPH-1.4',CURRENT_TIMESTAMP,'ACTIVE','APPROVED',
       md5(relation_id)||md5(relation_id||'-bankgraph'),'migration-v65'
FROM (VALUES
  ('KREL-TXT-DIGITAL-PAYMENT-RISK','TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT'),
  ('KREL-TXT-CROSS-BORDER-RISK','TEXT_CROSS_BORDER_MULTI_ACCOUNT')
) AS r(relation_id,pattern_code)
ON CONFLICT (relation_id) DO UPDATE SET
  status='ACTIVE',review_status='APPROVED',
  evidence_ref=EXCLUDED.evidence_ref,updated_at=CURRENT_TIMESTAMP;
