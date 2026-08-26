-- The three case-forming recognition modes are the visible business entry.
UPDATE sys_menu
SET menu_name = '案例识别',
    updated_at = CURRENT_TIMESTAMP
WHERE path = '/analysis';

-- Scene-pattern recognition is no longer a standalone business menu.
-- Its historical tasks and data remain intact.
UPDATE sys_menu
SET visible = false,
    updated_at = CURRENT_TIMESTAMP
WHERE path = '/analysis/pattern';

UPDATE sys_menu
SET sort_order = CASE path
        WHEN '/overview' THEN 1
        WHEN '/analysis' THEN 2
        WHEN '/case' THEN 3
        WHEN '/graph' THEN 4
        WHEN '/data' THEN 5
        WHEN '/policy' THEN 6
        WHEN '/knowledge' THEN 7
        WHEN '/regulator' THEN 8
        WHEN '/system' THEN 99
        ELSE sort_order
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN (
    '/overview', '/analysis', '/case', '/graph', '/data',
    '/policy', '/knowledge', '/regulator', '/system'
);
