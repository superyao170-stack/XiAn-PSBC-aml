-- Event semantic governance between external financial standards and AML event frames.

CREATE TABLE IF NOT EXISTS event_standard_source (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL UNIQUE,
    source_namespace VARCHAR(64) NOT NULL,
    standard_name VARCHAR(256) NOT NULL,
    standard_version VARCHAR(64) NOT NULL,
    business_area VARCHAR(64),
    document_title VARCHAR(512) NOT NULL,
    official_url TEXT,
    document_number VARCHAR(128),
    published_at TIMESTAMPTZ,
    retrieved_at TIMESTAMPTZ,
    archive_ref TEXT,
    content_sha256 VARCHAR(64),
    authority_level VARCHAR(2) NOT NULL,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    license_note TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_event_standard_authority CHECK (authority_level IN ('S1','S2','S3','S4','S5')),
    CONSTRAINT chk_event_standard_status CHECK (status IN ('DRAFT','ACTIVE','SUPERSEDED','RETIRED'))
);

CREATE TABLE IF NOT EXISTS event_semantic_profile (
    id BIGSERIAL PRIMARY KEY,
    profile_id VARCHAR(64) NOT NULL,
    profile_code VARCHAR(192) NOT NULL,
    profile_version INT NOT NULL,
    profile_name VARCHAR(256) NOT NULL,
    meaning TEXT NOT NULL,
    business_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    applicable_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
    canonical_object VARCHAR(128),
    source_authority_level VARCHAR(2) NOT NULL DEFAULT 'S4',
    source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(profile_id,profile_version),
    UNIQUE(profile_code,profile_version),
    CONSTRAINT chk_event_profile_authority CHECK (source_authority_level IN ('S1','S2','S3','S4','S5')),
    CONSTRAINT chk_event_profile_status CHECK
        (status IN ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','REJECTED','RETIRED'))
);

CREATE TABLE IF NOT EXISTS event_semantic_profile_mapping (
    id BIGSERIAL PRIMARY KEY,
    mapping_id VARCHAR(64) NOT NULL UNIQUE,
    profile_code VARCHAR(192) NOT NULL,
    profile_version INT NOT NULL,
    source_id VARCHAR(64) NOT NULL REFERENCES event_standard_source(source_id),
    source_concept_code VARCHAR(256) NOT NULL,
    source_concept_name VARCHAR(512),
    message_family VARCHAR(64),
    message_version_range VARCHAR(128),
    mapping_type VARCHAR(24) NOT NULL,
    applicability JSONB NOT NULL DEFAULT '{}'::jsonb,
    condition_expression JSONB NOT NULL DEFAULT '{}'::jsonb,
    field_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence NUMERIC(8,6),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(profile_code,profile_version)
        REFERENCES event_semantic_profile(profile_code,profile_version),
    CONSTRAINT chk_event_profile_mapping_type CHECK
        (mapping_type IN ('EXACT','NARROWER','BROADER','DERIVED','RELATED')),
    CONSTRAINT chk_event_profile_mapping_confidence CHECK
        (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);

CREATE TABLE IF NOT EXISTS event_lifecycle_definition (
    id BIGSERIAL PRIMARY KEY,
    lifecycle_id VARCHAR(64) NOT NULL,
    lifecycle_code VARCHAR(128) NOT NULL,
    lifecycle_version INT NOT NULL,
    lifecycle_name VARCHAR(256) NOT NULL,
    description TEXT,
    applicable_profile_code VARCHAR(192),
    applicable_profile_version INT,
    initial_state VARCHAR(64) NOT NULL,
    terminal_states JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(lifecycle_id,lifecycle_version),
    UNIQUE(lifecycle_code,lifecycle_version),
    FOREIGN KEY(applicable_profile_code,applicable_profile_version)
        REFERENCES event_semantic_profile(profile_code,profile_version)
);

CREATE TABLE IF NOT EXISTS event_lifecycle_state (
    id BIGSERIAL PRIMARY KEY,
    lifecycle_code VARCHAR(128) NOT NULL,
    lifecycle_version INT NOT NULL,
    state_code VARCHAR(64) NOT NULL,
    state_name VARCHAR(128) NOT NULL,
    fact_level VARCHAR(32) NOT NULL,
    terminal BOOLEAN NOT NULL DEFAULT false,
    display_order INT NOT NULL DEFAULT 0,
    description TEXT,
    UNIQUE(lifecycle_code,lifecycle_version,state_code),
    FOREIGN KEY(lifecycle_code,lifecycle_version)
        REFERENCES event_lifecycle_definition(lifecycle_code,lifecycle_version) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS event_lifecycle_transition (
    id BIGSERIAL PRIMARY KEY,
    transition_id VARCHAR(64) NOT NULL UNIQUE,
    lifecycle_code VARCHAR(128) NOT NULL,
    lifecycle_version INT NOT NULL,
    from_state VARCHAR(64) NOT NULL,
    to_state VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(32),
    condition_expression JSONB NOT NULL DEFAULT '{}'::jsonb,
    description TEXT,
    UNIQUE(lifecycle_code,lifecycle_version,from_state,to_state),
    FOREIGN KEY(lifecycle_code,lifecycle_version,from_state)
        REFERENCES event_lifecycle_state(lifecycle_code,lifecycle_version,state_code) ON DELETE CASCADE,
    FOREIGN KEY(lifecycle_code,lifecycle_version,to_state)
        REFERENCES event_lifecycle_state(lifecycle_code,lifecycle_version,state_code) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS event_quality_policy (
    id BIGSERIAL PRIMARY KEY,
    policy_id VARCHAR(64) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    policy_name VARCHAR(256) NOT NULL,
    weights JSONB NOT NULL,
    thresholds JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(policy_id,policy_version)
);

CREATE TABLE IF NOT EXISTS event_identity_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    rule_version INT NOT NULL,
    rule_name VARCHAR(256) NOT NULL,
    applicable_profile_code VARCHAR(192),
    applicable_profile_version INT,
    strong_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    composite_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    time_window_seconds INT NOT NULL DEFAULT 300,
    auto_merge_threshold NUMERIC(8,6) NOT NULL DEFAULT 0.98,
    candidate_threshold NUMERIC(8,6) NOT NULL DEFAULT 0.80,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(rule_id,rule_version),
    UNIQUE(rule_code,rule_version),
    FOREIGN KEY(applicable_profile_code,applicable_profile_version)
        REFERENCES event_semantic_profile(profile_code,profile_version),
    CONSTRAINT chk_event_identity_thresholds CHECK (
        candidate_threshold BETWEEN 0 AND 1
        AND auto_merge_threshold BETWEEN 0 AND 1
        AND candidate_threshold <= auto_merge_threshold
    )
);

CREATE TABLE IF NOT EXISTS event_resolution_record (
    id BIGSERIAL PRIMARY KEY,
    resolution_id VARCHAR(64) NOT NULL UNIQUE,
    canonical_event_id VARCHAR(64) NOT NULL,
    event_refs JSONB NOT NULL,
    rule_code VARCHAR(128),
    rule_version INT,
    resolution_status VARCHAR(24) NOT NULL,
    score NUMERIC(8,6),
    conflicts JSONB NOT NULL DEFAULT '[]'::jsonb,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    resolution TEXT,
    resolved_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_event_resolution_status CHECK
        (resolution_status IN ('MATCHED','CANDIDATE','DISTINCT','CONFLICT'))
);

CREATE TABLE IF NOT EXISTS event_definition_supersession (
    id BIGSERIAL PRIMARY KEY,
    supersession_id VARCHAR(64) NOT NULL UNIQUE,
    source_frame_code VARCHAR(128) NOT NULL,
    source_frame_version INT NOT NULL,
    target_frame_code VARCHAR(128) NOT NULL,
    target_frame_version INT NOT NULL,
    mapping_type VARCHAR(24) NOT NULL,
    applicability JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_frame_code,source_frame_version,target_frame_code,target_frame_version)
);

CREATE TABLE IF NOT EXISTS technique_applicability_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    rule_version INT NOT NULL,
    event_frame_code VARCHAR(128) NOT NULL,
    event_frame_version INT NOT NULL,
    technique_code VARCHAR(128) NOT NULL,
    technique_version VARCHAR(32) NOT NULL DEFAULT '1.0',
    required_context JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    minimum_evidence_strength VARCHAR(2) NOT NULL DEFAULT 'E2',
    minimum_score NUMERIC(8,6) NOT NULL DEFAULT 0.8,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(rule_code,rule_version)
);

CREATE TABLE IF NOT EXISTS event_ontology_regression_case (
    id BIGSERIAL PRIMARY KEY,
    test_case_id VARCHAR(64) NOT NULL UNIQUE,
    test_case_name VARCHAR(256) NOT NULL,
    input_observations JSONB NOT NULL,
    expected_semantic_profile VARCHAR(128),
    expected_event_type VARCHAR(128),
    expected_fact_level VARCHAR(32),
    expected_lifecycle_state VARCHAR(64),
    expected_slots JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_quality_range JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_resolution VARCHAR(24),
    forbidden_event_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE semantic_frame_definition
    ADD COLUMN IF NOT EXISTS asset_kind VARCHAR(32) NOT NULL DEFAULT 'EVENT_FRAME',
    ADD COLUMN IF NOT EXISTS semantic_profile_code VARCHAR(192),
    ADD COLUMN IF NOT EXISTS semantic_profile_version INT,
    ADD COLUMN IF NOT EXISTS domain_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS business_object_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS action_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS fact_level VARCHAR(32) NOT NULL DEFAULT 'ASSERTION',
    ADD COLUMN IF NOT EXISTS standard_namespace VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_authority_level VARCHAR(2) NOT NULL DEFAULT 'S4',
    ADD COLUMN IF NOT EXISTS source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_to TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supersedes_version INT,
    ADD COLUMN IF NOT EXISTS source_snapshot_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS legacy_event BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE cf_risk_event
    ADD COLUMN IF NOT EXISTS semantic_profile_code VARCHAR(192),
    ADD COLUMN IF NOT EXISTS semantic_profile_version INT,
    ADD COLUMN IF NOT EXISTS lifecycle_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS lifecycle_version INT,
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(64),
    ADD COLUMN IF NOT EXISTS fact_level VARCHAR(32) NOT NULL DEFAULT 'ASSERTION',
    ADD COLUMN IF NOT EXISTS event_quality_score NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS quality_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS quality_policy_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS identity_resolution_status VARCHAR(24) NOT NULL DEFAULT 'DISTINCT',
    ADD COLUMN IF NOT EXISTS canonical_event_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_event_profile_status
    ON event_semantic_profile(status,profile_code,profile_version DESC);
CREATE INDEX IF NOT EXISTS idx_event_standard_source_lookup
    ON event_standard_source(source_namespace,status);
CREATE INDEX IF NOT EXISTS idx_event_lifecycle_lookup
    ON event_lifecycle_definition(status,lifecycle_code,lifecycle_version DESC);
CREATE INDEX IF NOT EXISTS idx_event_identity_rule_lookup
    ON event_identity_rule(status,rule_code,rule_version DESC);
CREATE INDEX IF NOT EXISTS idx_cf_risk_event_canonical
    ON cf_risk_event(canonical_event_id) WHERE deleted=false;

INSERT INTO event_standard_source
    (source_id,source_namespace,standard_name,standard_version,business_area,
     document_title,official_url,authority_level,verification_status,status,created_by)
VALUES
    ('SRC-ISO20022-BUSINESS-MODEL','ISO_20022','ISO 20022 Business Model','CURRENT',
     'COMMON','ISO 20022 Business Model',
     'https://www.iso20022.org/iso20022-repository/business-model','S1','URL_VERIFIED','ACTIVE','migration-v48'),
    ('SRC-ISO20022-REPOSITORY','ISO_20022','ISO 20022 Repository','CURRENT',
     'MULTI_DOMAIN','ISO 20022 Financial Repository',
     'https://www.iso20022.org/financial-repository','S1','URL_VERIFIED','ACTIVE','migration-v48'),
    ('SRC-SWIFT-ISO20022','SWIFT_CBPR_PLUS','SWIFT ISO 20022 / CBPR+','CURRENT',
     'PAYMENTS','SWIFT ISO 20022 Standards',
     'https://www.swift.com/standards/iso-20022','S1','URL_VERIFIED','ACTIVE','migration-v48'),
    ('SRC-BIAN','BIAN','BIAN Service Landscape / Semantic API','CURRENT',
     'BANKING','BIAN Banking Architecture',
     'https://bian.org/','S2','URL_VERIFIED','ACTIVE','migration-v48'),
    ('SRC-BANK-CORE','BANK_CORE','Bank Core Transaction Model','LOCAL',
     'CORE_BANKING','本行核心交易数据字典',NULL,'S2','PENDING_ARCHIVE','DRAFT','migration-v48')
ON CONFLICT (source_id) DO UPDATE SET
    official_url=EXCLUDED.official_url,updated_at=CURRENT_TIMESTAMP;

INSERT INTO event_semantic_profile
    (profile_id,profile_code,profile_version,profile_name,meaning,business_actions,
     applicable_domains,canonical_object,source_authority_level,source_refs,status,
     review_status,effective_from,created_by)
VALUES
    ('PROFILE-PAYMENT-CREDIT-TRANSFER','PAYMENT.CREDIT_TRANSFER',1,'贷记转账',
     '付款方通过金融机构向收款方转移资金的业务过程。',
     '["PAYMENT_INSTRUCTION","DEBIT","CREDIT","SETTLEMENT"]',
     '["FINANCIAL_EVENT"]','CREDIT_TRANSFER','S1',
     '[{"sourceId":"SRC-ISO20022-BUSINESS-MODEL"},{"sourceId":"SRC-SWIFT-ISO20022"}]',
     'ACTIVE','APPROVED',CURRENT_TIMESTAMP,'migration-v48'),
    ('PROFILE-ACCOUNT-ENTRY','ACCOUNT.ENTRY',1,'账户账务分录',
     '核心账务系统对账户余额产生影响的借记、贷记或冲正分录。',
     '["DEBIT_POSTED","CREDIT_POSTED","REVERSED"]',
     '["FINANCIAL_EVENT"]','ACCOUNT_ENTRY','S2',
     '[{"sourceId":"SRC-BANK-CORE"}]','ACTIVE','APPROVED',CURRENT_TIMESTAMP,'migration-v48'),
    ('PROFILE-CASH-DEPOSIT','CASH.DEPOSIT',1,'现金存入',
     '客户或第三方向账户增加现金余额。',
     '["CASH_RECEIVED","ACCOUNT_CREDIT"]','["FINANCIAL_EVENT"]','CASH_DEPOSIT','S2',
     '[{"sourceId":"SRC-BANK-CORE"}]','ACTIVE','APPROVED',CURRENT_TIMESTAMP,'migration-v48'),
    ('PROFILE-AML-CDD','AML_PROCESS.CDD',1,'客户尽职调查',
     '金融机构执行客户身份识别和持续尽职调查的过程。',
     '["CDD_STARTED","CDD_COMPLETED","CDD_FAILED"]','["AML_PROCESS_EVENT"]','CDD','S2',
     '[]','ACTIVE','APPROVED',CURRENT_TIMESTAMP,'migration-v48'),
    ('PROFILE-REGULATORY-REQUEST','REGULATORY.LAW_ENFORCEMENT_REQUEST',1,'执法协查请求',
     '执法或监管机关向金融机构发出调查协查请求。',
     '["REQUEST_RECEIVED","REQUEST_RESPONDED"]','["REGULATORY_EVENT"]',
     'LAW_ENFORCEMENT_REQUEST','S2','[]','ACTIVE','APPROVED',CURRENT_TIMESTAMP,'migration-v48')
ON CONFLICT (profile_code,profile_version) DO NOTHING;

-- Every old active frame receives an explicit legacy profile. It remains replayable,
-- but new canonical recognition should prefer the lifecycle-specific profiles above.
INSERT INTO event_semantic_profile
    (profile_id,profile_code,profile_version,profile_name,meaning,business_actions,
     applicable_domains,canonical_object,source_authority_level,source_refs,status,
     review_status,effective_from,created_by)
SELECT 'PROFILE-LEGACY-' || substr(md5(f.frame_code),1,32),
       'LEGACY.' || f.frame_code,1,'Legacy：' || f.frame_name,
       COALESCE(f.description,f.frame_name),
       jsonb_build_array(f.frame_code),
       '["LEGACY_EVENT"]'::jsonb,f.frame_code,'S4',
       jsonb_build_array(jsonb_build_object('source','PROJECT_BASELINE_V1','frameCode',f.frame_code)),
       'ACTIVE','APPROVED',f.created_at,'migration-v48'
FROM semantic_frame_definition f
WHERE f.event_layer='BASE_EVENT'
ON CONFLICT (profile_code,profile_version) DO NOTHING;

UPDATE semantic_frame_definition
SET semantic_profile_code='LEGACY.' || frame_code,
    semantic_profile_version=1,
    domain_code=COALESCE(domain_code,'LEGACY_EVENT'),
    business_object_code=COALESCE(business_object_code,frame_code),
    action_code=COALESCE(action_code,'LEGACY_ASSERTED'),
    source_authority_level=COALESCE(source_authority_level,'S4'),
    legacy_event=true,
    updated_at=CURRENT_TIMESTAMP
WHERE event_layer='BASE_EVENT' AND semantic_profile_code IS NULL;

ALTER TABLE semantic_frame_definition
    DROP CONSTRAINT IF EXISTS fk_semantic_frame_profile;
ALTER TABLE semantic_frame_definition
    ADD CONSTRAINT fk_semantic_frame_profile
    FOREIGN KEY(semantic_profile_code,semantic_profile_version)
    REFERENCES event_semantic_profile(profile_code,profile_version);

INSERT INTO event_lifecycle_definition
    (lifecycle_id,lifecycle_code,lifecycle_version,lifecycle_name,description,
     applicable_profile_code,applicable_profile_version,initial_state,terminal_states,
     status,review_status,created_by)
VALUES
    ('LIFECYCLE-PAYMENT-CREDIT-TRANSFER','PAYMENT.CREDIT_TRANSFER',1,'贷记转账生命周期',
     '区分付款意图、受理、清算、结算、入账、退回和冲正。',
     'PAYMENT.CREDIT_TRANSFER',1,'INITIATED',
     '["REJECTED","CANCELLED","RETURNED","REVERSED"]','ACTIVE','APPROVED','migration-v48')
ON CONFLICT (lifecycle_code,lifecycle_version) DO NOTHING;

INSERT INTO event_lifecycle_state
    (lifecycle_code,lifecycle_version,state_code,state_name,fact_level,terminal,display_order)
VALUES
    ('PAYMENT.CREDIT_TRANSFER',1,'INITIATED','已发起','INSTRUCTION',false,1),
    ('PAYMENT.CREDIT_TRANSFER',1,'ACCEPTED','已受理','PROCESS_STATUS',false,2),
    ('PAYMENT.CREDIT_TRANSFER',1,'CLEARED','已清算','CLEARING_FACT',false,3),
    ('PAYMENT.CREDIT_TRANSFER',1,'SETTLED','已结算','SETTLEMENT_FACT',false,4),
    ('PAYMENT.CREDIT_TRANSFER',1,'BOOKED','已入账','BOOKING_FACT',false,5),
    ('PAYMENT.CREDIT_TRANSFER',1,'REJECTED','已拒绝','PROCESS_STATUS',true,6),
    ('PAYMENT.CREDIT_TRANSFER',1,'CANCELLATION_REQUESTED','已申请撤销','INSTRUCTION',false,7),
    ('PAYMENT.CREDIT_TRANSFER',1,'CANCELLED','已撤销','PROCESS_STATUS',true,8),
    ('PAYMENT.CREDIT_TRANSFER',1,'RETURNED','已退回','BOOKING_FACT',true,9),
    ('PAYMENT.CREDIT_TRANSFER',1,'REVERSED','已冲正','BOOKING_FACT',true,10)
ON CONFLICT (lifecycle_code,lifecycle_version,state_code) DO NOTHING;

INSERT INTO event_lifecycle_transition
    (transition_id,lifecycle_code,lifecycle_version,from_state,to_state,trigger_type)
VALUES
    ('TRANS-PAY-INIT-ACCEPT', 'PAYMENT.CREDIT_TRANSFER',1,'INITIATED','ACCEPTED','STATUS_UPDATE'),
    ('TRANS-PAY-INIT-REJECT', 'PAYMENT.CREDIT_TRANSFER',1,'INITIATED','REJECTED','STATUS_UPDATE'),
    ('TRANS-PAY-ACCEPT-CLEAR','PAYMENT.CREDIT_TRANSFER',1,'ACCEPTED','CLEARED','CLEARING'),
    ('TRANS-PAY-CLEAR-SETTLE','PAYMENT.CREDIT_TRANSFER',1,'CLEARED','SETTLED','SETTLEMENT'),
    ('TRANS-PAY-SETTLE-BOOK','PAYMENT.CREDIT_TRANSFER',1,'SETTLED','BOOKED','CORE_BOOKING'),
    ('TRANS-PAY-ACCEPT-CANCELREQ','PAYMENT.CREDIT_TRANSFER',1,'ACCEPTED','CANCELLATION_REQUESTED','CANCEL_REQUEST'),
    ('TRANS-PAY-CANCELREQ-CANCEL','PAYMENT.CREDIT_TRANSFER',1,'CANCELLATION_REQUESTED','CANCELLED','STATUS_UPDATE'),
    ('TRANS-PAY-SETTLE-RETURN','PAYMENT.CREDIT_TRANSFER',1,'SETTLED','RETURNED','RETURN'),
    ('TRANS-PAY-BOOK-REVERSE','PAYMENT.CREDIT_TRANSFER',1,'BOOKED','REVERSED','REVERSAL')
ON CONFLICT (transition_id) DO NOTHING;

INSERT INTO event_quality_policy
    (policy_id,policy_version,policy_name,weights,thresholds,status,review_status,created_by)
VALUES
    ('EVENT-QUALITY','1.0','事件质量基线',
     '{"sourceReliability":0.30,"completeness":0.20,"temporalPrecision":0.15,"entityResolution":0.20,"extractionConfidence":0.15}',
     '{"high":0.85,"medium":0.65,"low":0.40}',
     'ACTIVE','APPROVED','migration-v48')
ON CONFLICT (policy_id,policy_version) DO NOTHING;

INSERT INTO event_identity_rule
    (rule_id,rule_code,rule_version,rule_name,applicable_profile_code,
     applicable_profile_version,strong_keys,composite_keys,time_window_seconds,
     auto_merge_threshold,candidate_threshold,status,review_status,created_by)
VALUES
    ('IDENTITY-PAYMENT-TRANSFER','PAYMENT_TRANSFER_IDENTITY',1,'贷记转账跨来源身份规则',
     'PAYMENT.CREDIT_TRANSFER',1,'["uetr","transactionId","endToEndId"]',
     '["amount","currency","debtor","creditor","eventTime"]',300,0.98,0.80,
     'ACTIVE','APPROVED','migration-v48')
ON CONFLICT (rule_code,rule_version) DO NOTHING;

INSERT INTO event_semantic_profile_mapping
    (mapping_id,profile_code,profile_version,source_id,source_concept_code,
     source_concept_name,message_family,mapping_type,condition_expression,
     confidence,review_status,status,created_by)
VALUES
    ('MAP-PACS008-CREDIT-TRANSFER','PAYMENT.CREDIT_TRANSFER',1,
     'SRC-ISO20022-REPOSITORY','pacs.008','FIToFICustomerCreditTransfer','pacs',
     'DERIVED','{"allowedFacts":["INSTRUCTED"],"forbiddenFacts":["SETTLED","BOOKED"]}',
     0.95,'APPROVED','ACTIVE','migration-v48'),
    ('MAP-PACS002-CREDIT-TRANSFER','PAYMENT.CREDIT_TRANSFER',1,
     'SRC-ISO20022-REPOSITORY','pacs.002','FIToFIPaymentStatusReport','pacs',
     'DERIVED','{"statusDriven":true,"forbiddenFacts":["BOOKED"]}',
     0.95,'APPROVED','ACTIVE','migration-v48'),
    ('MAP-CAMT054-ACCOUNT-ENTRY','ACCOUNT.ENTRY',1,
     'SRC-ISO20022-REPOSITORY','camt.054','BankToCustomerDebitCreditNotification','camt',
     'DERIVED','{"requires":["creditDebitIndicator","bookingStatus","accountId"]}',
     0.95,'APPROVED','ACTIVE','migration-v48')
ON CONFLICT (mapping_id) DO NOTHING;

UPDATE cf_risk_event
SET canonical_event_id=COALESCE(canonical_event_id,event_id),
    semantic_profile_code=COALESCE(semantic_profile_code,'LEGACY.' || event_frame_code),
    semantic_profile_version=CASE WHEN event_frame_code IS NULL THEN semantic_profile_version
                                  ELSE COALESCE(semantic_profile_version,1) END,
    event_quality_score=COALESCE(event_quality_score,
        LEAST(1.0,GREATEST(0.0,COALESCE(definition_match_confidence,confidence,0.5)))),
    quality_breakdown=CASE WHEN quality_breakdown='{}'::jsonb THEN
        jsonb_build_object(
            'sourceReliability',CASE
                WHEN definition_match_method IN ('STRUCTURED_WORKER','EVENT_TYPE_DICTIONARY') THEN 0.95
                WHEN definition_match_method LIKE '%MANUAL%' THEN 0.90
                ELSE 0.65 END,
            'completeness',CASE WHEN evidence_refs IS NULL THEN 0.50 ELSE 0.80 END,
            'temporalPrecision',CASE WHEN event_time IS NULL THEN 'UNKNOWN' ELSE 'SECOND' END,
            'entityResolution',0.50,
            'extractionConfidence',COALESCE(definition_match_confidence,confidence,0.50)
        ) ELSE quality_breakdown END,
    quality_policy_version=COALESCE(quality_policy_version,'1.0')
WHERE deleted=false;

INSERT INTO event_ontology_regression_case
    (test_case_id,test_case_name,input_observations,expected_semantic_profile,
     expected_event_type,expected_fact_level,expected_lifecycle_state,
     forbidden_event_types,created_by)
VALUES
    ('EVT-REG-PACS008-ACCP','pacs.008受理不得误判结算',
     '{"messageType":"pacs.008","status":"ACCP"}',
     'PAYMENT.CREDIT_TRANSFER','PAYMENT.CREDIT_TRANSFER.ACCEPTED',
     'PROCESS_STATUS','ACCEPTED',
     '["PAYMENT.CREDIT_TRANSFER.SETTLED","ACCOUNT.ENTRY.CREDIT_POSTED"]',
     'migration-v48'),
    ('EVT-REG-SWIFT-ACK','SWIFT ACK不得生成资金到账',
     '{"sourceStandard":"SWIFT","networkStatus":"ACK"}',
     'PAYMENT.CREDIT_TRANSFER',NULL,'ASSERTION',NULL,
     '["PAYMENT.CREDIT_TRANSFER.SETTLED","ACCOUNT.ENTRY.CREDIT_POSTED"]',
     'migration-v48'),
    ('EVT-REG-CORE-CREDIT','核心贷记入账形成账务事实',
     '{"sourceSystem":"CORE_BANKING","entryType":"CREDIT","bookingStatus":"POSTED"}',
     'ACCOUNT.ENTRY','ACCOUNT.ENTRY.CREDIT_POSTED','BOOKING_FACT','BOOKED',
     '[]','migration-v48')
ON CONFLICT (test_case_id) DO NOTHING;

INSERT INTO sys_menu(parent_id,menu_name,path,component,sort_order,type,permission,visible)
SELECT p.id,'事件语义治理','/knowledge/event-governance',
       'views/knowledge/EventGovernance.vue',3,'MENU','knowledge:view',true
FROM sys_menu p
WHERE p.path='/knowledge'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/knowledge/event-governance');
