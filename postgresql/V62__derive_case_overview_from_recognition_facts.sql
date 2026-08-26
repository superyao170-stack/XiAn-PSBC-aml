-- Backfill only fields that have deterministic support in recognition lineage,
-- case lifecycle or explicit case text. User-maintained values are never overwritten.
UPDATE cf_risk_case
SET business_domain = COALESCE(NULLIF(btrim(business_domain), ''), '01-反洗钱'),
    business_case_type = COALESCE(NULLIF(btrim(business_case_type), ''), '01-可疑报告'),
    business_case_status = COALESCE(
        NULLIF(btrim(business_case_status), ''),
        CASE WHEN COALESCE(text_decision, struct_decision)='SUSPECTED'
             THEN '初步可疑' ELSE NULL END),
    business_risk_level = COALESCE(
        NULLIF(btrim(business_risk_level), ''),
        CASE
            WHEN risk_level IN ('HIGH','CRITICAL') THEN '重点可疑'
            WHEN risk_level IN ('LOW','MEDIUM') THEN '一般可疑'
            ELSE NULL
        END),
    trigger_point = COALESCE(
        NULLIF(btrim(trigger_point), ''),
        CASE
            WHEN case_source IN ('STRUCT_SUSPECTED','STRUCT_CASE','PATTERN_RECOGNITION')
                THEN '01-模型筛选'
            WHEN description ~ '(公安|纪检|安全部门|执法部门).{0,20}(指令|冻结|协查)'
                OR description ~ '(指令|冻结|协查).{0,20}(公安|纪检|安全部门|执法部门)'
                THEN '02-执法部门指令（公安、纪检、安全等部门的境内冻结、协查等）'
            WHEN description ~ '(央行|人民银行|证监会|交易所|监管部门).{0,20}(指令|警示|协查)'
                OR description ~ '(指令|警示|协查).{0,20}(央行|人民银行|证监会|交易所|监管部门)'
                THEN '03-监管部门指令（如央行、证监会、交易所等部门的警示或协查等）'
            WHEN description ~ '(模型筛选|系统筛选|规则命中)' THEN '01-模型筛选'
            WHEN description ~ '(社会舆情|媒体报道|网络舆情)' THEN '05-社会舆情'
            ELSE NULL
        END),
    urgency_level = COALESCE(
        NULLIF(btrim(urgency_level), ''),
        CASE WHEN description ~ '(特别紧急|紧急报送|立即处置)' THEN '02-特别紧急' ELSE NULL END),
    suspected_crime_type = COALESCE(
        NULLIF(btrim(suspected_crime_type), ''),
        CASE
            WHEN description ~ '(网络赌博|涉赌|赌场)' THEN '涉嫌网络赌博及洗钱相关犯罪'
            WHEN description ~ '(集资诈骗|非法集资)' THEN '涉嫌集资诈骗或非法吸收公众存款'
            WHEN description ~ '(电信诈骗|网络诈骗)' THEN '涉嫌电信网络诈骗及洗钱相关犯罪'
            WHEN description ~ '(毒品|贩毒)' THEN '涉嫌毒品犯罪及洗钱相关犯罪'
            WHEN description ~ '(贪污|受贿|职务犯罪)' THEN '涉嫌贪污贿赂或职务犯罪'
            ELSE NULL
        END),
    suspicious_transaction_feature_code = COALESCE(
        NULLIF(btrim(suspicious_transaction_feature_code), ''),
        CASE
            WHEN description ~ '(行为代码|代码)[：: ]*2002'
                OR description ~ '(涉赌组织|经营赌博|网络赌博).{0,30}(转移资金|洗钱)'
                THEN '2002-涉赌组织经营赌博或为其转移资金'
            WHEN description ~ '(快进快出).{0,20}(不留余额|极少余额)'
                THEN '1002-短期内对私客户快进快出不留余额'
            WHEN description ~ '(非法集资|集资诈骗)' THEN '1001-疑似非法集资'
            ELSE NULL
        END),
    updated_at = CURRENT_TIMESTAMP
WHERE deleted=false
  AND (
      business_domain IS NULL OR btrim(business_domain)=''
      OR business_case_type IS NULL OR btrim(business_case_type)=''
      OR business_case_status IS NULL OR btrim(business_case_status)=''
      OR business_risk_level IS NULL OR btrim(business_risk_level)=''
      OR trigger_point IS NULL OR btrim(trigger_point)=''
      OR urgency_level IS NULL OR btrim(urgency_level)=''
      OR suspected_crime_type IS NULL OR btrim(suspected_crime_type)=''
      OR suspicious_transaction_feature_code IS NULL
      OR btrim(suspicious_transaction_feature_code)=''
  );
