-- Keep all existing capabilities and permissions, while making the visible
-- navigation follow the actual business sequence defined by the guided-flow
-- design: data -> recognition -> case handling -> cross-case analysis.
UPDATE sys_menu
SET sort_order = CASE path
        WHEN '/overview' THEN 1
        WHEN '/data' THEN 2
        WHEN '/analysis' THEN 3
        WHEN '/case' THEN 4
        WHEN '/graph' THEN 5
        WHEN '/regulator' THEN 6
        WHEN '/policy' THEN 7
        WHEN '/knowledge' THEN 8
        WHEN '/system' THEN 99
        ELSE sort_order
    END,
    menu_name = CASE path
        WHEN '/analysis' THEN '非法行为识别'
        WHEN '/case' THEN '案例管理'
        WHEN '/graph' THEN '线索分析'
        WHEN '/policy' THEN '策略配置'
        ELSE menu_name
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN (
    '/overview', '/data', '/analysis', '/case', '/graph',
    '/regulator', '/policy', '/knowledge', '/system'
);

UPDATE sys_menu
SET sort_order = CASE path
        WHEN '/analysis/identify' THEN 1
        WHEN '/analysis/structured' THEN 2
        WHEN '/analysis/unstructured' THEN 3
        WHEN '/analysis/pattern' THEN 4
        WHEN '/case/list' THEN 1
        WHEN '/case/review' THEN 2
        WHEN '/case/approval' THEN 3
        WHEN '/case/replay' THEN 4
        WHEN '/graph/visualize' THEN 1
        WHEN '/graph/association-clues' THEN 2
        WHEN '/graph/hidden-risk' THEN 3
        WHEN '/policy/scenario' THEN 1
        WHEN '/policy/rule' THEN 2
        WHEN '/policy/algorithm' THEN 3
        ELSE sort_order
    END,
    menu_name = CASE path
        WHEN '/analysis/identify' THEN '存疑交易识别'
        WHEN '/analysis/structured' THEN '结构化案例识别'
        WHEN '/analysis/unstructured' THEN '非结构化案例识别'
        WHEN '/analysis/pattern' THEN '场景模式识别'
        WHEN '/graph/visualize' THEN '全景图谱'
        WHEN '/graph/association-clues' THEN '关联线索分析'
        WHEN '/graph/hidden-risk' THEN '隐蔽风险推理'
        ELSE menu_name
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN (
    '/analysis/identify', '/analysis/structured', '/analysis/unstructured', '/analysis/pattern',
    '/case/list', '/case/review', '/case/approval', '/case/replay',
    '/graph/visualize', '/graph/association-clues', '/graph/hidden-risk',
    '/policy/scenario', '/policy/rule', '/policy/algorithm'
);

-- Parameterized/internal pages stay reachable through business flows, but do
-- not appear as independent menu entries.
UPDATE sys_menu
SET visible = false,
    updated_at = CURRENT_TIMESTAMP
WHERE path IN ('/case/detail', '/case/graph', '/graph/snapshot');
