-- Task 2 joins the existing cross-case clue workflow; task 3 remains a
-- separate bank-local hidden-risk workflow. Legacy frontend paths redirect.
UPDATE sys_menu
SET menu_name='关联线索分析',
    path='/graph/association-clues',
    component='views/graph/AssociationClueWorkbench.vue',
    sort_order=2,
    updated_at=CURRENT_TIMESTAMP
WHERE path='/graph/clue';

UPDATE sys_menu
SET menu_name='隐蔽风险推理',
    path='/graph/hidden-risk',
    component='views/graph/HiddenRiskWorkbench.vue',
    sort_order=3,
    updated_at=CURRENT_TIMESTAMP
WHERE path='/graph/task23-risk';
