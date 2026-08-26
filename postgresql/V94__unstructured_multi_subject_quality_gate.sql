INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'2.0.3',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '多人主体质量门增加“三名”“等人”等明确人数表述，避免模型遗漏全部客户但保留泛化账户时绕过降级。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"multiSubjectQualityGate":true}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION'
  AND algorithm_version='2.0.2'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

UPDATE algorithm_definition
SET current_version='2.0.3',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION';

UPDATE algorithm_assembly
SET algorithm_version='2.0.3',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-HYBRID-AML-DEFAULT';
