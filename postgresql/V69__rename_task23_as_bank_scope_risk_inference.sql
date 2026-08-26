-- Task 3 is bank-local, cross-case inference. Single-case reasoning remains
-- the responsibility of the AMLTRIX case reasoning flow.
UPDATE sys_menu
SET menu_name='银行本地风险推理',
    updated_at=CURRENT_TIMESTAMP
WHERE path='/graph/task23-risk';
