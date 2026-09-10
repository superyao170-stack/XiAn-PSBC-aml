INSERT INTO sys_role (role_code, role_name, description, menus, permissions, deleted)
VALUES ('viewer', '案例访客', '可查看共享案例列表，不可上传、修改、删除或审批案例',
        ARRAY['case'], ARRAY['case:list:view'], false)
ON CONFLICT (role_code) DO UPDATE SET
    role_name = EXCLUDED.role_name,
    description = EXCLUDED.description,
    menus = EXCLUDED.menus,
    permissions = EXCLUDED.permissions,
    deleted = false,
    updated_at = CURRENT_TIMESTAMP;
