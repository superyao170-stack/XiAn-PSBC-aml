#!/usr/bin/env python3
from __future__ import annotations

import concurrent.futures
import json
import subprocess
import time
from pathlib import Path

import requests


BASE='http://127.0.0.1:3030/api/v1'
MANIFEST=Path('/Users/zhangyao/Desktop/课题三/课题三-医疗腐败/输入数据/mock数据/case_upload_processing_manifest_20260919.json')


def login():
    session=requests.Session()
    value=session.post(f'{BASE}/auth/login',json={'username':'sadmin','password':'sadmin@123'},timeout=30).json()
    session.headers['Authorization']='Bearer '+value['data']['token']
    return session


SESSION=login()


def detail(case_id):
    value=SESSION.get(f'{BASE}/case-processing/{case_id}',timeout=60).json()
    if value.get('code')!=200: raise RuntimeError(value)
    return value['data']


def aml_report(payload):
    b=payload['basic_info']; customers=payload.get('customers') or []; accounts=payload.get('accounts') or b.get('accounts') or []
    feature=payload.get('transaction_features') or {}; txs=feature.get('transactions') or b.get('transaction_records') or []
    patterns=b.get('suspicious_pattern_details') or feature.get('event_features') or []
    total_in=sum(float(x.get('amount_cny') or 0) for x in txs if x.get('direction')=='转入')
    total_out=sum(float(x.get('amount_cny') or 0) for x in txs if x.get('direction')=='转出')
    customer_text='；'.join(f"{x.get('customer_name')}，客户号{x.get('customer_number') or x.get('customer_no')}，证件{x.get('id_number')}，职业或行业{x.get('occupation_industry')}，归属{x.get('province')}{x.get('institution')}，风险等级{x.get('customer_risk_level') or x.get('risk_level')}" for x in customers)
    account_text='；'.join(f"{x.get('holder_name')}持有{x.get('account_type')}，账号{x.get('account_number') or x.get('account_no')}，开户行{x.get('opening_bank') or x.get('bank_name')}，开户日期{x.get('open_date') or x.get('opened_at')}" for x in accounts)
    tx_text='；'.join(f"{x.get('occurred_at')}{x.get('direction')}{float(x.get('amount_cny') or 0):.2f}元，对手{x.get('counterparty_name')}（{x.get('counterparty_account')}），渠道{x.get('transaction_channel')}，附言{x.get('memo')}，证据{x.get('evidence_ref')}" for x in txs)
    event_text='；'.join(f"{i+1}.{x.get('event_start_date')}【{x.get('event_type_id')}】{x.get('fact')}，涉及金额{float(x.get('total_amount_cny') or 0):.2f}元，风险指标{x.get('risk_indicator')}" for i,x in enumerate(patterns))
    return (f"一、案例概况：{b.get('case_description')} 案例由{b.get('case_trigger')}触发，报送方向为{b.get('submission_direction') or b.get('reporting_direction')}。"
            f"\n二、客户信息：{customer_text}。\n三、账户信息：{account_text}。"
            f"\n四、资金交易事实：观察期共核验{len(txs)}笔重点流水，转入合计{total_in:.2f}元，转出合计{total_out:.2f}元。{tx_text}。"
            f"\n五、风险事件与证据链：{event_text}。"
            f"\n六、资金路径研判：{b.get('fund_flow_path')}。交易规模、频次、地域、账户控制关系与客户职业及申报收入存在明显偏离，部分资金在入账后短时间内经关联账户继续转移。"
            f"\n七、核验边界：客户对部分交易解释为{b.get('alternative_explanation')}，仍需核验{b.get('verification_focus')}以及合同、发票、物流和资金最终去向，当前材料属于可疑交易核查依据，不替代司法认定。"
            f"\n八、处置建议：{b.get('disposition_measures') or b.get('disposal_measure')}。建议持续保留交易流水、登录日志、客户回访和业务凭证，发现外部涉案线索时及时协同核查。")


