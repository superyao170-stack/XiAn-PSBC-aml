UPDATE analysis_job_step
SET step_name = CASE step_type
    WHEN 'M1' THEN '数据装载'
    WHEN 'M2' THEN '候选模式生成'
    WHEN 'M3' THEN '模式评估'
    WHEN 'M4' THEN '图谱关联'
    WHEN 'M5' THEN '结果发布'
    ELSE step_name
END
WHERE step_type IN ('M1','M2','M3','M4','M5');
