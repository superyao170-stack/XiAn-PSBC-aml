-- Expose the task 2/3 event-graph and matter-graph analytics workbench.
INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT p.id,'任务2/3图谱推理','/analysis/task23-graph',
       'views/analysis/Task23GraphWorkbench.vue','el-icon-share',2,
       'MENU','analysis:task23:view',true
FROM sys_menu p
WHERE p.path='/analysis'
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path='/analysis/task23-graph');
