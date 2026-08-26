INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'2.0.1',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '证据—事件关系改用原文包含、日期/账号/金额强锚点及长连续片段匹配，消除通用词导致的关系膨胀。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"evidenceEventMatching":"STRONG_ANCHOR_V2"}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION'
  AND algorithm_version='2.0.0'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'1.0.1',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '规则模式证据—事件关系升级为强锚点匹配，降低长文本中的误关联。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"evidenceEventMatching":"STRONG_ANCHOR_V2"}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_RULE_GRAPH_EXTRACTION'
  AND algorithm_version='1.0.0'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

UPDATE algorithm_definition
SET current_version='2.0.1',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION';

UPDATE algorithm_definition
SET current_version='1.0.1',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_RULE_GRAPH_EXTRACTION';

UPDATE algorithm_assembly
SET algorithm_version='2.0.1',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-HYBRID-AML-DEFAULT';

UPDATE algorithm_assembly
SET algorithm_version='1.0.1',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-RULE-AML-FALLBACK';
