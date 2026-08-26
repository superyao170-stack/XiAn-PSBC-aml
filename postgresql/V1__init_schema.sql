
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar VARCHAR(255),
    status BOOLEAN DEFAULT true,
    role_code VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32),
    institution_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false,
    last_login_time TIMESTAMPTZ,
    last_login_ip VARCHAR(64)
);

CREATE INDEX idx_sys_user_username ON sys_user(username);
CREATE INDEX idx_sys_user_role_code ON sys_user(role_code);
CREATE INDEX idx_sys_user_bank_code ON sys_user(bank_code);

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    menus TEXT[],
    permissions TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    menu_name VARCHAR(64) NOT NULL,
    path VARCHAR(255),
    component VARCHAR(255),
    icon VARCHAR(64),
    sort_order INT DEFAULT 0,
    type VARCHAR(16) NOT NULL,
    permission VARCHAR(128),
    visible BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);

CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(64) NOT NULL UNIQUE,
    dict_name VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    status BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(64) NOT NULL,
    dict_value VARCHAR(128) NOT NULL,
    dict_label VARCHAR(128) NOT NULL,
    sort_order INT DEFAULT 0,
    status BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_config (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT,
    config_type VARCHAR(32) DEFAULT 'STRING',
    description VARCHAR(255),
    status BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS struct_schema (
    id BIGSERIAL PRIMARY KEY,
    schema_code VARCHAR(64) NOT NULL UNIQUE,
    schema_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    category VARCHAR(64),
    version_count INT DEFAULT 0,
    latest_version VARCHAR(32),
    bank_code VARCHAR(32),
    institution_id BIGINT,
    status VARCHAR(24) DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);

CREATE TABLE IF NOT EXISTS struct_schema_version (
    id BIGSERIAL PRIMARY KEY,
    schema_id BIGINT NOT NULL REFERENCES struct_schema(id),
    version VARCHAR(32) NOT NULL,
    schema_definition JSONB NOT NULL,
    field_definitions JSONB NOT NULL,
    semantic_mapping JSONB,
    graph_mapping JSONB,
    changelog TEXT,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE UNIQUE INDEX uk_struct_schema_version ON struct_schema_version(schema_id, version);

CREATE TABLE IF NOT EXISTS struct_schema_binding (
    id BIGSERIAL PRIMARY KEY,
    schema_id BIGINT NOT NULL REFERENCES struct_schema(id),
    schema_version VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    source_scope VARCHAR(64),
    effective_start TIMESTAMPTZ NOT NULL,
    effective_end TIMESTAMPTZ,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    binding_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS structured_record_head (
    id BIGSERIAL PRIMARY KEY,
    record_uid VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    schema_id BIGINT NOT NULL REFERENCES struct_schema(id),
    schema_version VARCHAR(32) NOT NULL,
    batch_id BIGINT,
    source_signal_id VARCHAR(64),
    source_signal_version BIGINT,
    lifecycle_status VARCHAR(24) DEFAULT 'ACTIVE',
    record_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS structured_business_record (
    id BIGSERIAL PRIMARY KEY,
    head_id BIGINT NOT NULL REFERENCES structured_record_head(id),
    payload_json JSONB NOT NULL,
    record_version BIGINT DEFAULT 1,
    amount_num DECIMAL(24,6),
    occurred_at_tz TIMESTAMPTZ,
    written_schema_version VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_structured_business_record_head_id ON structured_business_record(head_id);
CREATE INDEX idx_structured_business_record_payload ON structured_business_record USING GIN(payload_json);

CREATE TABLE IF NOT EXISTS risk_ingest_batch (
    id BIGSERIAL PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    workspace_id BIGINT NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    source_count BIGINT DEFAULT 0,
    accepted_count BIGINT DEFAULT 0,
    rejected_count BIGINT DEFAULT 0,
    duplicate_count BIGINT DEFAULT 0,
    input_sha256 CHAR(64) NOT NULL,
    status VARCHAR(24) DEFAULT 'PROCESSING',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS risk_transaction_materialized (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    workspace_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL REFERENCES risk_ingest_batch(id),
    source_record_id VARCHAR(128) NOT NULL,
    account_hash CHAR(64) NOT NULL,
    counterparty_hash CHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    currency CHAR(3) NOT NULL,
    amount DECIMAL(24,6) NOT NULL,
    transaction_type VARCHAR(64),
    channel VARCHAR(64),
    lifecycle_status VARCHAR(24) DEFAULT 'NORMAL',
    record_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_signal (
    id BIGSERIAL PRIMARY KEY,
    signal_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    workspace_id BIGINT NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    scenario_version VARCHAR(32),
    pipeline_version VARCHAR(32),
    algorithm_binding_version VARCHAR(32),
    deployment_config_snapshot_id VARCHAR(64),
    effective_config_snapshot_id VARCHAR(64),
    execution_package_id VARCHAR(64),
    signal_type VARCHAR(32) NOT NULL,
    source_ref_type VARCHAR(32),
    source_ref_id VARCHAR(128),
    algorithm_id VARCHAR(64),
    algorithm_version VARCHAR(32),
    algorithm_endpoint VARCHAR(255),
    score DECIMAL(12,6) NOT NULL,
    decision VARCHAR(32),
    reason_codes TEXT[],
    recommended_action VARCHAR(255),
    policy_version VARCHAR(32),
    model_version VARCHAR(32),
    input_data_version VARCHAR(64),
    input_data_hash CHAR(64),
    contribution JSONB,
    fusion_weight DECIMAL(6,4),
    parent_signal_id VARCHAR(64),
    is_fused BOOLEAN DEFAULT false,
    status VARCHAR(24) DEFAULT 'CANDIDATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE INDEX idx_risk_signal_scenario_status ON risk_signal(scenario_code, status, created_at DESC);

CREATE TABLE IF NOT EXISTS text_risk_signal (
    id BIGSERIAL PRIMARY KEY,
    signal_id VARCHAR(64) NOT NULL UNIQUE REFERENCES risk_signal(signal_id),
    text_case_id VARCHAR(64),
    document_hash CHAR(64),
    extraction_confidence DECIMAL(6,4),
    llm_model_version VARCHAR(64),
    entity_extractions JSONB,
    event_extractions JSONB,
    evidence_offsets JSONB,
    original_text_fragment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS entity_master (
    id BIGSERIAL PRIMARY KEY,
    entity_uid VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_hash CHAR(64) NOT NULL,
    display_label VARCHAR(128),
    properties JSONB,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_entity_master_hash ON entity_master(entity_hash);
CREATE INDEX idx_entity_master_bank ON entity_master(bank_code, entity_type);

CREATE TABLE IF NOT EXISTS entity_signal_ref (
    id BIGSERIAL PRIMARY KEY,
    entity_uid VARCHAR(64) NOT NULL REFERENCES entity_master(entity_uid),
    signal_id VARCHAR(64) NOT NULL REFERENCES risk_signal(signal_id),
    ref_type VARCHAR(32) NOT NULL,
    ref_source_id VARCHAR(128) NOT NULL,
    confidence DECIMAL(6,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_entity_signal_ref ON entity_signal_ref(entity_uid, signal_id, ref_type);

CREATE TABLE IF NOT EXISTS cf_risk_case (
    id BIGSERIAL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL UNIQUE,
    case_version BIGINT DEFAULT 1,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    org_id BIGINT,
    workspace_id BIGINT,
    scenario_code VARCHAR(64) NOT NULL,
    scenario_version VARCHAR(32),
    case_source VARCHAR(32) NOT NULL,
    case_type VARCHAR(32),
    case_status VARCHAR(32) DEFAULT 'DRAFT',
    risk_score DECIMAL(12,6),
    risk_level VARCHAR(32),
    subject_count INT DEFAULT 0,
    transaction_count INT DEFAULT 0,
    total_amount DECIMAL(24,6),
    graph_snapshot_id VARCHAR(64),
    graph_snapshot_sha256 CHAR(64),
    deployment_config_snapshot_id VARCHAR(64),
    effective_config_snapshot_id VARCHAR(64),
    execution_package_id VARCHAR(64),
    owner_analyst VARCHAR(64),
    reviewer VARCHAR(64),
    approver VARCHAR(64),
    struct_decision VARCHAR(32),
    text_decision VARCHAR(32),
    decision_conflict BOOLEAN DEFAULT false,
    conflict_details JSONB,
    supersedes_case_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ,
    deleted BOOLEAN DEFAULT false
);

CREATE INDEX idx_cf_risk_case_bank_status ON cf_risk_case(bank_code, case_status, created_at DESC);
CREATE INDEX idx_cf_risk_case_scenario ON cf_risk_case(scenario_code, risk_level);

CREATE TABLE IF NOT EXISTS cf_risk_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    case_version BIGINT DEFAULT 1,
    bank_code VARCHAR(32) NOT NULL,
    event_name VARCHAR(200) NOT NULL,
    event_type VARCHAR(50),
    event_standard_code VARCHAR(64),
    event_time TIMESTAMPTZ,
    confidence DECIMAL(6,4),
    evidence_refs JSONB,
    dedup_key VARCHAR(128),
    risk_score DECIMAL(12,6),
    risk_level VARCHAR(50),
    rule_name VARCHAR(200),
    subject_count INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);

CREATE TABLE IF NOT EXISTS case_signal_rel (
    id BIGSERIAL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    case_version BIGINT DEFAULT 1,
    signal_id VARCHAR(64) NOT NULL REFERENCES risk_signal(signal_id),
    bank_code VARCHAR(32) NOT NULL,
    signal_role VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_case_signal_rel ON case_signal_rel(case_id, signal_id);

CREATE TABLE IF NOT EXISTS case_review_record (
    id BIGSERIAL PRIMARY KEY,
    review_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    case_version BIGINT DEFAULT 1,
    bank_code VARCHAR(32) NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    review_type VARCHAR(32) NOT NULL,
    review_result VARCHAR(32) NOT NULL,
    review_opinion TEXT,
    signal_conflict BOOLEAN DEFAULT false,
    conflict_details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS case_approval_record (
    id BIGSERIAL PRIMARY KEY,
    approval_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    case_version BIGINT DEFAULT 1,
    bank_code VARCHAR(32) NOT NULL,
    approver VARCHAR(64) NOT NULL,
    approval_step VARCHAR(32) NOT NULL,
    approval_result VARCHAR(32) NOT NULL,
    approval_opinion TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS workflow_instance (
    id BIGSERIAL PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL UNIQUE,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id),
    bank_code VARCHAR(32) NOT NULL,
    workflow_type VARCHAR(64) NOT NULL,
    current_step VARCHAR(64),
    status VARCHAR(24) DEFAULT 'ACTIVE',
    initiated_by VARCHAR(64),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_instance_case_id ON workflow_instance(case_id);
CREATE INDEX idx_workflow_instance_status ON workflow_instance(status);

CREATE TABLE IF NOT EXISTS workflow_step (
    id BIGSERIAL PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL REFERENCES workflow_instance(workflow_id),
    step_order INT NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    assignee VARCHAR(64),
    status VARCHAR(24) DEFAULT 'PENDING',
    result VARCHAR(64),
    opinion TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_step_workflow_id ON workflow_step(workflow_id);
CREATE UNIQUE INDEX uk_workflow_step_order ON workflow_step(workflow_id, step_order);

CREATE TABLE IF NOT EXISTS risk_clue (
    id BIGSERIAL PRIMARY KEY,
    clue_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    case_id VARCHAR(64) REFERENCES cf_risk_case(case_id),
    clue_type VARCHAR(64) NOT NULL,
    algorithm_code VARCHAR(64),
    algorithm_version VARCHAR(32),
    evidence_subgraph_ref VARCHAR(255),
    confidence DECIMAL(6,4),
    explanation TEXT,
    related_case_ids TEXT[],
    related_subject_ids TEXT[],
    status VARCHAR(24) DEFAULT 'CANDIDATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS risk_scenario_template (
    id BIGSERIAL PRIMARY KEY,
    template_id VARCHAR(64) NOT NULL UNIQUE,
    template_name VARCHAR(128) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    description VARCHAR(512),
    config_definition JSONB NOT NULL,
    algorithm_bindings JSONB,
    default_thresholds JSONB,
    bank_code VARCHAR(32),
    status VARCHAR(24) DEFAULT 'ACTIVE',
    version VARCHAR(32) DEFAULT '1.0.0',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_scenario_binding (
    id BIGSERIAL PRIMARY KEY,
    template_id VARCHAR(64) NOT NULL REFERENCES risk_scenario_template(template_id),
    bank_code VARCHAR(32) NOT NULL,
    institution_id BIGINT,
    effective_start TIMESTAMPTZ NOT NULL,
    effective_end TIMESTAMPTZ,
    override_config JSONB,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_deployment_config_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    config_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_effective_config_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    config_json JSONB NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_regulatory_exemption (
    id BIGSERIAL PRIMARY KEY,
    exemption_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    exemption_type VARCHAR(32),
    reason TEXT,
    approved_by VARCHAR(64),
    effective_start TIMESTAMPTZ NOT NULL,
    effective_end TIMESTAMPTZ,
    status VARCHAR(24) DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shared_schema (
    id BIGSERIAL PRIMARY KEY,
    schema_id VARCHAR(64) NOT NULL UNIQUE,
    schema_name VARCHAR(128) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    schema_definition JSONB NOT NULL,
    description VARCHAR(512),
    category VARCHAR(64),
    status VARCHAR(24) DEFAULT 'PUBLISHED',
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shared_clue (
    id BIGSERIAL PRIMARY KEY,
    clue_id VARCHAR(64) NOT NULL UNIQUE,
    clue_type VARCHAR(64) NOT NULL,
    description TEXT,
    evidence_json JSONB,
    confidence DECIMAL(6,4),
    source_bank_code VARCHAR(32),
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pending_activation (
    id BIGSERIAL PRIMARY KEY,
    activation_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    dispatch_id VARCHAR(64),
    content_type VARCHAR(32) NOT NULL,
    content_id VARCHAR(64),
    content_version VARCHAR(32),
    content_payload JSONB,
    compatibility_report JSONB,
    performance_impact TEXT,
    status VARCHAR(24) DEFAULT 'PENDING_ACTIVATION',
    activated_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    rejected_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pending_activation_bank_status ON pending_activation(bank_code, status, created_at DESC);

CREATE TABLE IF NOT EXISTS report_record (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    report_type VARCHAR(32) NOT NULL,
    record_count INT DEFAULT 0,
    topic VARCHAR(128),
    payload_hash CHAR(64),
    status VARCHAR(24) DEFAULT 'PENDING',
    error_message TEXT,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS graph_engine_config (
    id BIGSERIAL PRIMARY KEY,
    engine_type VARCHAR(32) NOT NULL,
    engine_name VARCHAR(64) NOT NULL UNIQUE,
    connection_url VARCHAR(255) NOT NULL,
    username VARCHAR(64),
    password VARCHAR(255),
    password_ref VARCHAR(128),
    properties JSONB,
    is_primary BOOLEAN DEFAULT false,
    status VARCHAR(24) DEFAULT 'INACTIVE',
    health_check_url VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS graph_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    case_id VARCHAR(64) REFERENCES cf_risk_case(case_id),
    engine_type VARCHAR(32) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    node_count INT DEFAULT 0,
    edge_count INT DEFAULT 0,
    snapshot_size BIGINT,
    metadata JSONB,
    status VARCHAR(24) DEFAULT 'CREATING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS graph_query_log (
    id BIGSERIAL PRIMARY KEY,
    query_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    engine_type VARCHAR(32) NOT NULL,
    query_type VARCHAR(64),
    query_text TEXT,
    query_params JSONB,
    execution_time_ms BIGINT,
    result_count INT DEFAULT 0,
    status VARCHAR(24) DEFAULT 'EXECUTING',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_operation_log (
    id BIGSERIAL PRIMARY KEY,
    log_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32),
    institution_id BIGINT,
    user_id BIGINT REFERENCES sys_user(id),
    username VARCHAR(64),
    operation_type VARCHAR(64) NOT NULL,
    operation_module VARCHAR(64),
    operation_desc VARCHAR(512),
    target_type VARCHAR(64),
    target_id VARCHAR(128),
    request_params JSONB,
    response_result JSONB,
    ip_address VARCHAR(64),
    user_agent VARCHAR(255),
    execution_time_ms BIGINT,
    success BOOLEAN DEFAULT true,
    error_message TEXT,
    prev_log_id VARCHAR(64),
    chain_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_operation_log_created_at ON audit_operation_log(created_at DESC);

CREATE TABLE IF NOT EXISTS audit_login_log (
    id BIGSERIAL PRIMARY KEY,
    log_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32),
    ip_address VARCHAR(64),
    user_agent VARCHAR(255),
    login_type VARCHAR(32),
    success BOOLEAN DEFAULT true,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quality_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_code VARCHAR(64) NOT NULL UNIQUE,
    rule_name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    rule_expression TEXT,
    config_json JSONB,
    description VARCHAR(512),
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quality_check_result (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES risk_ingest_batch(id),
    rule_code VARCHAR(64) NOT NULL REFERENCES quality_rule(rule_code),
    rule_version VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    source_record_id VARCHAR(128),
    evidence_json JSONB NOT NULL,
    status VARCHAR(24) DEFAULT 'OPEN',
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch_quality_report (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL UNIQUE REFERENCES risk_ingest_batch(id),
    bank_code VARCHAR(32) NOT NULL,
    total_records BIGINT DEFAULT 0,
    passed_count BIGINT DEFAULT 0,
    failed_count BIGINT DEFAULT 0,
    warning_count BIGINT DEFAULT 0,
    critical_errors JSONB,
    summary JSONB,
    overall_status VARCHAR(24) DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quality_issue_ticket (
    id BIGSERIAL PRIMARY KEY,
    ticket_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    batch_id BIGINT REFERENCES risk_ingest_batch(id),
    issue_type VARCHAR(64) NOT NULL,
    issue_code VARCHAR(64),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    affected_count INT DEFAULT 0,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(24) DEFAULT 'OPEN',
    assignee VARCHAR(64),
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMPTZ,
    resolution_method VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_registry (
    id BIGSERIAL PRIMARY KEY,
    model_id VARCHAR(64) NOT NULL UNIQUE,
    model_name VARCHAR(128) NOT NULL,
    model_version VARCHAR(32) NOT NULL,
    model_type VARCHAR(64),
    framework VARCHAR(64),
    model_path VARCHAR(255),
    model_hash CHAR(64),
    input_features JSONB,
    output_schema JSONB,
    performance_metrics JSONB,
    training_data_hash CHAR(64),
    status VARCHAR(24) DEFAULT 'REGISTERED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS model_deployment (
    id BIGSERIAL PRIMARY KEY,
    deployment_id VARCHAR(64) NOT NULL UNIQUE,
    model_id VARCHAR(64) NOT NULL REFERENCES model_registry(model_id),
    model_version VARCHAR(32) NOT NULL,
    bank_code VARCHAR(32),
    endpoint VARCHAR(255),
    status VARCHAR(24) DEFAULT 'DEPLOYING',
    deployed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS algorithm_registry (
    id BIGSERIAL PRIMARY KEY,
    algorithm_id VARCHAR(64) NOT NULL UNIQUE,
    algorithm_name VARCHAR(128) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64),
    input_schema JSONB,
    output_schema JSONB,
    endpoint VARCHAR(255),
    health_check_url VARCHAR(255),
    auth_token_ref VARCHAR(128),
    rollback_version VARCHAR(32),
    status VARCHAR(24) DEFAULT 'REGISTERED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS execution_package (
    id BIGSERIAL PRIMARY KEY,
    package_id VARCHAR(64) NOT NULL UNIQUE,
    package_sha256 CHAR(64) NOT NULL,
    bank_code VARCHAR(32),
    scenario_code VARCHAR(64),
    scenario_version VARCHAR(32),
    config_definition JSONB,
    algorithm_bindings JSONB,
    rules_content JSONB,
    model_metadata JSONB,
    manifest JSONB,
    builder_image_digest VARCHAR(128),
    source_date_epoch TIMESTAMPTZ,
    sbom_sha256 CHAR(64),
    signature_key_id VARCHAR(64),
    detached_signature TEXT,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS risk_config_key_definition (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL UNIQUE,
    key_type VARCHAR(32) NOT NULL,
    override_mode VARCHAR(32) DEFAULT 'REPLACE',
    governance_operator VARCHAR(32),
    allowed_scopes TEXT[],
    validation_rule JSONB,
    default_value TEXT,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_scenario_override (
    id BIGSERIAL PRIMARY KEY,
    override_id VARCHAR(64) NOT NULL UNIQUE,
    scenario_code VARCHAR(64) NOT NULL,
    bank_code VARCHAR(32),
    scope_type VARCHAR(32) NOT NULL,
    scope_key VARCHAR(64),
    override_type VARCHAR(32) NOT NULL,
    target_path VARCHAR(255),
    override_value JSONB,
    override_operator VARCHAR(16),
    priority INTEGER DEFAULT 0,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_scenario_override_version (
    id BIGSERIAL PRIMARY KEY,
    override_id VARCHAR(64) NOT NULL REFERENCES risk_scenario_override(override_id),
    version VARCHAR(32) NOT NULL,
    override_value JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_override_version ON risk_scenario_override_version(override_id, version);

CREATE TABLE IF NOT EXISTS risk_config_resolution_audit (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    resolve_context JSONB NOT NULL,
    candidate_rules JSONB,
    hit_reasons JSONB,
    source_hierarchy JSONB,
    resolved_snapshot_id VARCHAR(64),
    resolution_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_algorithm_route_rule (
    id BIGSERIAL PRIMARY KEY,
    route_rule_id VARCHAR(64) NOT NULL UNIQUE,
    scenario_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_key VARCHAR(64),
    selector_json JSONB,
    priority INTEGER DEFAULT 0,
    service_mode VARCHAR(16) DEFAULT 'BATCH',
    required_semantic_codes TEXT[],
    minimum_data_readiness JSONB,
    algorithm_version_id VARCHAR(64),
    model_version_id VARCHAR(64),
    parameter_set_version VARCHAR(32),
    fallback_route_rule_id VARCHAR(64),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    traffic_percentage INTEGER DEFAULT 100,
    stable_hash_key VARCHAR(64),
    status VARCHAR(24) DEFAULT 'DRAFT',
    approval_record JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_config_activation (
    id BIGSERIAL PRIMARY KEY,
    activation_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    activation_type VARCHAR(32) NOT NULL,
    traffic_percentage INTEGER DEFAULT 100,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    rollback_target_id VARCHAR(64),
    status VARCHAR(24) DEFAULT 'PENDING',
    activated_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS risk_org_unit (
    id BIGSERIAL PRIMARY KEY,
    org_unit_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    parent_org_unit_id VARCHAR(64),
    org_level VARCHAR(32) NOT NULL,
    org_name VARCHAR(128) NOT NULL,
    org_code VARCHAR(64) NOT NULL,
    status VARCHAR(24) DEFAULT 'ACTIVE',
    version VARCHAR(32) DEFAULT '1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_org_closure (
    id BIGSERIAL PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    ancestor_org_unit_id VARCHAR(64) NOT NULL,
    descendant_org_unit_id VARCHAR(64) NOT NULL,
    depth INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_workflow_role_binding (
    id BIGSERIAL PRIMARY KEY,
    binding_id VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    workflow_type VARCHAR(64) NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    local_position_codes TEXT[],
    description VARCHAR(512),
    status VARCHAR(24) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS structured_projection_definition (
    id BIGSERIAL PRIMARY KEY,
    projection_id VARCHAR(64) NOT NULL UNIQUE,
    struct_schema_id VARCHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    projection_name VARCHAR(128) NOT NULL,
    target_table VARCHAR(128),
    projection_columns JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sys_role (role_code, role_name, description, menus, permissions) VALUES
('sadmin', '超级管理员', '系统超级管理员，拥有所有权限', 
 ARRAY['overview', 'system', 'data', 'case', 'regulator', 'graph', 'policy'],
 ARRAY['*:*:*']),
('badmin', '银行管理员', '银行端管理员，管理本行数据和案例', 
 ARRAY['overview', 'data', 'case', 'graph', 'regulator'],
 ARRAY['data:*', 'case:*', 'graph:*', 'regulator:view']),
('madmin', '监管管理员', '监管端管理员，管理共享和下发', 
 ARRAY['overview', 'regulator', 'sharing', 'bank-access'],
 ARRAY['regulator:*', 'sharing:*', 'bank-access:*']);

INSERT INTO sys_user (username, password, nickname, email, phone, role_code, bank_code, status) VALUES
('sadmin', '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjzqAKL9xL5jvMFVdNJHvGCgTq/VEq', '超级管理员', 'sadmin@bankgraph.com', '13800138000', 'sadmin', NULL, true),
('badmin', '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjzqAKL9xL5jvMFVdNJHvGCgTq/VEq', '银行管理员', 'badmin@bankgraph.com', '13800138001', 'badmin', 'BANK001', true),
('madmin', '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjzqAKL9xL5jvMFVdNJHvGCgTq/VEq', '监管管理员', 'madmin@bankgraph.com', '13800138002', 'madmin', NULL, true);

INSERT INTO sys_dict_type (dict_type, dict_name, description) VALUES
('case_status', '案例状态', '风险案例状态'),
('case_source', '案例来源', '案例数据来源'),
('risk_level', '风险等级', '风险等级'),
('signal_type', '信号类型', '风险信号类型'),
('signal_status', '信号状态', '信号状态'),
('review_result', '复核结果', '案例复核结果');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label) VALUES
('case_status', 'DRAFT', '草稿'),
('case_status', 'IN_REVIEW', '复核中'),
('case_status', 'PENDING_APPROVAL', '待审批'),
('case_status', 'APPROVED', '已通过'),
('case_status', 'REJECTED', '已驳回'),
('case_status', 'CLOSED', '已结案'),
('case_status', 'REOPENED', '已重开'),
('case_source', 'STRUCT_SUSPECTED', '结构化存疑'),
('case_source', 'TEXT_CASE', '文本案例'),
('case_source', 'MANUAL', '手动创建'),
('case_source', 'CROSS_BANK', '跨行案例'),
('risk_level', 'LOW', '低风险'),
('risk_level', 'MEDIUM', '中风险'),
('risk_level', 'HIGH', '高风险'),
('risk_level', 'CRITICAL', '严重风险'),
('signal_type', 'TRANSACTION', '交易信号'),
('signal_type', 'ACCOUNT', '账户信号'),
('signal_type', 'SUBJECT', '主体信号'),
('signal_type', 'GANG', '团伙信号'),
('signal_status', 'CANDIDATE', '候选'),
('signal_status', 'MERGED', '已合并'),
('signal_status', 'DISMISSED', '已驳回'),
('signal_status', 'SUPERSEDED', '已取代'),
('review_result', 'PASS', '通过'),
('review_result', 'REJECT', '驳回'),
('review_result', 'ESCALATE', '升级'),
('review_result', 'RETURN', '退回');

INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible) VALUES
(0, '平台总览', '/overview', 'views/overview/index.vue', 'el-icon-home', 1, 'MENU', 'overview:view', true),
(0, '系统管理', '/system', 'views/system/index.vue', 'el-icon-setting', 2, 'MENU', 'system:view', true),
(2, '用户管理', '/system/users', 'views/system/users.vue', 'el-icon-user', 1, 'MENU', 'system:user:view', true),
(2, '角色管理', '/system/roles', 'views/system/roles.vue', 'el-icon-user-solid', 2, 'MENU', 'system:role:view', true),
(2, '菜单管理', '/system/menus', 'views/system/menus.vue', 'el-icon-menu', 3, 'MENU', 'system:menu:view', true),
(2, '字典管理', '/system/dict', 'views/system/dict.vue', 'el-icon-collection-tag', 4, 'MENU', 'system:dict:view', true),
(0, '数据接入', '/data', 'views/data/index.vue', 'el-icon-data-line', 3, 'MENU', 'data:view', true),
(6, 'Schema管理', '/data/schema', 'views/data/schema.vue', 'el-icon-files', 1, 'MENU', 'data:schema:view', true),
(6, '数据批次', '/data/batches', 'views/data/batches.vue', 'el-icon-data-analysis', 2, 'MENU', 'data:batch:view', true),
(6, '隔离区', '/data/quarantine', 'views/data/quarantine.vue', 'el-icon-warning', 3, 'MENU', 'data:quarantine:view', true),
(0, '案例管理', '/case', 'views/case/index.vue', 'el-icon-folder-opened', 4, 'MENU', 'case:view', true),
(10, '案例列表', '/case/list', 'views/case/list.vue', 'el-icon-list', 1, 'MENU', 'case:list:view', true),
(10, '案例详情', '/case/detail', 'views/case/detail.vue', 'el-icon-document', 2, 'MENU', 'case:detail:view', true),
(10, '案例复核', '/case/review', 'views/case/review.vue', 'el-icon-check', 3, 'MENU', 'case:review:view', true),
(10, '案例审批', '/case/approval', 'views/case/approval.vue', 'el-icon-circle-check', 4, 'MENU', 'case:approval:view', true),
(0, '图谱分析', '/graph', 'views/graph/index.vue', 'el-icon-connection', 5, 'MENU', 'graph:view', true),
(14, '图谱可视化', '/graph/visualize', 'views/graph/visualize.vue', 'el-icon-picture', 1, 'MENU', 'graph:visualize:view', true),
(14, '线索分析', '/graph/clue', 'views/graph/clue.vue', 'el-icon-search', 2, 'MENU', 'graph:clue:view', true),
(14, '图快照', '/graph/snapshot', 'views/graph/snapshot.vue', 'el-icon-camera', 3, 'MENU', 'graph:snapshot:view', true),
(0, '监管协同', '/regulator', 'views/regulator/index.vue', 'el-icon-office-building', 6, 'MENU', 'regulator:view', true),
(18, '共享中心', '/regulator/sharing', 'views/regulator/sharing.vue', 'el-icon-share', 1, 'MENU', 'regulator:sharing:view', true),
(18, '待激活', '/regulator/pending', 'views/regulator/pending.vue', 'el-icon-clock', 2, 'MENU', 'regulator:pending:view', true),
(18, '银行接入', '/regulator/bank', 'views/regulator/bank.vue', 'el-icon-link', 3, 'MENU', 'regulator:bank:view', true),
(18, '数据交换', '/regulator/exchange', 'views/regulator/exchange.vue', 'el-icon-swap', 4, 'MENU', 'regulator:exchange:view', true),
(0, '策略配置', '/policy', 'views/policy/index.vue', 'el-icon-s-tools', 7, 'MENU', 'policy:view', true),
(22, '场景管理', '/policy/scenario', 'views/policy/scenario.vue', 'el-icon-s-marketing', 1, 'MENU', 'policy:scenario:view', true),
(22, '规则管理', '/policy/rule', 'views/policy/rule.vue', 'el-icon-s-check', 2, 'MENU', 'policy:rule:view', true);
