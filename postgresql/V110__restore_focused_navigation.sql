-- Keep navigation aligned with the focused AML/anti-fraud product surface.
UPDATE sys_menu
SET visible = false, updated_at = CURRENT_TIMESTAMP
WHERE path LIKE '/data%'
   OR path LIKE '/policy%'
   OR path LIKE '/knowledge%'
   OR path LIKE '/regulator%'
   OR path IN ('/analysis/identify', '/analysis/unstructured', '/analysis/pattern',
               '/case/intelligence', '/case/replay', '/system/users', '/system/dict');

UPDATE sys_menu
SET menu_name = CASE path
        WHEN '/overview' THEN '平台总览'
        WHEN '/analysis' THEN '案例识别'
        WHEN '/case' THEN '案例管理'
        WHEN '/graph' THEN '线索分析'
        WHEN '/system' THEN '系统管理'
        ELSE menu_name
    END,
    visible = true,
    sort_order = CASE path
        WHEN '/overview' THEN 1
        WHEN '/analysis' THEN 2
        WHEN '/case' THEN 3
        WHEN '/graph' THEN 4
        WHEN '/system' THEN 99
        ELSE sort_order
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN ('/overview', '/analysis', '/case', '/graph', '/system');

UPDATE sys_menu
SET path = '/analysis/aml', menu_name = '反洗钱',
    component = 'views/analysis/PipelineWorkbench.vue', sort_order = 1,
    visible = true, updated_at = CURRENT_TIMESTAMP
WHERE path = '/analysis/structured';

INSERT INTO sys_menu(parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT parent.id, '反欺诈', '/analysis/fraud', 'views/analysis/PipelineWorkbench.vue',
       'el-icon-data-analysis', 2, 'MENU', 'analysis:structured:view', true
FROM sys_menu parent
WHERE parent.path = '/analysis'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/analysis/fraud');

UPDATE sys_menu
SET menu_name = CASE path
        WHEN '/graph/visualize' THEN '全景图谱'
        WHEN '/graph/hidden-risk' THEN '隐蔽风险挖掘'
        WHEN '/graph/association-clues' THEN '关联线索分析'
        WHEN '/system/roles' THEN '角色管理'
        WHEN '/system/menus' THEN '菜单管理'
        ELSE menu_name
    END,
    sort_order = CASE path
        WHEN '/graph/visualize' THEN 1
        WHEN '/graph/hidden-risk' THEN 2
        WHEN '/graph/association-clues' THEN 3
        WHEN '/system/roles' THEN 1
        WHEN '/system/menus' THEN 2
        ELSE sort_order
    END,
    visible = true,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN ('/graph/visualize', '/graph/hidden-risk', '/graph/association-clues',
               '/system/roles', '/system/menus');
