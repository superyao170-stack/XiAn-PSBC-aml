INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'2.0.2',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '实体质量门按客户、账户类别分别检查；材料明确包含姓名或多人主体而模型遗漏客户时自动采用规则实体结果。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"entityCategoryQualityGate":true}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION'
  AND algorithm_version='2.0.1'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

UPDATE algorithm_definition
SET current_version='2.0.2',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION';

UPDATE algorithm_assembly
SET algorithm_version='2.0.2',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-HYBRID-AML-DEFAULT';
