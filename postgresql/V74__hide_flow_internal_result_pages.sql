-- Result and parameterized pages remain routable from their owning workflows,
-- but must not appear as standalone navigation entries.
UPDATE sys_menu
SET visible = false,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN (
    '/case/suspicious-transactions',
    '/case/detail',
    '/case/graph',
    '/graph/snapshot'
);
