-- Business-facing formal text for case-level pattern occurrences.
-- Keep the text in governed pattern metadata instead of hard-coding it in the UI.

UPDATE event_pattern_version
SET pattern_payload = pattern_payload || jsonb_build_object(
    'formalText', '材料中的低于报告阈值分笔操作与正式指标共同支持阈值下拆分行为模式。',
    'businessDescription', '用于识别以低于报告阈值的分笔操作、测试支付等方式探查或规避阈值的行为结构。'
)
WHERE pattern_code='TEXT_THRESHOLD_STRUCTURING' AND version='1.0';

UPDATE event_pattern_version
SET pattern_payload = pattern_payload || jsonb_build_object(
    'formalText', '材料中的多账户分散及跨区域转移事实与正式指标共同支持跨境多账户转移行为模式。',
    'businessDescription', '用于识别资金经多个账户分散后继续跨境或跨区域转移的行为结构。'
)
WHERE pattern_code='TEXT_CROSS_BORDER_MULTI_ACCOUNT' AND version='1.0';

UPDATE event_pattern_version
SET pattern_payload = pattern_payload || jsonb_build_object(
    'formalText', '材料中的高频交易与第三方支付事实和正式指标共同支持高频数字支付行为模式。',
    'businessDescription', '用于识别通过第三方支付渠道实施高频资金操作的行为结构。'
)
WHERE pattern_code='TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT' AND version='1.0';