def fraud_report(payload):
    b=payload['basic_info']; customers=payload.get('customers') or []; accounts=payload.get('accounts') or []; devices=payload.get('devices') or []; events=payload.get('event_chain') or []
    customer_text='；'.join(f"{x.get('customer_name')}，客户号{x.get('customer_number')}，证件{x.get('id_number')}，职业或行业{x.get('occupation_industry')}，归属{x.get('province')}{x.get('institution')}" for x in customers)
    account_text='；'.join(f"{x.get('holder_name')}持有{x.get('account_type')}，账号{x.get('account_number')}，银行卡尾号{x.get('bank_card_number','')[-4:]}，开户行{x.get('bank_info')}，开户日期{x.get('account_open_date')}" for x in accounts)
    device_text='；'.join(f"{x.get('设备号')}，{x.get('设备名称')}，IP {x.get('ip地址')}" for x in devices)
    event_text='；'.join(f"{i+1}.{x.get('发生时间')}【{x.get('类型')}】{x.get('具体内容')}" for i,x in enumerate(events))
    return (f"一、线索概况：{b.get('case_description')} 线索由{b.get('case_trigger')}触发，紧急程度{b.get('urgency')}。"
            f"\n二、客户信息：{customer_text}。\n三、账户信息：{account_text}。\n四、设备与网络信息：{device_text}。"
            f"\n五、连续事件链：{event_text}。"
            f"\n六、研判意见：账户在新设备或异地网络环境登录后发生收款人变更、限额调整、多头转入及快速转出，时间衔接紧密，资金用途和客户既往画像存在偏离。相关规则命中构成待核验风险线索，需要结合客户本人确认、付款人陈述、设备控制关系和下游账户流水进一步判断。"
            f"\n七、处置与核验：{b.get('disposition_measures')}。继续核验客户是否本人操作、异常设备实际控制人、各笔付款与事件的对应关系及资金最终去向；当前材料不替代公安机关和司法机关的最终认定。")


def save_report(case_id, scenario):
    data=detail(case_id)
    if data['processingStage']!='PENDING_REPORT':
        return {'caseId':case_id,'stage':data['processingStage'],'skipped':True}
    payload=data['sourcePayload']
    text=aml_report(payload) if scenario=='AML' else fraud_report(payload)
    lowered=text.lower()
    if any(word in lowered for word in ('模拟','测试','mock')): raise RuntimeError(f'forbidden marker in {case_id}')
    value=SESSION.put(f'{BASE}/case-processing/{case_id}/report',json={'analysisText':text},timeout=120).json()
    if value.get('code')!=200: raise RuntimeError(f'{case_id}: {value}')
    return {'caseId':case_id,'stage':'PENDING_EXTRACTION','reportChars':len(text)}


def reports(ids,scenario):
    results=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        futures={pool.submit(save_report,cid,scenario):cid for cid in ids}
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    print(json.dumps({'phase':'reports','scenario':scenario,'count':len(results),'minChars':min(x.get('reportChars',10**9) for x in results),'maxChars':max(x.get('reportChars',0) for x in results)},ensure_ascii=False),flush=True)
    return results


def enrich_fraud_framework(ids):
    listed=','.join("'"+x+"'" for x in ids)
    sql=f"""
    UPDATE case_processing_pool p
       SET source_payload=jsonb_set(source_payload,'{{basic_info,suspicious_pattern_details}}',
         COALESCE((SELECT jsonb_agg(jsonb_build_object(
           'event_id',p.case_id||'-EV-'||lpad(e.ord::text,2,'0'),
           'event_type_id',(ARRAY['FRD-MOB-001','FRD-MOB-003','FRD-TXN-001','FRD-TXN-002','FRD-MOB-002','FRD-DEV-001','FRD-DSP-001'])[e.ord],
           'subject_entity_id',p.source_payload->'customers'->0->>'entity_id',
           'subject_name',p.source_payload->'customers'->0->>'customer_name',
           'account_ids',jsonb_build_array(p.source_payload->'accounts'->0->>'entity_id',p.source_payload->'accounts'->1->>'entity_id',p.source_payload->'accounts'->2->>'entity_id'),
           'account_numbers',jsonb_build_array(p.source_payload->'accounts'->0->>'account_number',p.source_payload->'accounts'->1->>'account_number',p.source_payload->'accounts'->2->>'account_number'),
           'event_start_date',e.item->>'发生时间','event_end_date',e.item->>'发生时间',
           'total_amount_cny',COALESCE((p.source_payload->'basic_info'->>'involved_amount_cny')::numeric,0),
           'transaction_ids','[]'::jsonb,'behavior_chain',e.item->>'具体内容','fact',e.item->>'具体内容',
           'source_fact_summary','来源为事件链、客户资料、账户资料和设备日志的交叉记录',
           'risk_indicator',(ARRAY['异常网络登录','异常新增收款人','多头分散转入','资金快进快出','远程控制操作','共享设备控制多账户','风险提醒后继续交易'])[e.ord]
         ) ORDER BY e.ord)
          FROM jsonb_array_elements(p.source_payload->'event_chain') WITH ORDINALITY e(item,ord)), '[]'::jsonb),true),
           updated_at=CURRENT_TIMESTAMP
     WHERE p.case_id IN ({listed}) AND p.processing_stage='PENDING_EXTRACTION';
    """
    run=subprocess.run(['docker','exec','-i','bankgraph-postgres','psql','-U','postgres','-d','databank','-v','ON_ERROR_STOP=1'],input=sql,text=True,capture_output=True,check=True)
    print(json.dumps({'phase':'enrich-fraud','output':run.stdout.strip()},ensure_ascii=False),flush=True)


