INSERT INTO sys_menu
    (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT 0, '识别与案例化', '/analysis', 'views/analysis/index.vue',
       'el-icon-cpu', 4, 'MENU', 'analysis:view', true
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/analysis');

INSERT INTO sys_menu
    (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT parent.id, '结构化案例化', '/analysis/structured',
       'views/analysis/structured.vue', 'el-icon-data-analysis', 1,
       'MENU', 'analysis:structured:view', true
FROM sys_menu parent
WHERE parent.path = '/analysis'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/analysis/structured');

INSERT INTO sys_menu
    (parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT parent.id, '非结构化案例化', '/analysis/unstructured',
       'views/analysis/unstructured.vue', 'el-icon-document', 2,
       'MENU', 'analysis:unstructured:view', true
FROM sys_menu parent
WHERE parent.path = '/analysis'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/analysis/unstructured');

UPDATE sys_role
SET menus = CASE
    WHEN 'analysis' = ANY(COALESCE(menus, ARRAY[]::TEXT[])) THEN menus
    ELSE array_append(COALESCE(menus, ARRAY[]::TEXT[]), 'analysis')
END
WHERE role_code IN ('sadmin', 'badmin');
