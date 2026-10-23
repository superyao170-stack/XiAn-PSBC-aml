#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import sys
import time
from pathlib import Path

import requests


BASE = 'http://127.0.0.1:3030/api/v1'
SOURCE = Path('/Users/zhangyao/Desktop/课题三/课题三-医疗腐败/输入数据/mock数据')
STAGING = Path('/tmp/bank-case-20260919-upload')
MANIFEST = SOURCE / 'case_upload_processing_manifest_20260919.json'


def login(session: requests.Session):
    response = session.post(f'{BASE}/auth/login', json={'username':'sadmin','password':'sadmin@123'}, timeout=30)
    response.raise_for_status()
    body = response.json()
    token = (body.get('data') or {}).get('token')
    if not token:
        raise RuntimeError(f'login failed: {body}')
    session.headers.update({'Authorization':f'Bearer {token}'})


def historical_parts(source: Path, prefix: str, chunk_size: int):
    STAGING.mkdir(parents=True, exist_ok=True)
    with source.open(encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        fields = reader.fieldnames
    result=[]
    for start in range(0, len(rows), chunk_size):
        chunk=rows[start:start+chunk_size]
        for row in chunk:
            basic=json.loads(row['basic_info'])
            basic['risk_level']=''
            basic['business_risk_level']=''
            basic['review_routing']='MANUAL_PENDING_APPROVAL'
            row['basic_info']=json.dumps(basic,ensure_ascii=False,separators=(',',':'))
        path=STAGING/f'{prefix}_{start//chunk_size+1:02d}.csv'
        with path.open('w',encoding='utf-8-sig',newline='') as handle:
            writer=csv.DictWriter(handle,fieldnames=fields)
            writer.writeheader();writer.writerows(chunk)
        result.append((path,len(chunk)))
    return result


def upload(session, path: Path, mode: str, scenario: str):
    endpoint = 'anti-fraud-case-batch-file' if scenario=='ANTI_FRAUD' else 'structured-case-batch-file'
    with path.open('rb') as handle:
        response=session.post(f'{BASE}/analysis/{endpoint}',files={'file':(path.name,handle,'text/csv')},data={'recognitionMode':mode},timeout=600)
    body=response.json()
    if response.status_code!=200 or body.get('code')!=200:
        raise RuntimeError(f'upload {path.name} failed: HTTP {response.status_code} {body}')
    return body['data']


def create_job(session, upload_info, path: Path, count: int, mode: str, scenario: str):
    workflow='ANTI_FRAUD_CASE_PIPELINE' if scenario=='ANTI_FRAUD' else 'XI_AN_CASE_PIPELINE'
    params={'workflow':workflow,'processingMode':'BATCH','recognitionMode':mode,
            'uploadToken':upload_info['uploadToken'],'caseCount':count,'sourceFileName':path.name}
    body={'bankCode':'中国测试银行','workspaceId':1,'batchId':None,'jobType':'STRUCTURED',
          'jobName':f'20260919-{scenario}-{mode}-{path.stem}','scenarioCode':scenario,
          'inputParams':json.dumps(params,ensure_ascii=False),
          'steps':[{'stepName':'数据校验','stepType':'VALIDATE'},
                   {'stepName':'案例入库','stepType':'PERSIST'},
                   {'stepName':'历史案例抽取并自动入图','stepType':'HISTORY_FRAMEWORK'}]}
    response=session.post(f'{BASE}/analysis/jobs',json=body,timeout=60)
    value=response.json()
    if response.status_code!=200 or value.get('code')!=200:
        raise RuntimeError(f'create job {path.name} failed: HTTP {response.status_code} {value}')
    data=value['data']
    return data.get('jobId') or data.get('job_id')


def wait_job(session, job_id: str):
    last=None
    started=time.time()
    while True:
        response=session.get(f'{BASE}/analysis/jobs/{job_id}',timeout=60)
        body=response.json()
        if response.status_code!=200 or body.get('code')!=200:
            raise RuntimeError(f'job query failed {job_id}: {body}')
        data=body['data']; status=data.get('status')
        if status!=last:
            print(json.dumps({'jobId':job_id,'status':status,'progress':data.get('progress'),'elapsedSeconds':round(time.time()-started,1)},ensure_ascii=False),flush=True)
            last=status
        if status in {'SUCCEEDED','FAILED','CANCELLED'}:
            if status!='SUCCEEDED':
                raise RuntimeError(f'job {job_id} ended with {status}: {data}')
            return data
        if time.time()-started>7200:
            raise TimeoutError(job_id)
        time.sleep(5)


def main():
    aml_h=historical_parts(SOURCE/'aml_historical_cases_1000_20260918.csv','aml_historical',500)
    frd_h=historical_parts(SOURCE/'anti_fraud_historical_cases_500_20260918.csv','fraud_historical',500)
    batches=[
        *((p,c,'HISTORICAL','AML') for p,c in aml_h),
        (SOURCE/'aml_new_cases_200_20260918.csv',200,'NEW','AML'),
        *((p,c,'HISTORICAL','ANTI_FRAUD') for p,c in frd_h),
        (SOURCE/'anti_fraud_new_cases_100_20260918.csv',100,'NEW','ANTI_FRAUD'),
    ]
    session=requests.Session();login(session)
    manifest={'startedAt':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'batches':[]}
    for path,count,mode,scenario in batches:
        print(json.dumps({'phase':'upload','file':path.name,'count':count,'mode':mode,'scenario':scenario},ensure_ascii=False),flush=True)
        info=upload(session,path,mode,scenario)
        job_id=create_job(session,info,path,count,mode,scenario)
        item={'file':path.name,'count':count,'mode':mode,'scenario':scenario,'jobId':job_id,'uploadToken':info['uploadToken']}
        manifest['batches'].append(item)
        MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
        data=wait_job(session,job_id)
        item.update({'status':data.get('status'),'progress':data.get('progress'),'completedAt':str(data.get('completedAt') or '')})
        MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    manifest['completedAt']=time.strftime('%Y-%m-%dT%H:%M:%S%z')
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'SUCCEEDED','manifest':str(MANIFEST)},ensure_ascii=False),flush=True)


if __name__=='__main__':
    try: main()
    except Exception as exc:
        print(json.dumps({'status':'FAILED','error':str(exc)},ensure_ascii=False),file=sys.stderr,flush=True)
        raise
