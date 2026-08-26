-- Case management keeps transaction signals and event-centred cases as separate queues.
INSERT INTO sys_menu(parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT p.id, '存疑交易列表', '/case/suspicious-transactions',
       'views/case/SuspiciousTransactionList.vue', 'el-icon-warning', 0,
       'MENU', 'case:suspicious:view', true
FROM sys_menu p
WHERE p.path='/case'
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path='/case/suspicious-transactions');

INSERT INTO sys_menu(parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT p.id, '案例图谱', '/case/graph',
       'views/case/CaseGraph.vue', 'el-icon-connection', 99,
       'MENU', 'case:graph:view', true
FROM sys_menu p
WHERE p.path='/case'
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path='/case/graph');