def action(name,ids):
    required={'FRAMEWORK':'PENDING_EXTRACTION','SIMILARITY':'PENDING_SIMILARITY'}[name]
    eligible=[case_id for case_id in ids if detail(case_id)['processingStage']==required]
    started=time.time()
    if not eligible:
        return {'requested':0,'succeeded':0,'failed':0,'results':[]}
    def one(case_id):
        value=SESSION.post(f'{BASE}/case-processing/actions/{name}',json={'caseIds':[case_id]},timeout=7200).json()
        if value.get('code')!=200 or value.get('data',{}).get('failed'):
            raise RuntimeError(f'{name} {case_id}: {value}')
        return value['data']['results'][0]
    completed=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        futures={pool.submit(one,case_id):case_id for case_id in eligible}
        for future in concurrent.futures.as_completed(futures):
            completed.append(future.result())
            if len(completed)%10==0 or len(completed)==len(eligible):
                print(json.dumps({'phase':name,'completed':len(completed),'total':len(eligible),'seconds':round(time.time()-started,1)},ensure_ascii=False),flush=True)
    result={'requested':len(eligible),'succeeded':len(completed),'failed':0,'results':completed}
    print(json.dumps({'phase':name,'count':len(eligible),'alreadyAdvanced':len(ids)-len(eligible),'seconds':round(time.time()-started,1)},ensure_ascii=False),flush=True)
    return result


def stage_counts():
    sql="""
    SELECT scenario_code,recognition_mode,processing_stage,COUNT(*)
      FROM case_processing_pool
     WHERE (case_id BETWEEN 'AML-H-007101' AND 'AML-H-008100')
        OR (case_id BETWEEN 'AML-N-002031' AND 'AML-N-002230')
        OR (case_id BETWEEN 'FRD-H-002021' AND 'FRD-H-002520')
        OR (case_id BETWEEN 'FRD-N-001004' AND 'FRD-N-001103')
     GROUP BY 1,2,3 ORDER BY 1,2,3;
    """
    out=subprocess.run(['docker','exec','-i','bankgraph-postgres','psql','-U','postgres','-d','databank','-At'],input=sql,text=True,capture_output=True,check=True).stdout.strip().splitlines()
    return out


def main():
    aml=[f'AML-N-{i:06d}' for i in range(2031,2231)]
    fraud=[f'FRD-N-{i:06d}' for i in range(1004,1104)]
    report_result={'aml':reports(aml[:150],'AML'),'antiFraud':reports(fraud[:75],'ANTI_FRAUD')}
    enrich_fraud_framework(fraud[:50])
    framework_result={'aml':action('FRAMEWORK',aml[:100]),'antiFraud':action('FRAMEWORK',fraud[:50])}
    similarity_result={'aml':action('SIMILARITY',aml[:50]),'antiFraud':action('SIMILARITY',fraud[:25])}
    counts=stage_counts()
    manifest=json.loads(MANIFEST.read_text(encoding='utf-8')) if MANIFEST.exists() else {}
    manifest['stageProcessing']={'completedAt':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'reports':{'AML':150,'ANTI_FRAUD':75},'framework':{'AML':100,'ANTI_FRAUD':50},'similarity':{'AML':50,'ANTI_FRAUD':25},'stageCounts':counts,'results':{'framework':framework_result,'similarity':similarity_result}}
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'SUCCEEDED','stageCounts':counts,'manifest':str(MANIFEST)},ensure_ascii=False),flush=True)


if __name__=='__main__': main()
