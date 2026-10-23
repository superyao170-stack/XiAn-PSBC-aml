#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import random
import re
from datetime import datetime, timedelta
from pathlib import Path


OUTPUT = Path('/Users/zhangyao/Desktop/课题三/课题三-医疗腐败/输入数据/mock数据')
FORBIDDEN = re.compile(r'模拟|测试|mock', re.I)
RNG = random.Random(20260918)

PROVINCES = [
    ('陕西省', '西安市', '中国邮政储蓄银行西安市分行'),
    ('河南省', '郑州市', '中国邮政储蓄银行郑州市分行'),
    ('四川省', '成都市', '中国邮政储蓄银行成都市分行'),
    ('湖北省', '武汉市', '中国邮政储蓄银行武汉市分行'),
    ('浙江省', '杭州市', '中国邮政储蓄银行杭州市分行'),
    ('广东省', '广州市', '中国邮政储蓄银行广州市分行'),
    ('江苏省', '南京市', '中国邮政储蓄银行南京市分行'),
    ('山东省', '济南市', '中国邮政储蓄银行济南市分行'),
    ('福建省', '厦门市', '中国邮政储蓄银行厦门市分行'),
    ('湖南省', '长沙市', '中国邮政储蓄银行长沙市分行'),
]
SURNAMES = list('赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜谢邹苏潘葛范彭郎鲁韦昌马苗方俞任袁柳唐罗薛贺倪汤滕殷段雷白邓郭夏高林徐')
GIVEN = list('安博辰德峰国海杰凯林明宁鹏强荣涛伟鑫勇泽')
OCCUPATIONS = ['建筑材料批发', '物流运输', '餐饮服务', '电子产品零售', '农产品贸易', '信息技术服务', '物业管理', '服装经营', '机械设备租赁', '个体工商经营']
CHANNELS = ['手机银行', '网上银行', '柜面', 'ATM', '快捷支付', '第三方支付']

AML_THEMES = [
    ('分散转入后集中转出', 'ET015', '大量异地个人账户分散转入，随后向少数关联账户集中转出', '涉嫌掩饰、隐瞒犯罪所得、犯罪所得收益'),
    ('多层账户资金过渡', 'ET004', '资金在多个关联账户间连续划转并在短时间内离开本机构', '涉嫌掩饰、隐瞒犯罪所得、犯罪所得收益'),
    ('异常现金集中存取', 'ET016', '多家网点连续存入现金后通过电子渠道集中转出', '涉嫌非法经营及洗钱活动'),
    ('跨区域快速轮转', 'ET069', '同日跨机构跨地域连续收付，交易节奏与客户经营周期不符', '涉嫌地下钱庄及非法汇兑'),
    ('公私账户频繁对转', 'ET057', '单位结算账户与控制人个人账户高频往返并伴随取现', '涉嫌职务侵占及洗钱活动'),
    ('虚假贸易结算回流', 'ET028', '交易附言与合同品名不一致，付款后经关联方拆分回流', '涉嫌虚开发票及洗钱活动'),
    ('贴近阈值拆分交易', 'ET053', '多笔金额贴近报告阈值且交易时间连续，存在刻意拆分特征', '涉嫌逃避反洗钱监测及洗钱活动'),
    ('异常贵金属购入回售', 'ET088', '资金集中购入贵金属并短期回售，回款流向非原付款方', '涉嫌利用高值商品转移犯罪所得'),
]
FRAUD_THEMES = [
    ('虚假投资理财', 'FRD-SCM-001', '客户在陌生投资平台引导下多次转账，资金进入后迅速分层转出'),
    ('刷单返利', 'FRD-SCM-002', '多名付款人以任务款名义转入，涉险账户随即归集并转向下游账户'),
    ('冒充客服退款', 'FRD-SCM-003', '客户接到冒充平台客服来电后共享屏幕并办理多笔转账'),
    ('虚假贷款解冻', 'FRD-SCM-004', '客户以贷款解冻和保证金名义连续向陌生账户付款'),
    ('虚假购物预付款', 'FRD-SCM-005', '多名客户支付商品预付款后未收到货物，收款账户快速转移资金'),
    ('婚恋交友诱导投资', 'FRD-SCM-006', '客户经网络交友对象引导向投资平台充值并追加转账'),
    ('企业身份冒用付款', 'FRD-SCM-007', '财务人员收到冒用企业负责人身份的付款指令并执行大额转账'),
]


