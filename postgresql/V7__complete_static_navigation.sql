-- Bank-side missing entries.
INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '存疑交易识别', '/analysis/identify', 'views/analysis/identify.vue', 'el-icon-search', 0, 'MENU', 'analysis:identify:view', true
FROM sys_menu WHERE path='/analysis' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/analysis/identify');

INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '策略回放', '/case/replay', 'views/case/replay.vue', 'el-icon-refresh', 5, 'MENU', 'case:replay:view', true
FROM sys_menu WHERE path='/case' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/case/replay');

INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '图引擎管理', '/system/graph-engines', 'views/system/graph-engines.vue', 'el-icon-cpu', 5, 'MENU', 'system:graph:view', true
FROM sys_menu WHERE path='/system' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/system/graph-engines');

-- Regulator collaboration entries shared by regulator and authorized bank operators.
INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '监管总览', '/regulator/overview', 'views/regulator/overview.vue', 'el-icon-data-analysis', 0, 'MENU', 'regulator:overview:view', true
FROM sys_menu WHERE path='/regulator' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/regulator/overview');

INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '汇集与下发', '/regulator/dispatch', 'views/regulator/dispatch.vue', 'el-icon-upload', 5, 'MENU', 'regulator:dispatch:view', true
FROM sys_menu WHERE path='/regulator' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/regulator/dispatch');

INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT id, '上报记录', '/regulator/reports', 'views/regulator/reports.vue', 'el-icon-document', 6, 'MENU', 'regulator:reports:view', true
FROM sys_menu WHERE path='/regulator' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/regulator/reports');
