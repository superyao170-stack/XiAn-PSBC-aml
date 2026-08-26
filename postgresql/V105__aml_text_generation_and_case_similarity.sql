-- Register the Xi'an Postal Savings Bank AML text-generation workflow and
-- heterogeneous case-graph GED similarity algorithm as first-class assets.

INSERT INTO algorithm_definition
    (algorithm_id,algorithm_name,algorithm_type,task_type,description,current_version,status,created_by)
VALUES
    ('AML_ANALYSIS_TEXT_GENERATION','反洗钱案例分析文本生成','LLM_PIPELINE','CASE_ANALYSIS',
     '基于结构化案例事实、客户交易特征、风险事件知识与分段审查重写流程，生成可复核的资金流转分析和疑点分析文本。',
     '0.1.0','ACTIVE','system'),
    ('CASE_GRAPH_SIMILARITY_GED','案例图谱相似度匹配','GRAPH_MATCHING','CASE_ANALYSIS',
     '采用事件锚点、人员行为结构、异构图近似编辑距离和事件类型向量诊断，对新增案例与历史案例执行全量匹配并输出Top-5命中子图。',
     '0.1.0','ACTIVE','system')
ON CONFLICT (algorithm_id) DO UPDATE SET
    algorithm_name=EXCLUDED.algorithm_name,
    algorithm_type=EXCLUDED.algorithm_type,
    task_type=EXCLUDED.task_type,
    description=EXCLUDED.description,
    current_version=EXCLUDED.current_version,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,source_root,source_file,inference_config,
     status,verification_status,verification_summary,last_verified_at,changelog,
     created_by,published_at)
VALUES
    ('AML_ANALYSIS_TEXT_GENERATION','0.1.0','PYTHON_LLM',
     '{"type":"object","required":["basicInfo","customers","transactionFeatures"],"properties":{"basicInfo":{"type":"object"},"customers":{"type":"array"},"transactionFeatures":{"type":"object"},"knowledgeBase":{"type":"object"},"modelConfig":{"type":"object"}}}'::jsonb,
     '{"type":"object","required":["analysisText1","analysisText2","paragraphs","review"]}'::jsonb,
     '{"type":"object","properties":{"model":{"type":"string"},"baseUrl":{"type":"string"},"timeoutSeconds":{"type":"number"},"temperature":{"type":"number"}}}'::jsonb,
     '{"model":"deepseek-chat","timeoutSeconds":90,"temperature":0.1}'::jsonb,
     './worker/aml-intelligence','./worker/aml-intelligence/aml_analysis_workflow/workflow.py',
     '{"requestMode":"CASE_FACTS","llmConfiguration":"SOURCE_CONSTANTS","outputUsage":"reviewable_case_analysis"}'::jsonb,
     'ACTIVE','VERIFIED','原始工作流、提示词、输入校验、审查重写和统一Worker适配已迁移并通过本地测试',
     CURRENT_TIMESTAMP,'从 xian-postal-savings-bank 迁入 aml_analysis_workflow 完整实现','system',CURRENT_TIMESTAMP),
    ('CASE_GRAPH_SIMILARITY_GED','0.1.0','PYTHON',
     '{"type":"object","required":["queryCase","historyCases"],"properties":{"queryCase":{"type":"object"},"historyCases":{"type":"array","minItems":1},"parameters":{"type":"object"}}}'::jsonb,
     '{"type":"object","required":["query_case","results","similarity_metrics"],"properties":{"results":{"type":"array","maxItems":5}}}'::jsonb,
     '{"type":"object","properties":{"minSharedEventTypes":{"type":"integer","minimum":0},"minEventOverlap":{"type":"number","minimum":0,"maximum":1},"minEventCountRatio":{"type":"number","minimum":0,"maximum":1}}}'::jsonb,
     '{"minSharedEventTypes":1,"minEventOverlap":0.1,"minEventCountRatio":0.2}'::jsonb,
     './worker/aml-intelligence','./worker/aml-intelligence/case_similarity.py',
     '{"requestMode":"CASE_GRAPH_SET","rankingMetric":"normalized_ged_ascending","topK":5}'::jsonb,
     'ACTIVE','VERIFIED','原始异构图GED实现、无NumPy回退路径、匹配指标与命中子图输出已迁移并通过本地测试',
     CURRENT_TIMESTAMP,'从 xian-postal-savings-bank 迁入 case_similarity.py 完整实现','system',CURRENT_TIMESTAMP)
ON CONFLICT (algorithm_id,algorithm_version) DO UPDATE SET
    implementation_type=EXCLUDED.implementation_type,
    input_schema=EXCLUDED.input_schema,
    output_schema=EXCLUDED.output_schema,
    parameter_schema=EXCLUDED.parameter_schema,
    default_parameters=EXCLUDED.default_parameters,
    source_root=EXCLUDED.source_root,
    source_file=EXCLUDED.source_file,
    inference_config=EXCLUDED.inference_config,
    status='ACTIVE',
    verification_status='VERIFIED',
    verification_summary=EXCLUDED.verification_summary,
    last_verified_at=CURRENT_TIMESTAMP,
    changelog=EXCLUDED.changelog,
    published_at=COALESCE(algorithm_version_registry.published_at,CURRENT_TIMESTAMP);

INSERT INTO worker_registry
    (worker_id,worker_version,worker_name,task_type,worker_type,execution_mode,
     package_path,entrypoint,runtime_config,resource_requirements,
     input_contract_version,output_contract_version,status,created_by)
VALUES
    ('AML_INTELLIGENCE_WORKER','1.0.0','AML文本生成与相似案例Worker','CASE_ANALYSIS',
     'PYTHON_LLM','BATCH','./worker/aml-intelligence','app.py',
     '{"transport":"json-stdio","llmConfiguration":"SOURCE_CONSTANTS","algorithms":["AML_ANALYSIS_TEXT_GENERATION","CASE_GRAPH_SIMILARITY_GED"]}'::jsonb,
     '{"cpu":"2","memory":"4Gi","llm":"text_generation_only"}'::jsonb,
     '1.0','1.0','ACTIVE','system')
ON CONFLICT (worker_id,worker_version) DO UPDATE SET
    worker_name=EXCLUDED.worker_name,
    task_type=EXCLUDED.task_type,
    worker_type=EXCLUDED.worker_type,
    execution_mode=EXCLUDED.execution_mode,
    package_path=EXCLUDED.package_path,
    entrypoint=EXCLUDED.entrypoint,
    runtime_config=EXCLUDED.runtime_config,
    resource_requirements=EXCLUDED.resource_requirements,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO worker_algorithm_capability
    (worker_id,worker_version,algorithm_id,enabled,capability_config)
VALUES
    ('AML_INTELLIGENCE_WORKER','1.0.0','AML_ANALYSIS_TEXT_GENERATION',true,
     '{"action":"text-generation"}'::jsonb),
    ('AML_INTELLIGENCE_WORKER','1.0.0','CASE_GRAPH_SIMILARITY_GED',true,
     '{"action":"case-similarity","topK":5}'::jsonb)
ON CONFLICT (worker_id,worker_version,algorithm_id) DO UPDATE SET
    enabled=true,
    capability_config=EXCLUDED.capability_config,
    updated_at=CURRENT_TIMESTAMP;

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT p.id,'AML智能分析','/case/intelligence','views/case/CaseIntelligence.vue',
       'el-icon-data-analysis',5,'MENU','case:intelligence:view',true
FROM sys_menu p
WHERE p.path='/case'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path='/case/intelligence');
