-- Consolidate existing metadata capabilities into classified navigation.
-- Routes and permissions remain unchanged; only ownership and display names change.
UPDATE sys_menu
SET menu_name = '元数据管理', sort_order = 8, updated_at = CURRENT_TIMESTAMP
WHERE path = '/knowledge';

UPDATE sys_menu
SET menu_name = '元数据总览', sort_order = 1, updated_at = CURRENT_TIMESTAMP
WHERE path = '/knowledge/overview';

INSERT INTO sys_menu(parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT p.id, c.menu_name, c.path, '', NULL, c.sort_order, 'MENU', 'knowledge:view', true
FROM sys_menu p
CROSS JOIN (
    VALUES
      ('数据与案例元数据', '/knowledge/category/data-case', 2),
      ('事件与图谱元数据', '/knowledge/category/event-graph', 3),
      ('事理与风控知识', '/knowledge/category/reasoning-risk', 4),
      ('版本与模型', '/knowledge/category/version-model', 5)
) AS c(menu_name, path, sort_order)
WHERE p.path = '/knowledge'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path = c.path);

UPDATE sys_menu child
SET parent_id = category.id,
    sort_order = mapping.sort_order,
    menu_name = mapping.menu_name,
    updated_at = CURRENT_TIMESTAMP
FROM sys_menu category
JOIN (
    VALUES
      ('/data/schema', '/knowledge/category/data-case', 1, '数据结构（Schema）'),
      ('/knowledge/case-framework-metadata', '/knowledge/category/data-case', 2, '案例框架'),
      ('/knowledge/events', '/knowledge/category/event-graph', 1, '事件类型'),
      ('/knowledge/event-governance', '/knowledge/category/event-graph', 2, '事件语义治理'),
      ('/knowledge/graph-metadata', '/knowledge/category/event-graph', 3, '图谱元数据'),
      ('/knowledge/amltrix', '/knowledge/category/reasoning-risk', 1, '战术与技术'),
      ('/knowledge/indicators', '/knowledge/category/reasoning-risk', 2, '指标管理'),
      ('/knowledge/patterns', '/knowledge/category/reasoning-risk', 3, '事理模式'),
      ('/knowledge/mappings', '/knowledge/category/reasoning-risk', 4, '关系与映射'),
      ('/knowledge/coverage', '/knowledge/category/reasoning-risk', 5, '应用覆盖'),
      ('/knowledge/releases', '/knowledge/category/version-model', 1, '版本发布'),
      ('/knowledge/models', '/knowledge/category/version-model', 2, '模型注册')
) AS mapping(child_path, category_path, sort_order, menu_name)
  ON category.path = mapping.category_path
WHERE child.path = mapping.child_path;
