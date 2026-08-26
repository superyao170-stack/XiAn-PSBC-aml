-- A reported threshold-splitting observation is a pattern candidate only after
-- it has been matched to a formal ACTIVE indicator by the backend.

INSERT INTO event_pattern_definition
  (pattern_code,pattern_name,pattern_type,scope,owner,status)
VALUES
  ('TEXT_THRESHOLD_STRUCTURING','阈值下分笔存入行为','DAG','GLOBAL','bankgraph','ACTIVE')
ON CONFLICT (pattern_code) DO UPDATE SET
  pattern_name=EXCLUDED.pattern_name,status='ACTIVE';

INSERT INTO event_pattern_version
  (pattern_code,version,pattern_payload,content_sha256,review_status,created_by)
VALUES
  ('TEXT_THRESHOLD_STRUCTURING','1.0',
   '{"observationMode":"REPORTED","requiredObservations":["OBS_THRESHOLD_STRUCTURING"],"formalIndicatorRequired":true,"minimumEvents":1}',
   md5('TEXT_THRESHOLD_STRUCTURING-v1')||md5('TEXT_THRESHOLD_STRUCTURING-v1-bankgraph'),
   'APPROVED','migration-v64')
ON CONFLICT (pattern_code,version) DO UPDATE SET
  pattern_payload=EXCLUDED.pattern_payload,
  content_sha256=EXCLUDED.content_sha256,
  review_status='APPROVED';

INSERT INTO knowledge_asset_relation
  (relation_id,source_type,source_code,source_version,relation_type,
   target_type,target_code,target_version,applicability,is_primary,evidence_ref,
   source_standard_version,effective_from,status,review_status,content_sha256,created_by)
VALUES
  ('KREL-TXT-THRESHOLD-RISK','BEHAVIOR_PATTERN','TEXT_THRESHOLD_STRUCTURING','1.0',
   'PATTERN_SUPPORTS_RISK_EVENT','RISK_EVENT_TYPE',
   'REPORTED_COMPOSITE_LAUNDERING_SCENARIO','1.0','TEXT_CASE_CANDIDATE',true,
   '{"assertionMode":"REPORTED","formalIndicatorRequired":true}'::jsonb,
   'BANKGRAPH-1.4',CURRENT_TIMESTAMP,'ACTIVE','APPROVED',
   md5('KREL-TXT-THRESHOLD-RISK')||md5('KREL-TXT-THRESHOLD-RISK-bankgraph'),
   'migration-v64')
ON CONFLICT (relation_id) DO UPDATE SET
  status='ACTIVE',review_status='APPROVED',
  evidence_ref=EXCLUDED.evidence_ref,updated_at=CURRENT_TIMESTAMP;