def jd(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def masked_name(seed: int) -> str:
    return SURNAMES[seed % len(SURNAMES)] + '某' + GIVEN[(seed * 7) % len(GIVEN)]


def card(prefix: str, n: int, suffix: int) -> str:
    digits = f'{prefix}{n:08d}{suffix:02d}'
    return digits[:8] + '****' + digits[-4:]


def person_id(n: int, suffix: int) -> str:
    return f'61{(n % 90)+10:02d}04********{(n * 37 + suffix) % 10000:04d}'


def dt_text(base: datetime, minutes: int) -> str:
    return (base + timedelta(minutes=minutes)).strftime('%Y-%m-%d %H:%M:%S')


def customer(case_id: str, n: int, j: int, province: str, city: str, institution: str, risk='中高'):
    name = masked_name(n * 5 + j)
    entity = f'{case_id}-CUS-{j:02d}'
    return {
        'entity_id': entity, 'customer_name': name,
        'customer_number': f'C{202000000000 + n * 10 + j}',
        'customer_no': f'C{202000000000 + n * 10 + j}',
        'id_type': '居民身份证', 'id_number': person_id(n, j),
        'occupation_industry': OCCUPATIONS[(n + j) % len(OCCUPATIONS)],
        'occupation': OCCUPATIONS[(n + j) % len(OCCUPATIONS)],
        'industry': OCCUPATIONS[(n + j) % len(OCCUPATIONS)],
        'nationality': '中国', 'province': province, 'institution': institution,
        'address': f'{province}{city}{["新城路","民安路","兴业路","长乐路","锦程路"][(n+j)%5]}{20+(n+j)%180}号',
        'mobile': f'1{["36","37","38","39","50","51","52","53","55","56"][(n+j)%10]}****{(n*29+j*13)%10000:04d}',
        'annual_income_cny': 80000 + ((n * 7919 + j * 1301) % 720000),
        'customer_since': f'{2014+(n+j)%10}-{1+(n*3+j)%12:02d}-{1+(n*7+j)%27:02d}',
        'customer_risk_level': risk, 'risk_level': risk,
        'kyc_status': '身份核验有效，职业及收入资料已留存',
        'legal_representative_name': '不适用', 'legal_representative_id_type': '不适用',
        'legal_representative_id_number': '不适用', 'controller_name': name,
        'controller_id_type': '居民身份证', 'controller_id_number': person_id(n, j),
    }


def aml_accounts(case_id: str, n: int, customers: list[dict], province: str, institution: str):
    result = []
    for j in range(1, 6):
        holder = customers[(j - 1) % len(customers)]
        account_no = card('621799', n, j)
        result.append({
            'entity_id': f'{case_id}-ACC-{j:02d}', 'account_id': f'{case_id}-ACC-{j:02d}',
            'account_type': ['个人I类账户','个人II类账户','单位结算账户'][j % 3],
            'holder_name': holder['customer_name'], 'holder_entity_id': holder['entity_id'],
            'holder_id_type': holder['id_type'], 'holder_id_number': holder['id_number'],
            'opened_at': f'{2016+(n+j)%8}-{1+(n+j)%12:02d}-{1+(n*2+j)%27:02d}',
            'open_date': f'{2016+(n+j)%8}-{1+(n+j)%12:02d}-{1+(n*2+j)%27:02d}',
            'closed_at': '', 'province': province, 'institution': institution,
            'account_no': account_no, 'account_number': account_no,
            'card_type': '借记卡', 'opening_bank': institution, 'bank_name': institution,
            'card_no': account_no, 'usual_purpose': '日常经营收支及个人结算',
            'status': '正常监测', 'balance_cny': round(12000 + (n * 4591 + j * 769) % 380000, 2),
            'risk_tags': ['交易规模与客户画像不符', '关联账户交易集中'],
        })
    return result


def aml_transactions(case_id: str, n: int, accounts: list[dict], base: datetime, count=20):
    rows = []
    for k in range(1, count + 1):
        direction = '转入' if k <= 11 else '转出'
        amount = round(8600 + ((n * 104729 + k * 7919) % 238000) + (k % 7) * 0.37, 2)
        own = accounts[(k - 1) % len(accounts)]
        cp = card('622848', n * 31 + k, k % 9)
        rows.append({
            'transaction_id': f'{case_id}-TX-{k:03d}', 'occurred_at': dt_text(base, k * 19 + (k % 4) * 7),
            'direction': direction, 'amount_cny': amount,
            'account_id': own['entity_id'], 'account_number': own['account_number'],
            'counterparty_name': masked_name(n * 11 + k), 'counterparty_account': cp,
            'counterparty_bank': PROVINCES[(n+k)%len(PROVINCES)][2],
            'transaction_channel': CHANNELS[(n+k)%len(CHANNELS)],
            'currency': 'CNY', 'memo': ['货款','往来款','服务费','借款归还','材料款','劳务费'][k%6],
            'location': PROVINCES[(n+k)%len(PROVINCES)][0] + PROVINCES[(n+k)%len(PROVINCES)][1],
            'evidence_ref': f'{case_id}-FLOW-{k:03d}',
        })
    return rows


def pattern_details(case_id: str, n: int, accounts: list[dict], customers: list[dict], txs: list[dict], base: datetime, fraud=False):
    if fraud:
        ids = ['FRD-MOB-001','FRD-MOB-002','FRD-TXN-001','FRD-TXN-002','FRD-DEV-001','FRD-SCM-002','FRD-DSP-001']
        names = ['异常网络登录','远程控制操作','多头转入集中归集','资金快进快出','共享设备控制多账户','诈骗话术线索','风险提醒后继续交易']
    else:
        primary = AML_THEMES[n % len(AML_THEMES)][1]
        ids = [primary,'ET002','ET008','ET009','ET012','ET022','ET024']
        names = [AML_THEMES[n % len(AML_THEMES)][0],'短期高频交易','交易规模与客户背景不匹配','资金快进快出','关联账户协同交易','交易对手异常分散','多人共用设备或IP']
    total = round(sum(x['amount_cny'] for x in txs), 2)
    out = []
    for j, (etype, name) in enumerate(zip(ids, names), start=1):
        chosen = txs[(j-1)*2:(j-1)*2+4]
        out.append({
            'event_id': f'{case_id}-EV-{j:02d}', 'event_type_id': etype,
            'subject_entity_id': customers[(j-1)%len(customers)]['entity_id'],
            'subject_name': customers[(j-1)%len(customers)]['customer_name'],
            'account_ids': [a['entity_id'] for a in accounts[:3]],
            'account_numbers': [a['account_number'] for a in accounts[:3]],
            'event_start_date': dt_text(base, j * 35), 'event_end_date': dt_text(base, j * 35 + 90),
            'total_amount_cny': round(total * (0.18 + j * 0.04), 2),
            'transaction_ids': [x['transaction_id'] for x in chosen],
            'behavior_chain': f'{name}发生后，资金经关联账户过渡并转向异地交易对手，时间间隔短且用途说明不足',
            'fact': f'观察期内识别到{name}，涉及{len(chosen)}笔重点流水，关联账户与交易对手之间形成连续资金路径',
            'source_fact_summary': f'依据客户资料、账户流水、渠道日志和交易附言交叉核验，{name}具有可定位的时间、金额和账户证据',
            'risk_indicator': f'{etype}：{name}，需核验实际交易背景、资金来源及最终去向',
        })
    return out


def aml_record(seq: int, mode: str):
    n = seq
    case_id = f'AML-{"H" if mode=="HISTORICAL" else "N"}-{seq:06d}'
    theme, code, behavior, crime = AML_THEMES[n % len(AML_THEMES)]
    province, city, institution = PROVINCES[n % len(PROVINCES)]
    base = datetime(2025 + (n % 2), 1 + (n * 5) % 12, 1 + (n * 11) % 25, 8 + n % 10, 0, 0)
    customers = [customer(case_id, n, j, province, city, institution, '高' if j == 1 else '中高') for j in range(1, 4)]
    accounts = aml_accounts(case_id, n, customers, province, institution)
    txs = aml_transactions(case_id, n, accounts, base)
    total_in = round(sum(x['amount_cny'] for x in txs if x['direction']=='转入'), 2)
    total_out = round(sum(x['amount_cny'] for x in txs if x['direction']=='转出'), 2)
    patterns = pattern_details(case_id, n, accounts, customers, txs, base)
    others = [
        {'entity_id': f'{case_id}-DVC-01', 'entity_type': '设备', 'entity_attr_1': f'DVC-{n:06d}-A', 'entity_attr_2': 'Android移动终端', 'entity_attr_3': f'10.{20+n%20}.{n%250}.{20+n%220}', 'entity_attr_4': '关联3个账户'},
        {'entity_id': f'{case_id}-IP-01', 'entity_type': 'IP地址', 'entity_attr_1': f'10.{30+n%20}.{(n*3)%250}.{30+n%210}', 'entity_attr_2': province+city, 'entity_attr_3': '夜间多次登录', 'entity_attr_4': '关联电子银行渠道'},
        {'entity_id': f'{case_id}-ORG-01', 'entity_type': '机构', 'entity_attr_1': f'{city}恒达商贸有限公司', 'entity_attr_2': '交易对手企业', 'entity_attr_3': '批发零售业', 'entity_attr_4': '与主账户存在频繁往来'},
    ]
    description = f'本案涉及{len(customers)}名关联客户和{len(accounts)}个账户。观察期内共核验{len(txs)}笔重点交易，转入{total_in:.2f}元、转出{total_out:.2f}元。{behavior}；同时存在高频交易、关联账户协同操作及异地渠道登录，需要结合合同、发票、物流凭证和资金最终去向开展复核。'
    basic = {
        'case_id': case_id, 'case_name': f'{theme}可疑资金线索{seq:06d}', 'case_description': description,
        'description': description, 'business_domain': '反洗钱', 'business_case_type': '可疑交易监测线索',
        'case_type': '可疑交易监测线索', 'submission_direction': '中国人民银行反洗钱监测分析中心',
        'reporting_direction': '中国人民银行反洗钱监测分析中心', 'case_trigger': f'{code}规则与客户画像偏离指标同时命中',
        'trigger_point': f'{code}规则与客户画像偏离指标同时命中', 'urgency': '较高',
        'report_date': (base + timedelta(days=3)).strftime('%Y-%m-%d'), 'reported_at': (base + timedelta(days=3)).strftime('%Y-%m-%d'),
        'case_status': '历史案例待复核' if mode=='HISTORICAL' else '新增案例待生成报告',
        'business_case_status': '历史案例待复核' if mode=='HISTORICAL' else '新增案例待生成报告',
        'risk_level': '高风险', 'business_risk_level': '高风险', 'suspected_crime_type': crime,
        'suspicious_transaction_codes': '、'.join([code,'ET002','ET009','ET012']),
        'suspicious_transaction_feature_code': '、'.join([code,'ET002','ET009','ET012']),
        'disposition_measures': '已纳入强化监测，限制非柜面大额交易并安排客户回访；复核完成前保留完整交易与渠道日志',
        'disposal_measure': '已纳入强化监测，限制非柜面大额交易并安排客户回访；复核完成前保留完整交易与渠道日志',
        'case_facts': description, 'involved_role': '账户持有人、实际控制人、交易对手',
        'suspected_counterparty_type': '异地个人账户及关联企业账户', 'risk_event_name': theme, 'risk_event_code': code,
        'fund_flow_path': f'多名交易对手→{accounts[0]["account_number"]}→关联账户→异地下游账户',
        'suspicious_patterns': [p['risk_indicator'] for p in patterns],
        'customer_behavior': '客户交易规模明显超过申报收入和既往月均水平，回访时对部分交易用途说明不完整',
        'document_findings': '部分合同签署日期晚于付款日期，发票品名与交易附言不一致，物流凭证尚待补充',
        'asset_disposition': '重点账户已纳入持续监测，未发现有权机关冻结文书',
        'alternative_explanation': '客户称部分款项为临时周转及集中结算，尚需取得对手方合同、发票及交付凭证核实',
        'verification_focus': ['资金来源真实性','交易背景与凭证一致性','关联账户实际控制关系','最终收款人及资金用途'],
        'accounts': accounts, 'other_entities': others, 'transaction_records': txs,
        'suspicious_pattern_details': patterns,
        'event_timeline': [{'event_id': p['event_id'], 'event_type': p['event_type_id'], 'event_code': p['event_type_id'], 'occurred_at': p['event_start_date'], 'subject_entity_id': p['subject_entity_id'], 'object_entity_id': p['account_ids'][0], 'amount_cny': p['total_amount_cny'], 'content': p['fact'], 'evidence_refs': p['transaction_ids']} for p in patterns],
        'relationship_summary': [{'source': c['entity_id'], 'target': accounts[i]['entity_id'], 'type': '持有关系'} for i,c in enumerate(customers)] + [{'source': accounts[0]['entity_id'], 'target': a['entity_id'], 'type':'资金转移关系'} for a in accounts[1:]],
    }
    if mode == 'HISTORICAL':
        tx_detail = '；'.join(f'{x["occurred_at"]}{x["direction"]}{x["amount_cny"]:.2f}元，渠道{x["transaction_channel"]}，对手{x["counterparty_name"]}（{x["counterparty_account"]}），附言{x["memo"]}' for x in txs)
        events = '；'.join(f'{i+1}.{p["event_start_date"]}【{p["event_type_id"]}】{p["fact"]}，涉及金额{p["total_amount_cny"]:.2f}元' for i,p in enumerate(patterns))
        analysis = {'analysis_text1': f'一、线索概况：{description}\n二、客户与账户：主客户{customers[0]["customer_name"]}，证件{customers[0]["id_number"]}，归属机构{institution}；关联客户为{customers[1]["customer_name"]}、{customers[2]["customer_name"]}，共涉及{len(accounts)}个账户。\n三、重点流水：{tx_detail}\n四、风险事件：{events}\n五、研判意见：交易金额、频次、地域和资金路径与客户职业、申报收入及既往交易习惯不匹配，需核验交易背景、账户控制关系及资金最终去向。',
                    'analysis_text2': f'复核要点包括合同发票一致性、交易对手实际经营情况、登录设备与IP归属、资金在关联账户间的停留时间及最终收款人。当前识别到{len(patterns)}项风险事件，所有结论均以银行流水、客户资料和外部核验结果为依据。'}
        return {'basic_info': basic, 'customers': customers, 'analysis_texts': analysis}
    feature = {
        'schema_version': '2.0', 'case_id': case_id, 'feature_level': 'CASE', 'stat_window_days': 30,
        'stat_window_start': base.strftime('%Y-%m-%d'), 'stat_window_end': (base+timedelta(days=3)).strftime('%Y-%m-%d'),
        'amount_currency_basis': 'CNY', 'amount_unit': '元', 'ratio_scale': '0-1',
        'data_quality_note': '流水、客户、账户及渠道日志字段完整，交易对手资料待进一步核验',
        'transactions': txs,
        'customer_features': [{
            'customer_id': c['entity_id'], 'customer_name': c['customer_name'], 'account_ids': [a['entity_id'] for a in accounts if a['holder_entity_id']==c['entity_id']],
            'transaction_count': len(txs), 'in_amount_cny': total_in, 'out_amount_cny': total_out,
            'night_transaction_ratio': round(0.24+(n%20)/100,2), 'cross_region_ratio': round(0.42+(n%15)/100,2),
            'counterparty_count': 14+n%12, 'rapid_transfer_ratio': round(0.61+(n%18)/100,2),
            'risk_tags': [theme,'交易规模与客户画像不匹配','关联账户协同交易']
        } for c in customers],
        'account_features': [{'account_id': a['entity_id'], 'account_number': a['account_number'], 'in_count': 11+n%7, 'out_count': 9+n%6, 'turnover_cny': round(total_in+total_out,2), 'max_single_amount_cny': max(x['amount_cny'] for x in txs), 'active_regions': 4+n%5, 'risk_tags': a['risk_tags']} for a in accounts],
        'event_features': patterns,
    }
    return {'basic_info': basic, 'customers': customers, 'transaction_features': feature}


def fraud_common(seq: int, mode: str):
    n = seq
    case_id = f'FRD-{"H" if mode=="HISTORICAL" else "N"}-{seq:06d}'
    theme, theme_code, behavior = FRAUD_THEMES[n % len(FRAUD_THEMES)]
    province, city, institution = PROVINCES[n % len(PROVINCES)]
    base = datetime(2025 + n % 2, 1 + (n * 7) % 12, 1 + (n * 13) % 25, 9 + n % 8, 0, 0)
    strict = mode == 'NEW'
    customers = []
    accounts = []
    for j in range(1, 4):
        name = masked_name(n * 9 + j)
        cid = f'{case_id}-CUS-{j:02d}'
        c = {'entity_id':cid,'customer_name':name,'customer_number':f'F{202500000000+n*10+j}','id_type':'居民身份证','id_number':person_id(n+30000,j),'occupation_industry':OCCUPATIONS[(n+j)%len(OCCUPATIONS)],'nationality':'中国','province':province,'institution':institution,'address':f'{province}{city}康宁路{30+(n+j)%160}号','customer_risk_level':'高' if j==1 else '中高'}
        if not strict:
            c.update({'mobile':f'18{n%8}****{(n*41+j)%10000:04d}','residence':f'{province}{city}康宁路{30+(n+j)%160}号','customer_since':f'{2016+n%8}-{1+j:02d}-{5+j:02d}','declared_income_cny':90000+(n*379+j*11000)%650000,'role':'涉险账户持有人' if j==1 else '关联付款人'})
        customers.append(c)
        suffix = (n*53+j*17)%10000
        bankcard = f'621799{(n*137+j*19)%100000000:08d}{suffix:04d}'
        a = {'entity_id':f'{case_id}-ACC-{j:02d}','account_type':'个人I类账户' if j<3 else '个人II类账户','holder_name':name,'holder_id_type':'居民身份证','holder_id_number':c['id_number'],'account_open_date':f'{2018+n%6}-{1+(n+j)%12:02d}-{1+(n*3+j)%27:02d}','province':province,'institution':institution,'account_number':bankcard[:8]+'****'+bankcard[-4:],'bank_card_type':'借记卡','bank_info':institution,'bank_card_number':bankcard}
        accounts.append(a)
    devices = [
        {'设备号':f'DVC-{n:06d}-A','设备名称':'Android移动终端','ip地址':f'10.{50+n%30}.{(n*3)%240}.{20+n%220}'},
        {'设备号':f'DVC-{n:06d}-B','设备名称':'iOS移动终端','ip地址':f'10.{80+n%20}.{(n*7)%240}.{30+n%210}'},
        {'设备号':f'DVC-{n:06d}-C','设备名称':'Web浏览器终端','ip地址':f'172.20.{n%240}.{40+n%200}'},
    ]
    amount = round(180000 + (n * 9187) % 980000 + (n%7)*0.45,2)
    events = [
        {'发生时间':dt_text(base,0),'类型':'设备操作','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}由设备{devices[0]["设备号"]}通过IP {devices[0]["ip地址"]}异地登录，登录前30日未见该设备。'},
        {'发生时间':dt_text(base,12),'类型':'app操作','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}在设备{devices[0]["设备号"]}新增4名收款人并将单日转账限额上调至50万元，身份核验连续失败2次后通过。'},
        {'发生时间':dt_text(base,28),'类型':'流水','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}收到6名异地付款人分散转入共{amount*0.63:.2f}元，附言包括保证金、任务款和投资充值，交易背景尚未核实。'},
        {'发生时间':dt_text(base,49),'类型':'规则命中','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}命中{theme_code}及FRD-TXN-002，入账后21分钟内向3个下游账户转出{amount*0.58:.2f}元。'},
        {'发生时间':dt_text(base,72),'类型':'设备操作','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}由设备{devices[1]["设备号"]}通过IP {devices[1]["ip地址"]}再次登录，两台设备在不同城市交替操作。'},
        {'发生时间':dt_text(base,91),'类型':'流水','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}继续向尾号{accounts[1]["bank_card_number"][-4:]}和尾号{accounts[2]["bank_card_number"][-4:]}拆分转出{amount*0.31:.2f}元，资金停留时间不足两小时。'},
        {'发生时间':dt_text(base,125),'类型':'限额情况','具体内容':f'主体账户尾号{accounts[0]["bank_card_number"][-4:]}已触发非柜面限额和强化验证，客户回访称曾按陌生来电指引操作，仍需核验实际控制人和下游资金去向。'},
    ]
    description = f'{behavior}。本案关联3名客户、3个账户和3台终端，观察到7个连续事件；涉及金额{amount:.2f}元，其中快速转出约{amount*0.89:.2f}元。需核验客户是否本人操作、付款人陈述、设备实际控制人及下游账户资金去向。'
    basic = {'case_id':case_id,'case_name':f'{theme}风险线索{seq:06d}','case_description':description,'business_domain':'反欺诈','case_type':'电信网络诈骗风险线索','submission_direction':'公安机关反诈协查及行内风险处置','case_trigger':f'{theme_code}与FRD-TXN-002规则联合命中','urgency':'紧急','report_date':(base+timedelta(days=1)).strftime('%Y-%m-%d'),'case_status':'历史案例待复核' if mode=='HISTORICAL' else '新增案例待生成报告','risk_level':'高风险','suspected_crime_type':'涉嫌诈骗及掩饰、隐瞒犯罪所得','suspicious_transaction_codes':f'{theme_code}、FRD-TXN-002、FRD-DEV-001','disposition_measures':'已采取交易提醒、非柜面限额和强化身份验证；继续核验付款人、设备控制人及下游账户'}
    if not strict:
        txs=[]
        for k in range(1,13):
            txs.append({'transaction_id':f'{case_id}-TX-{k:03d}','occurred_at':dt_text(base,22+k*9),'direction':'转入' if k<=6 else '转出','amount_cny':round(amount*(0.035+(k%5)*0.011),2),'account_id':accounts[0]['entity_id'],'account_number':accounts[0]['account_number'],'counterparty_name':masked_name(n*17+k),'counterparty_account':card('622848',n*21+k,k%9),'transaction_mode':CHANNELS[(n+k)%len(CHANNELS)],'memo':['保证金','任务款','投资充值','货款','个人转账'][k%5],'device_id':devices[k%3]['设备号'],'ip_address':devices[k%3]['ip地址'],'location':PROVINCES[(n+k)%len(PROVINCES)][0]+PROVINCES[(n+k)%len(PROVINCES)][1],'risk_tag':'异地多头转入' if k<=6 else '入账后快速转出'})
        for j,a in enumerate(accounts):
            a.update({'open_date':a['account_open_date'],'currency':'CNY','current_balance_cny':round(5000+(n*631+j*901)%120000,2),'status':'风险监测中','usual_use':'工资及日常消费','recent_30d_in_count':35+n%18,'recent_30d_out_count':28+n%14,'recent_30d_turnover_cny':round(amount*1.72,2),'linked_device_ids':[d['设备号'] for d in devices[:2]],'risk_tags':[theme,'资金快进快出','异地设备登录'],'recent_transactions':txs})
        details = pattern_details(case_id,n,accounts,customers,txs,base,True)
        basic.update({'渠道':'交易监测','channel':'交易监测','fraud_type':theme,'source_channel':'交易监测','case_date':base.strftime('%Y-%m-%d'),'involved_amount_cny':amount,'suspected_loss_cny':round(amount*0.58,2),'victim_count':4+n%9,'event_summary':description,'evidence_summary':['客户身份与开户资料','电子银行登录设备及IP日志','重点交易流水','客户回访记录','限额与验证处置记录'],'control_actions':['交易提醒','非柜面限额','强化身份验证','持续监测'],'verification_focus':['客户是否本人操作','付款人陈述与流水对应','设备实际控制人','下游账户资金去向'],'conclusion_boundary':'规则命中和客户陈述构成待核验风险线索，最终结论以外部协查和证据核实结果为准','relationship_summary':[{'source':customers[i]['entity_id'],'target':accounts[i]['entity_id'],'type':'持有关系'} for i in range(3)],'suspicious_pattern_details':details})
        text = f'一、线索来源：{basic["case_trigger"]}。二、客户与账户：主客户{customers[0]["customer_name"]}，证件{customers[0]["id_number"]}，主体账户{accounts[0]["account_number"]}，归属机构{institution}；另有关联客户{customers[1]["customer_name"]}、{customers[2]["customer_name"]}。三、事实经过：' + '；'.join(x['具体内容'] for x in events) + '。四、交易明细：' + '；'.join(f'{x["occurred_at"]}{x["direction"]}{x["amount_cny"]:.2f}元，对手{x["counterparty_name"]}，渠道{x["transaction_mode"]}，附言{x["memo"]}' for x in txs) + f'。五、研判：{description} 六、处置建议：维持限额和强化验证，核验付款人陈述、设备控制关系及下游资金路径。'
        return {'basic_info':basic,'customers':customers,'accounts':accounts,'devices':devices,'text_analysis':{'text':text,'analysis_version':'2.0','event_count':7,'evidence_refs':[f'{case_id}-FLOW',f'{case_id}-DEVLOG',f'{case_id}-CALLBACK']}}
    return {'basic_info':basic,'customers':customers,'accounts':accounts,'devices':devices,'event_chain':events}


def write_csv(path: Path, headers: list[str], records):
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=headers)
        w.writeheader()
        for rec in records:
            w.writerow({h:jd(rec[h]) for h in headers})


