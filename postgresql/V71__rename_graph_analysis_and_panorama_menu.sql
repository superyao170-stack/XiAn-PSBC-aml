-- Rename the graph workbench navigation without changing routes or permissions.
UPDATE sys_menu
SET menu_name='线索分析',
    updated_at=CURRENT_TIMESTAMP
WHERE path='/graph';

UPDATE sys_menu
SET menu_name='全景图谱',
    updated_at=CURRENT_TIMESTAMP
WHERE path='/graph/visualize';
