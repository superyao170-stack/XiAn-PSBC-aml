-- Keep case overview classification fields aligned with the controlled values
-- defined by 副本非法行为案例框架-图谱映射逻辑-框架-0415pm.xlsx.
INSERT INTO case_framework_option_metadata
    (field_code, field_name, option_code, option_label, option_value, sort_order, description)
VALUES
    ('suspectedCrimeType', '疑似涉罪类型', '0701',
     '涉嫌集资诈骗的可疑交易行为', '0701-涉嫌集资诈骗的可疑交易行为', 10,
     '仅在材料明确支持集资诈骗或非法集资时选择'),
    ('suspiciousTransactionFeatureCode', '可疑交易特征代码', '1001',
     '疑似非法集资', '1001-疑似非法集资', 10,
     '案例材料明确存在非法集资特征'),
    ('suspiciousTransactionFeatureCode', '可疑交易特征代码', '1002',
     '短期内对私客户快进快出不留余额', '1002-短期内对私客户快进快出不留余额', 20,
     '案例材料明确存在快进快出且不留余额特征')
ON CONFLICT (field_code, option_code) DO UPDATE SET
    field_name = EXCLUDED.field_name,
    option_label = EXCLUDED.option_label,
    option_value = EXCLUDED.option_value,
    sort_order = EXCLUDED.sort_order,
    source_ref = EXCLUDED.source_ref,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- Earlier worker versions could place free-form crime labels, internal UIDs or
-- AMLTRIX technique codes in these two controlled business fields.
UPDATE cf_risk_case
SET suspected_crime_type = CASE
        WHEN description ~ '(集资诈骗|非法集资)'
            THEN '0701-涉嫌集资诈骗的可疑交易行为'
        ELSE NULL
    END,
    suspicious_transaction_feature_code = CASE
        WHEN suspicious_transaction_feature_code LIKE '%1001%'
             AND suspicious_transaction_feature_code LIKE '%1002%'
            THEN '1001-疑似非法集资；1002-短期内对私客户快进快出不留余额'
        WHEN suspicious_transaction_feature_code LIKE '%1001%'
            THEN '1001-疑似非法集资'
        WHEN suspicious_transaction_feature_code LIKE '%1002%'
            THEN '1002-短期内对私客户快进快出不留余额'
        WHEN description ~ '(快进快出).{0,25}(不留余额|极少余额)'
            THEN '1002-短期内对私客户快进快出不留余额'
        WHEN description ~ '(集资诈骗|非法集资)'
            THEN '1001-疑似非法集资'
        ELSE NULL
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE deleted = false
  AND case_source = 'TEXT_CASE'
  AND (
      suspected_crime_type IS NOT NULL
      OR suspicious_transaction_feature_code IS NOT NULL
  );
