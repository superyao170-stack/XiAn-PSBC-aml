
ALTER TABLE sys_user ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_user ALTER COLUMN updated_at TYPE TIMESTAMP;
ALTER TABLE sys_user ALTER COLUMN last_login_time TYPE TIMESTAMP;

ALTER TABLE sys_role ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_role ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE sys_menu ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_menu ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE sys_dict_type ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_dict_type ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE sys_dict_data ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_dict_data ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE sys_config ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE sys_config ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE struct_schema ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE struct_schema ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE struct_schema_version ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE struct_schema_binding ALTER COLUMN effective_start TYPE TIMESTAMP;
ALTER TABLE struct_schema_binding ALTER COLUMN effective_end TYPE TIMESTAMP;
ALTER TABLE struct_schema_binding ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE struct_schema_binding ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE structured_record_head ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE structured_record_head ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE structured_business_record ALTER COLUMN occurred_at_tz TYPE TIMESTAMP;
ALTER TABLE structured_business_record ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE structured_business_record ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_ingest_batch ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_ingest_batch ALTER COLUMN published_at TYPE TIMESTAMP;

ALTER TABLE risk_transaction_materialized ALTER COLUMN occurred_at TYPE TIMESTAMP;
ALTER TABLE risk_transaction_materialized ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_signal ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE text_risk_signal ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE entity_master ALTER COLUMN first_seen_at TYPE TIMESTAMP;
ALTER TABLE entity_master ALTER COLUMN last_seen_at TYPE TIMESTAMP;
ALTER TABLE entity_master ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE entity_master ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE entity_signal_ref ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE cf_risk_case ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE cf_risk_case ALTER COLUMN updated_at TYPE TIMESTAMP;
ALTER TABLE cf_risk_case ALTER COLUMN closed_at TYPE TIMESTAMP;

ALTER TABLE cf_risk_event ALTER COLUMN event_time TYPE TIMESTAMP;
ALTER TABLE cf_risk_event ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE cf_risk_event ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE case_signal_rel ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE case_review_record ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE case_approval_record ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE workflow_instance ALTER COLUMN completed_at TYPE TIMESTAMP;
ALTER TABLE workflow_instance ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE workflow_instance ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE workflow_step ALTER COLUMN started_at TYPE TIMESTAMP;
ALTER TABLE workflow_step ALTER COLUMN completed_at TYPE TIMESTAMP;
ALTER TABLE workflow_step ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_clue ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_scenario_template ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_scenario_template ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_scenario_binding ALTER COLUMN effective_start TYPE TIMESTAMP;
ALTER TABLE risk_scenario_binding ALTER COLUMN effective_end TYPE TIMESTAMP;
ALTER TABLE risk_scenario_binding ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_deployment_config_snapshot ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_effective_config_snapshot ALTER COLUMN effective_at TYPE TIMESTAMP;
ALTER TABLE risk_effective_config_snapshot ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_regulatory_exemption ALTER COLUMN effective_start TYPE TIMESTAMP;
ALTER TABLE risk_regulatory_exemption ALTER COLUMN effective_end TYPE TIMESTAMP;
ALTER TABLE risk_regulatory_exemption ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_regulatory_exemption ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE shared_schema ALTER COLUMN published_at TYPE TIMESTAMP;
ALTER TABLE shared_schema ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE shared_clue ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE pending_activation ALTER COLUMN activated_at TYPE TIMESTAMP;
ALTER TABLE pending_activation ALTER COLUMN rejected_at TYPE TIMESTAMP;
ALTER TABLE pending_activation ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE pending_activation ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE report_record ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE report_record ALTER COLUMN published_at TYPE TIMESTAMP;

ALTER TABLE graph_engine_config ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE graph_engine_config ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE graph_snapshot ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE graph_snapshot ALTER COLUMN completed_at TYPE TIMESTAMP;

ALTER TABLE graph_query_log ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE audit_operation_log ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE audit_login_log ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE quality_rule ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE quality_rule ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE quality_check_result ALTER COLUMN resolved_at TYPE TIMESTAMP;
ALTER TABLE quality_check_result ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE batch_quality_report ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE quality_issue_ticket ALTER COLUMN resolved_at TYPE TIMESTAMP;
ALTER TABLE quality_issue_ticket ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE quality_issue_ticket ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE model_registry ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE model_deployment ALTER COLUMN deployed_at TYPE TIMESTAMP;
ALTER TABLE model_deployment ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE algorithm_registry ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE algorithm_registry ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE execution_package ALTER COLUMN source_date_epoch TYPE TIMESTAMP;
ALTER TABLE execution_package ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_config_key_definition ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_config_key_definition ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_scenario_override ALTER COLUMN effective_from TYPE TIMESTAMP;
ALTER TABLE risk_scenario_override ALTER COLUMN effective_to TYPE TIMESTAMP;
ALTER TABLE risk_scenario_override ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_scenario_override ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_scenario_override_version ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_config_resolution_audit ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_algorithm_route_rule ALTER COLUMN effective_from TYPE TIMESTAMP;
ALTER TABLE risk_algorithm_route_rule ALTER COLUMN effective_to TYPE TIMESTAMP;
ALTER TABLE risk_algorithm_route_rule ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_algorithm_route_rule ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_config_activation ALTER COLUMN effective_from TYPE TIMESTAMP;
ALTER TABLE risk_config_activation ALTER COLUMN effective_to TYPE TIMESTAMP;
ALTER TABLE risk_config_activation ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_config_activation ALTER COLUMN activated_at TYPE TIMESTAMP;

ALTER TABLE risk_org_unit ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_org_unit ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE risk_org_closure ALTER COLUMN created_at TYPE TIMESTAMP;

ALTER TABLE risk_workflow_role_binding ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE risk_workflow_role_binding ALTER COLUMN updated_at TYPE TIMESTAMP;

ALTER TABLE structured_projection_definition ALTER COLUMN created_at TYPE TIMESTAMP;
ALTER TABLE structured_projection_definition ALTER COLUMN updated_at TYPE TIMESTAMP;
