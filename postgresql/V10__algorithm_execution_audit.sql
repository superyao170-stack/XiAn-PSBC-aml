CREATE TABLE IF NOT EXISTS algorithm_execution_log (
    id BIGSERIAL PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL UNIQUE,
    job_id VARCHAR(64) NOT NULL REFERENCES analysis_job(job_id),
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    algorithm_id VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    endpoint VARCHAR(255),
    request_count INT NOT NULL DEFAULT 0,
    result_count INT NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    status VARCHAR(24) NOT NULL,
    error_message TEXT,
    request_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_algorithm_execution_job
    ON algorithm_execution_log(job_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_algorithm_execution_route
    ON algorithm_execution_log(bank_code, scenario_code, algorithm_id, created_at DESC);

UPDATE sys_role
SET menus = CASE
      WHEN NOT ('policy'=ANY(menus)) THEN array_append(menus,'policy')
      ELSE menus END,
    permissions = CASE
      WHEN role_code='badmin' AND NOT ('policy:view'=ANY(permissions))
        THEN permissions || ARRAY['policy:view','policy:scenario:bind','policy:execution:view']
      WHEN role_code='madmin' AND NOT ('policy:view'=ANY(permissions))
        THEN permissions || ARRAY['policy:view','policy:algorithm:manage','policy:scenario:manage','policy:execution:view']
      ELSE permissions END
WHERE role_code IN ('badmin','madmin');

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT id,'算法中心','/policy/algorithm','views/policy/algorithm.vue',
       'el-icon-cpu',3,'MENU','policy:algorithm:view',true
FROM sys_menu p
WHERE p.path='/policy'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/policy/algorithm');

UPDATE sys_menu SET menu_name='策略与算法' WHERE path='/policy';
UPDATE sys_menu SET menu_name='场景管理' WHERE path='/policy/scenario';
UPDATE sys_menu SET menu_name='规则管理' WHERE path='/policy/rule';