def validate_file(path: Path, headers: list[str], expected: int):
    raw=path.read_text(encoding='utf-8-sig')
    hit=FORBIDDEN.search(raw)
    if hit: raise AssertionError(f'{path.name} contains forbidden text: {hit.group()}')
    ids=[]
    with path.open(encoding='utf-8-sig',newline='') as f:
        r=csv.DictReader(f)
        assert r.fieldnames==headers,(path,r.fieldnames)
        for row in r:
            decoded={k:json.loads(v) for k,v in row.items()}
            ids.append(decoded['basic_info']['case_id'])
            assert decoded['customers']
    assert len(ids)==expected,(path,len(ids),expected)
    assert len(ids)==len(set(ids)),f'duplicate IDs in {path}'
    return {'file':str(path),'rows':len(ids),'first':ids[0],'last':ids[-1],'bytes':path.stat().st_size}


def main():
    OUTPUT.mkdir(parents=True,exist_ok=True)
    specs=[
        ('aml_historical_cases_1000_20260918.csv',['basic_info','customers','analysis_texts'],(aml_record(i,'HISTORICAL') for i in range(7101,8101)),1000),
        ('aml_new_cases_200_20260918.csv',['basic_info','customers','transaction_features'],(aml_record(i,'NEW') for i in range(2031,2231)),200),
        ('anti_fraud_historical_cases_500_20260918.csv',['basic_info','customers','accounts','devices','text_analysis'],(fraud_common(i,'HISTORICAL') for i in range(2021,2521)),500),
        ('anti_fraud_new_cases_100_20260918.csv',['basic_info','customers','accounts','devices','event_chain'],(fraud_common(i,'NEW') for i in range(1004,1104)),100),
    ]
    manifest=[]
    for name,headers,records,count in specs:
        path=OUTPUT/name
        write_csv(path,headers,records)
        manifest.append(validate_file(path,headers,count))
    print(json.dumps(manifest,ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
