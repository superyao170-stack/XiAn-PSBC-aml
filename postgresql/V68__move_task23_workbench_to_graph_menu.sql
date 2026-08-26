-- Move the task 2/3 workbench under Graph Analysis. Keep this as a new
-- migration so environments that have already applied V67 remain valid.
UPDATE sys_menu workbench
SET parent_id=graph.id,
    menu_name='任务2/3风险推理',
    path='/graph/task23-risk',
    component='views/analysis/Task23GraphWorkbench.vue',
    icon='el-icon-share',
    sort_order=3,
    permission='graph:task23:view',
    visible=true,
    updated_at=CURRENT_TIMESTAMP
FROM sys_menu graph
WHERE workbench.path='/analysis/task23-graph'
  AND graph.path='/graph';

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT graph.id,'任务2/3风险推理','/graph/task23-risk',
       'views/analysis/Task23GraphWorkbench.vue','el-icon-share',3,
       'MENU','graph:task23:view',true
FROM sys_menu graph
WHERE graph.path='/graph'
  AND NOT EXISTS (SELECT 1 FROM sys_menu menu WHERE menu.path='/graph/task23-risk');
