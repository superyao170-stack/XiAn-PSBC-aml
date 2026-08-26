-- Business scenario and recognition channel are different dimensions.
-- Scenarios come from risk_scenario_template; case type is fixed by the
-- recognition task that created the case.

INSERT INTO risk_scenario_template
    (template_id,template_name,scenario_code,description,config_definition,
     algorithm_bindings,default_thresholds,bank_code,status,version)
VALUES
    ('RST-AML-DEFAULT','反洗钱','AML','平台内置反洗钱业务场景',
     '{}'::jsonb,'[]'::jsonb,'{}'::jsonb,NULL,'ACTIVE','1.0.0'),
    ('RST-ANTI-FRAUD-DEFAULT','反欺诈','ANTI_FRAUD','平台内置反欺诈业务场景',
     '{}'::jsonb,'[]'::jsonb,'{}'::jsonb,NULL,'ACTIVE','1.0.0')
ON CONFLICT (template_id) DO UPDATE SET
    template_name=EXCLUDED.template_name,
    scenario_code=EXCLUDED.scenario_code,
    description=EXCLUDED.description,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

-- Deployment smoke metadata is not a selectable business scenario.
UPDATE risk_scenario_template
SET status='RETIRED',updated_at=CURRENT_TIMESTAMP
WHERE scenario_code LIKE '%E2E%'
  AND status='ACTIVE';

INSERT INTO case_framework_option_metadata
    (field_code,field_name,option_code,option_label,option_value,sort_order,
     source_ref,description)
VALUES
    ('caseType','案例类型','STRUCTURED_CASE','结构化案例','STRUCTURED_CASE',10,
     '识别任务类型','由结构化案例识别或存疑交易人工归并产生'),
    ('caseType','案例类型','UNSTRUCTURED_CASE','非结构化案例','UNSTRUCTURED_CASE',20,
     '识别任务类型','由非结构化案例识别产生')
ON CONFLICT (field_code,option_code) DO UPDATE SET
    field_name=EXCLUDED.field_name,
    option_label=EXCLUDED.option_label,
    option_value=EXCLUDED.option_value,
    sort_order=EXCLUDED.sort_order,
    source_ref=EXCLUDED.source_ref,
    description=EXCLUDED.description,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

-- Correct technical task codes and historical AML test aliases that leaked
-- into the case business-scenario field.
UPDATE cf_risk_case
SET scenario_code='AML',updated_at=CURRENT_TIMESTAMP
WHERE deleted=false
  AND (scenario_code IN ('UNSTRUCTURED_CASE','TEXT_ILLEGAL_CASE')
       OR scenario_code LIKE 'AML[_]%');

UPDATE risk_signal
SET scenario_code='AML'
WHERE scenario_code IN ('UNSTRUCTURED_CASE','TEXT_ILLEGAL_CASE')
   OR scenario_code LIKE 'AML[_]%';

UPDATE analysis_job
SET scenario_code='AML'
WHERE scenario_code IN ('UNSTRUCTURED_CASE','TEXT_ILLEGAL_CASE')
   OR scenario_code LIKE 'AML[_]%';

UPDATE cf_risk_case
SET case_type=CASE
        WHEN case_source='TEXT_CASE' THEN 'UNSTRUCTURED_CASE'
        ELSE 'STRUCTURED_CASE'
    END,
    updated_at=CURRENT_TIMESTAMP
WHERE deleted=false
  AND case_type IS DISTINCT FROM CASE
        WHEN case_source='TEXT_CASE' THEN 'UNSTRUCTURED_CASE'
        ELSE 'STRUCTURED_CASE'
      END;
