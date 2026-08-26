UPDATE algorithm_version_registry
SET inference_config =
        COALESCE(inference_config,'{}'::jsonb)
        || '{"deterministicEvidence":true,"deterministicEdges":true,"qualityGate":true,"maximumLlmStages":4}'::jsonb,
    changelog='证据原文锚定和四个关系阶段改为确定性处理；模型输出关键节点为空时触发规则质量门降级。'
WHERE algorithm_id='UNSTRUCTURED_HYBRID_GRAPH_EXTRACTION'
  AND algorithm_version='2.0.0';
