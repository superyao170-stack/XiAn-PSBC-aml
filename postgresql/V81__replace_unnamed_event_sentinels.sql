UPDATE cf_risk_event
SET event_name = CASE
        WHEN event_type LIKE '%收款%' THEN '资金收取事件'
        WHEN event_type LIKE '%转账%' THEN '资金转移事件'
        WHEN event_type LIKE '%付款%' THEN '资金支付事件'
        WHEN event_type IS NOT NULL AND btrim(regexp_replace(event_type, '^[0-9]{2}-', '')) <> ''
            THEN regexp_replace(event_type, '^[0-9]{2}-', '')
        ELSE '其他交易事件'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE event_name IS NULL
   OR btrim(event_name) = ''
   OR upper(event_name) = 'UNNAMED_WORKER_EVENT';
