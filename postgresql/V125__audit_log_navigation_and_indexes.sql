-- Expose existing audit records to system administrators and keep common filters fast.
INSERT INTO sys_menu(parent_id, menu_name, path, component, icon, sort_order, type, permission, visible)
SELECT parent.id, '审计日志', '/system/audit-logs', 'views/system/AuditLogs.vue',
       'el-icon-document', 5, 'MENU', 'system:audit:view', true
FROM sys_menu parent
WHERE parent.path = '/system'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/audit-logs');

UPDATE sys_menu
SET menu_name = '审计日志', component = 'views/system/AuditLogs.vue', sort_order = 5,
    permission = 'system:audit:view', visible = true, updated_at = CURRENT_TIMESTAMP
WHERE path = '/system/audit-logs';

CREATE INDEX IF NOT EXISTS idx_audit_operation_log_username_created
    ON audit_operation_log(username, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operation_log_module_created
    ON audit_operation_log(operation_module, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operation_log_success_created
    ON audit_operation_log(success, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_login_log_username_created
    ON audit_login_log(username, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_login_log_success_created
    ON audit_login_log(success, created_at DESC);
