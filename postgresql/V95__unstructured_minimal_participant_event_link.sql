INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'2.0.4',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '明确案件参与人未被事件文本直接引用时，仅连接至上下文最相近的一个代表性事件，防止最终连通性裁剪丢失实体。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"minimalParticipantEventLink":true}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION'
  AND algorithm_version='2.0.3'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

INSERT INTO algorithm_version_registry
    (algorithm_id,algorithm_version,implementation_type,input_schema,output_schema,
     parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
     changelog,status,created_by,created_at,published_at,source_file,source_code,
     inference_config,code_updated_by,code_updated_at,source_root)
SELECT algorithm_id,'1.0.2',implementation_type,input_schema,output_schema,
       parameter_schema,default_parameters,model_id,model_version,code_hash,metrics,
       '规则模式增加未连接参与人的最小代表性事件连接，避免有效主体被最终图裁剪。',
       'ACTIVE','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,source_file,source_code,
       COALESCE(inference_config,'{}'::jsonb)
           || '{"minimalParticipantEventLink":true}'::jsonb,
       'system',CURRENT_TIMESTAMP,source_root
FROM algorithm_version_registry
WHERE algorithm_id='UNSTRUCTURED_RULE_GRAPH_EXTRACTION'
  AND algorithm_version='1.0.1'
ON CONFLICT (algorithm_id,algorithm_version) DO NOTHING;

UPDATE algorithm_definition
SET current_version='2.0.4',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION';

UPDATE algorithm_definition
SET current_version='1.0.2',updated_at=CURRENT_TIMESTAMP
WHERE algorithm_id='UNSTRUCTURED_RULE_GRAPH_EXTRACTION';

UPDATE algorithm_assembly
SET algorithm_version='2.0.4',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-HYBRID-AML-DEFAULT';

UPDATE algorithm_assembly
SET algorithm_version='1.0.2',updated_at=CURRENT_TIMESTAMP
WHERE assembly_id='ASM-UNSTRUCTURED-RULE-AML-FALLBACK';
