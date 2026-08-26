-- Reasoning metadata describes the business behavior directly.  Source and
-- epistemic boundaries belong to instance properties, not to the node name.
UPDATE event_pattern_definition
SET pattern_name = CASE pattern_code
    WHEN 'TEXT_HIGH_FREQUENCY_PASS_THROUGH' THEN '高频收付后快速转出的资金过渡行为'
    WHEN 'TEXT_ROUND_AMOUNT_STRUCTURING' THEN '高频整数倍小额拆分交易行为'
    WHEN 'TEXT_MULTI_ACCOUNT_LAYERING' THEN '多账户逐级归集与转移行为'
    ELSE pattern_name
END
WHERE pattern_code IN (
    'TEXT_HIGH_FREQUENCY_PASS_THROUGH',
    'TEXT_ROUND_AMOUNT_STRUCTURING',
    'TEXT_MULTI_ACCOUNT_LAYERING'
);
