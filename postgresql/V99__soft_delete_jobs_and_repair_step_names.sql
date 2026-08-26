ALTER TABLE analysis_job
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE analysis_job
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE analysis_job
    ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_analysis_job_visible_list
    ON analysis_job (bank_code, job_type, status, created_at DESC)
    WHERE deleted = FALSE;

UPDATE analysis_job_step
SET step_name = CASE step_type
    WHEN 'S1' THEN '上下文聚合'
    WHEN 'S2' THEN 'Schema校验'
    WHEN 'S3' THEN '质量门禁'
    WHEN 'S4' THEN '主体归一化'
    WHEN 'S5' THEN '事件抽取'
    WHEN 'S6' THEN '关系构建'
    WHEN 'S7' THEN '案例草稿'
    WHEN 'S8' THEN 'GraphWrite'
    WHEN 'S9' THEN '版本固化'
    WHEN 'I1' THEN '批次装载'
    WHEN 'I2' THEN '规则匹配'
    WHEN 'I3' THEN '模型评分'
    WHEN 'I4' THEN '图特征增强'
    WHEN 'I5' THEN '信号归并'
    WHEN 'I6' THEN '存疑结果发布'
    WHEN 'P00' THEN 'CanonicalText'
    WHEN 'P01' THEN 'NormalizedText'
    WHEN 'P02a' THEN 'EntityNodes'
    WHEN 'P02b' THEN 'EntityLinking'
    WHEN 'P03' THEN 'EventNodes'
    WHEN 'P04' THEN 'EvidenceNodes'
    WHEN 'P05a' THEN 'CaseNode'
    WHEN 'P05b' THEN 'CaseMerge'
    WHEN 'P06' THEN 'CaseEdges'
    WHEN 'P07' THEN 'EvidenceEdges'
    WHEN 'P08' THEN 'EventEdges'
    WHEN 'P11' THEN 'GraphPersist'
    ELSE step_name
END
WHERE step_type IN (
    'S1','S2','S3','S4','S5','S6','S7','S8','S9',
    'I1','I2','I3','I4','I5','I6',
    'P00','P01','P02a','P02b','P03','P04','P05a','P05b','P06','P07','P08','P11'
);
