-- Runtime accounts existed on older deployments while their role/profile seeds were missing.
INSERT INTO sys_role (role_code, role_name, description, menus, permissions, deleted)
VALUES
('sadmin', '超级管理员', '系统超级管理员，拥有所有业务域权限',
 ARRAY['overview','system','data','analysis','case','graph','regulator','policy'],
 ARRAY['*:*:*'], false),
('badmin', '银行管理员', '管理本行数据、识别任务、案例和监管协同',
 ARRAY['overview','data','analysis','case','graph','regulator'],
 ARRAY['data:*','analysis:*','case:*','graph:*','regulator:view'], false),
('madmin', '监管管理员', '管理银行接入、共享、汇集下发和监管上报',
 ARRAY['overview','regulator'],
 ARRAY['regulator:*','sharing:*','bank-access:*'], false)
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO bank_profile
    (bank_code, bank_name, bank_full_name, bank_short_name, bank_type, status)
VALUES
    ('BANK001', '示范银行', 'DataGraph 示范银行', '示范银行', 'COMMERCIAL', 'ACTIVE')
ON CONFLICT (bank_code) DO NOTHING;
