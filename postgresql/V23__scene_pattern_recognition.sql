ALTER TABLE structured_pattern_label ADD COLUMN IF NOT EXISTS source VARCHAR(24) NOT NULL DEFAULT 'UPLOADED';
ALTER TABLE structured_pattern_label ADD COLUMN IF NOT EXISTS job_id VARCHAR(64) REFERENCES analysis_job(job_id) ON DELETE SET NULL;
ALTER TABLE structured_pattern_label ADD COLUMN IF NOT EXISTS component_id VARCHAR(64);
ALTER TABLE structured_pattern_label ADD COLUMN IF NOT EXISTS transaction_count BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_structured_pattern_job_component
    ON structured_pattern_label(job_id,component_id)
    WHERE job_id IS NOT NULL AND component_id IS NOT NULL;

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT id,'场景模式识别','/analysis/pattern','views/analysis/pattern.vue','el-icon-share',1,'MENU','analysis:pattern:view',true
FROM sys_menu WHERE path='/analysis'
  AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/analysis/pattern');
