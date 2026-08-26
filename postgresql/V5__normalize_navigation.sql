-- Repair the menu hierarchy without relying on auto-generated numeric IDs.
UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/system'
  AND child.path IN ('/system/users', '/system/roles', '/system/menus', '/system/dict');

UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/data'
  AND child.path IN ('/data/schema', '/data/batches', '/data/quarantine');

UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/case'
  AND child.path IN ('/case/list', '/case/detail', '/case/review', '/case/approval');

UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/graph'
  AND child.path IN ('/graph/visualize', '/graph/clue', '/graph/snapshot');

UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/regulator'
  AND child.path IN ('/regulator/sharing', '/regulator/pending', '/regulator/bank', '/regulator/exchange');

UPDATE sys_menu child
SET parent_id = parent.id
FROM sys_menu parent
WHERE parent.path = '/policy'
  AND child.path IN ('/policy/scenario', '/policy/rule');

-- Detail pages require route parameters and snapshots have no implemented route.
-- They remain addressable from their business flows but must not appear as direct navigation entries.
UPDATE sys_menu
SET visible = false
WHERE path IN ('/case/detail', '/graph/snapshot');
