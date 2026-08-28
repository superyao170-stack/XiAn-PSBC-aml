import fs from 'node:fs';
import path from 'node:path';

const output = path.resolve(process.argv[2] || 'outputs/historical_cases_enriched_7000.csv');
const count = Number(process.argv[3] || 7000);
const chunkSize = Number(process.argv[4] || 100);
const chunkDirectory = output.replace(/\.[^.]+$/, '') + `_chunks_${chunkSize}`;
const knowledgeBasePath = path.resolve(
  'workers/structured-case-identification/xian_modules/data/kb/risk_event_knowledge_base.json'
);
const knowledgeBase = JSON.parse(fs.readFileSync(knowledgeBasePath, 'utf8'));
const knowledgeById = new Map(knowledgeBase.event_types.map(item => [item.id, item]));

const surnames = ['赵','钱','孙','李','周','吴','郑','王','冯','陈','褚','卫','蒋','沈','韩','杨','朱','秦','尤','许'];
const givenNames = ['明远','思涵','建国','雅婷','泽宇','欣怡','志强','雨桐','博文','若兰'];
const cities = ['西安市','重庆市','成都市','武汉市','南京市','杭州市','广州市','郑州市'];
const branches = ['高新支行','曲江支行','渝中支行','武昌支行','锦江支行','西湖支行'];
const industries = ['供应链服务','建筑工程','商贸批发','物流运输','信息技术','医疗器械','农业发展','文化传媒'];
const counterparties = ['华辰供应链有限公司','盛达咨询服务有限公司','恒信商贸有限公司','嘉禾物流有限公司','安泰工程有限公司','瑞丰科技有限公司'];

const patternDefinitions = [
  ['ET009','多名交易对手转入的资金在短时间内由中转账户连续划出，期末余额接近零','资金从收款账户归集至中转账户后分散转往关联账户','资金停留时间、入账后转出比例和期末余额'],
  ['ET014','多名付款方资金集中进入归集账户，随后被拆分至多个不同收款账户','分散来源资金先集中归集，再通过中转账户向多个账户分散转出','付款方数量、分散转出笔数和账户留存比例'],
  ['ET015','多个关联账户分散转入后，资金被集中转入少数核心账户','分散账户向中转账户转入，随后汇集至核心收款账户','转入账户数量、集中度和资金汇集时长'],
  ['ET012','多个关联账户使用相同设备与网络地址协同完成资金转移','关联客户账户交叉收付并共同使用登录设备，形成协同资金链','共享设备账户数、共同网络地址和交叉交易次数'],
  ['ET027','新设企业缺少工资、社保和日常经营支出，却承接并快速划转大额服务费','企业账户收到服务费后经关联账户过渡并转至个人账户','成立时间、经营性支出占比和公私转账金额'],
  ['ET028','合同、发票或物流信息与逐笔付款用途和交易对手不能相互印证','贸易款进入结算账户后以不一致用途转往关联服务商','单证差异、付款批次和实际资金去向'],
  ['ET053','多笔交易金额持续贴近机构内部监测阈值且由同一控制网络操作','付款账户分笔向中转账户转账，再由中转账户统一划出','阈值附近交易笔数、时间间隔和共同控制信息'],
  ['ET024','多个客户账户反复使用同一设备和网络地址在相近时段操作','关联账户通过共同设备登录并依次完成收款、过渡和转出','共享设备数、共享网络地址和操作时间重合度'],
  ['ET018','企业经营收入频繁转入实际控制人及关联个人账户且用途凭证不足','对公账户收到项目款后经中转账户持续向个人账户划款','公转私金额、交易频率和用途凭证完整度'],
  ['ET020','资金经多个关联账户转移后回到原付款方或其控制账户','原始资金经中转账户和关联企业账户转移后形成闭环回流','回流金额、闭环层级和资金周转时间'],
  ['ET029','跨境资金通过多个境内外账户分层绕转，最终回到关联受益人','境内账户经中转账户向境外收款方付款，再由关联账户承接','跨境层级、收付款关系和受益人关联性'],
  ['ET032','贷款资金到账后未用于约定经营用途，而是转往关联账户并部分回流','贷款进入借款账户后经供应商账户转至关联方并回到控制人账户','贷款用途、关联转账金额和回流比例'],
  ['ET047','长期低活跃账户在短期内突然发生多笔大额收付并快速清空余额','休眠账户突发收款后连续转往多个关联账户','休眠时长、突发交易金额和余额变化'],
  ['ET063','关联企业对开金额相近的发票并在结算后将资金转回原付款方','企业间以采购名义付款后经服务商账户向原控制网络回流','发票对应关系、对开金额和回流路径'],
  ['ET097','发票结算资金经多个账户拆分后回到开票方关联人员账户','付款账户完成发票结算后，资金经中转账户回流至关联个人','发票金额、拆分笔数和最终受益人'],
  ['ET104','项目中标款支付后，以咨询费或服务费名义向项目相关人员账户返点','工程款进入项目账户后经服务公司向相关个人账户回流','中标金额、服务费比例和相关人员关联关系']
].map(([id, fact, chain, indicator]) => {
  const metadata = knowledgeById.get(id);
  if (!metadata) throw new Error(`知识库缺少事件 ${id}`);
  return { id, name: metadata.name, category: metadata.category, fact, chain, indicator };
});

const json = value => JSON.stringify(value);
const pad = (value, width = 6) => String(value).padStart(width, '0');
const money = value => Math.round(value * 100) / 100;
const cell = value => `"${String(value).replaceAll('"', '""')}"`;
const isoDate = date => date.toISOString().slice(0, 10);
const addDays = (date, days) => new Date(date.getTime() + days * 86400000);
const chineseDate = value => {
  const [year, month, day] = value.split('-');
  return `${year}年${Number(month)}月${Number(day)}日`;
};
const accountNo = (caseIndex, accountIndex) =>
  `622202${String(100000000000 + caseIndex * 17 + accountIndex).slice(-12)}`;
const identityNo = (caseIndex, personIndex) =>
  `6101${1980 + ((caseIndex + personIndex) % 25)}${pad((caseIndex * 97 + personIndex * 1709) % 1000000)}${personIndex}`;

function customer(caseIndex, personIndex, city) {
  const name = `${surnames[(caseIndex + personIndex * 3) % surnames.length]}${givenNames[(caseIndex * 3 + personIndex) % givenNames.length]}`;
  const identity = identityNo(caseIndex, personIndex + 1);
  return {
    entity_id: `CUST-HIST-${pad(caseIndex)}-${String.fromCharCode(65 + personIndex)}`,
    customer_name: name,
    customer_number: `C${String(1000000000 + caseIndex * 10 + personIndex).slice(-10)}`,
    id_type: '居民身份证', id_number: identity,
    occupation_industry: personIndex === 0 ? industries[caseIndex % industries.length] : '企业财务与资金管理',
    nationality: '中国',
    address: `${city}${['高新区','经开区','中心城区'][personIndex]}${['科技路','建设路','金融街'][caseIndex % 3]}${20 + (caseIndex % 70)}号`,
    customer_risk_level: personIndex === 0 ? '高风险' : '较高风险',
    legal_representative_name: personIndex === 0 ? name : '',
    legal_representative_id_type: personIndex === 0 ? '居民身份证' : '',
    legal_representative_id_number: personIndex === 0 ? identity : '',
    controller_name: personIndex === 0 ? name : `${surnames[(caseIndex + 5) % surnames.length]}${givenNames[(caseIndex + 4) % givenNames.length]}`,
    controller_id_type: '居民身份证',
    controller_id_number: identityNo(caseIndex, personIndex + 7)
  };
}

function account(caseIndex, accountIndex, holder, city, openedAt) {
  const number = accountNo(caseIndex, accountIndex);
  return {
    entity_id: `ACCT-HIST-${pad(caseIndex)}-${pad(accountIndex, 2)}`,
    account_type: accountIndex % 3 === 0 ? '对公基本存款账户' : '个人活期结算账户',
    holder_name: holder.customer_name, holder_id_type: holder.id_type, holder_id_number: holder.id_number,
    account_open_date: isoDate(openedAt), account_close_date: '', account_number: number,
    bank_card_type: accountIndex % 3 === 0 ? '单位结算卡' : '借记卡', bank_card_number: number,
    institution: `中国邮政储蓄银行${city}${branches[(caseIndex + accountIndex) % branches.length]}`,
    bank_info: `中国邮政储蓄银行${city}${branches[(caseIndex + accountIndex) % branches.length]}`
  };
}

function transaction(caseId, blockIndex, txIndex, from, to, amount, occurredAt, caseIndex) {
  const nightTransaction = (caseIndex + blockIndex + txIndex) % 6 === 0;
  const hour = nightTransaction ? 23 : 8 + ((txIndex * 2 + blockIndex) % 12);
  return {
    transaction_id: `TX-${caseId}-${pad(blockIndex + 1, 2)}-${pad(txIndex + 1, 3)}`,
    transaction_date: isoDate(occurredAt),
    transaction_time: `${pad(hour, 2)}:${pad(11 + txIndex, 2)}:${pad(7 + txIndex, 2)}`,
    debit_account_id: from.entity_id, debit_account_number: from.account_number,
    credit_account_id: to.entity_id, credit_account_number: to.account_number,
    amount_cny: amount,
    transaction_channel: txIndex === 3 ? '手机银行' : txIndex % 2 ? '网上银行' : '柜面转账',
    counterparty_name: counterparties[(caseIndex + blockIndex + txIndex) % counterparties.length],
    purpose: ['项目款结算','供应链采购款','咨询服务费','劳务服务费','往来款'][txIndex],
    device_id: `DEV-${caseId}-${blockIndex + 1}`,
    ip_address: `172.${20 + (caseIndex % 180)}.${blockIndex + 10}.${txIndex + 21}`
  };
}

function buildEventBlock(caseId, caseIndex, blockIndex, pattern, eventAccounts, customers, eventStart) {
  const baseAmount = 680000 + ((caseIndex * 7919 + blockIndex * 17311) % 2320000);
  const transactionCount = 3 + ((caseIndex * 7 + blockIndex * 5 + Math.floor(caseIndex / 6)) % 6);
  const rawShares = Array.from({ length: transactionCount }, (_, index) => transactionCount + 2 - index);
  const shareTotal = rawShares.reduce((sum, value) => sum + value, 0);
  const shares = rawShares.map(value => value / shareTotal);
  const route = Array.from({ length: transactionCount }, (_, txIndex) => {
    const fromIndex = txIndex % eventAccounts.length;
    const toIndex = (fromIndex + 1 + (txIndex >= eventAccounts.length ? 1 : 0)) % eventAccounts.length;
    return [eventAccounts[fromIndex], eventAccounts[toIndex]];
  });
  const transactions = route.map(([from, to], txIndex) => transaction(
    caseId, blockIndex, txIndex, from, to,
    money(baseAmount * shares[txIndex]), addDays(eventStart, txIndex), caseIndex
  ));
  const totalAmount = money(transactions.reduce((sum, item) => sum + item.amount_cny, 0));
  const subject = customers[blockIndex % customers.length].customer_name;
  const routeDescription = transactions.map(item =>
    `${item.debit_account_number}于${item.transaction_date}向${item.credit_account_number}转账${item.amount_cny.toFixed(2)}元`
  ).join('，随后');
  return {
    event_id: pad(blockIndex + 1, 7),
    event_name: `${subject}控制的账户实施${pattern.name}相关行为`,
    event_type_id: pattern.id, event_type: pattern.name, category: pattern.category,
    start_date: transactions[0].transaction_date, end_date: transactions.at(-1).transaction_date,
    total_amount: totalAmount, accounts: eventAccounts, transactions,
    behavior_chain: `${pattern.chain}。具体顺序为：${routeDescription}。`,
    fact: pattern.fact,
    indicator: `${pattern.indicator}；累计金额${totalAmount.toFixed(2)}元，交易${transactions.length}笔，资金最长停留${2 + ((caseIndex + blockIndex) % 18)}小时，共同设备关联${2 + ((caseIndex + blockIndex) % 4)}个账户`,
    evidence: `交易流水${transactions.map(item => item.transaction_id).join('、')}可与账户开户资料、登录设备日志、付款用途及业务凭证逐项对应；其中${transactions[0].transaction_id}至${transactions.at(-1).transaction_id}共同构成该模式的完整资金链。`
  };
}

function suspiciousReports(basicInfo, customers, accounts, blocks) {
  const customerText = customers.map((item, index) =>
    `${index + 1}. ${item.customer_name}（${item.entity_id}），客户号${item.customer_number}，证件类型及号码为${item.id_type}${item.id_number}，职业或经营行业为${item.occupation_industry}，常住或经营地址为${item.address}，当前客户风险等级为${item.customer_risk_level}，登记实际控制人为${item.controller_name}。`
  ).join('\n');
  const accountText = accounts.map((item, index) =>
    `${index + 1}. ${item.holder_name}名下账户${item.entity_id}，账号${item.account_number}，账户类型为${item.account_type}，于${chineseDate(item.account_open_date)}在${item.institution}开立，账户载体为${item.bank_card_type}。`
  ).join('\n');
  const transactionText = blocks.flatMap(block => block.transactions).map((item, index) =>
    `${index + 1}. ${item.transaction_date} ${item.transaction_time}，流水${item.transaction_id}，付款账号${item.debit_account_number}通过${item.transaction_channel}向收款账号${item.credit_account_number}转账${item.amount_cny.toFixed(2)}元，用途记载为${item.purpose}，交易对手名称为${item.counterparty_name}，操作设备${item.device_id}，网络地址${item.ip_address}。`
  ).join('\n');
  const fundsText = blocks.map((block, index) => {
    const transactions = block.transactions.map(item =>
      `${item.transaction_id}（${item.transaction_date}，${item.debit_account_number}向${item.credit_account_number}转账${item.amount_cny.toFixed(2)}元）`
    ).join('；');
    return `${index + 1}. ${block.event_name}。该组事实对应${block.event_type}，独立观察期间为${block.start_date}至${block.end_date}，独立涉及账户为${block.accounts.map(item => `${item.account_number}（${item.entity_id}）`).join('、')}，独立统计交易${block.transactions.length}笔、金额${block.total_amount.toFixed(2)}元。行为链表现为：${block.behavior_chain}可复核逐笔流水为：${transactions}。${block.fact}；结构化特征为${block.indicator}。证据方面，${block.evidence}该模式单独统计、单独核验，不与其他模式合并计算。`;
  }).join('\n');
  const counterpartyText = blocks.map((block, index) =>
    `${index + 1}. ${block.event_type}对应交易对手包括${[...new Set(block.transactions.map(item => item.counterparty_name))].join('、')}；涉及日期${block.start_date}至${block.end_date}，对应金额${block.total_amount.toFixed(2)}元，需结合合同、发票、物流或服务成果核验交易背景。`
  ).join('\n');
  const summary = basicInfo.structured_transaction_summary;
  const analysisText1 = `【一】发现情况
本案例由日常交易监测和客户风险复核触发。观察窗口为${basicInfo.stat_window_start}至${basicInfo.stat_window_end}，涉及${summary.customer_count}名客户、${summary.account_count}个账户及${summary.tx_cnt_30d}笔重点交易，收付累计金额${summary.tx_cirmb_amt_sum_30d.toFixed(2)}元。现有事实呈现${blocks.map(item => item.event_type).join('、')}等${blocks.length}组相互独立的可疑行为特征。上述内容属于待核验风险事实，不构成对违法犯罪的认定。

【二】客户基本情况
${customerText}
客户与账户通过稳定实体标识对应，未将同名主体自行合并。现有客户资料能够支持身份、行业、地区和风险等级核验，收入规模、历史经营基线及受益所有权证明仍需进一步补充。

【三】开户与整体交易
本案账户情况如下：
${accountText}
观察期内逐笔重点交易如下：
${transactionText}
全案观察期口径共发生${summary.tx_cnt_30d}笔重点收付，其中收款${summary.in_tx_cnt_30d}笔、付款${summary.out_tx_cnt_30d}笔，累计金额${summary.tx_cirmb_amt_sum_30d.toFixed(2)}元；夜间交易${summary.night_tx_cnt_30d}笔。上述流水均保留交易日期、时间、付款账户、收款账户、金额、渠道、用途、交易对手、设备和网络地址，可按流水号逐项复核。

【四】资金来源与去向
${fundsText}

【五】客户主体分析
${customers.map(item => `${item.customer_name}（${item.entity_id}）`).join('、')}在同一观察窗口内涉及上述账户资金链，交易规模、夜间操作、共享设备和账户间往来相互印证，提示账户可能被用于资金归集、中转或回流。现有材料同时不能排除集中结算、项目周期性付款、关联企业正常往来等替代解释。机构已提高客户风险等级、发起增强尽职调查并设置交易复核；后续应核对经营规模、合同发票、物流或服务成果、受益所有人、账户实际控制关系和资金最终用途。`;

  const analysisText2 = `【一】案例概述
${basicInfo.case_description}本案当前处于风险复核阶段，${blocks.length}组可疑模式均以独立账户集合、独立日期范围、独立金额及逐笔流水为依据，未将不同模式合并统计。

【二】客户主体分析
主要关注客户为${customers.map(item => `${item.customer_name}（${item.entity_id}，${item.occupation_industry}，${item.customer_risk_level}）`).join('、')}。客户名下账户开户资料齐备，但现有交易规模和多段资金链需要与其实际经营范围、收入来源、账户声明用途及受益所有权材料进一步比对。基于现有资料只能形成风险关注方向，不能据此认定客户实施违法犯罪行为。

【三】客户交易分析
1. 资金交易方向
${fundsText}

2. 账号方向
${blocks.length}组模式分别使用${blocks.map((block, index) => `第${index + 1}组账户${block.accounts.map(item => item.account_number).join('、')}（${block.start_date}至${block.end_date}，${block.total_amount.toFixed(2)}元）`).join('；')}。账户组之间不交叉代替，各组行为链均可由对应流水、开户资料和设备日志复核。账户间的频繁过渡和部分回流提示资金周转倾向，但账户实际控制关系仍需通过登录日志、柜面凭证和客户访谈确认。

3. 交易对手方向
${counterpartyText}
交易对手覆盖多个行业名称，单凭名称及交易次数不能认定其为空壳主体或异常对手；应逐户核验工商信息、合同履约、发票开具、物流轨迹、服务成果和最终受益人。

4. 司法查询方向
现有材料未提供司法查询、协查、冻结、止付或有权机关认定结果，因此本方向暂不作确定判断。后续如取得相关资料，应与本案逐笔流水、账户控制关系和交易用途分别核验，不得以单一查询结果替代完整事实审查。

【四】综合分析
有事实支持的风险指标包括：观察期内${summary.tx_cnt_30d}笔逐笔流水及${summary.tx_cirmb_amt_sum_30d.toFixed(2)}元累计交易均可定位到具体账户；${blocks.length}组模式分别记录了日期、金额、行为链和证据链；夜间交易${summary.night_tx_cnt_30d}笔；共享设备${basicInfo.shared_device_id}与多个账户存在关联。具有一定支持的候选疑点为资金归集后分散、关联账户过渡、异常公转私或回流等行为，具体对应${blocks.map(item => item.event_type).join('、')}。当前不能确认相关资金具有违法来源，也不能排除项目结算、供应链付款、关联企业往来和短期经营周转等正常解释。关键数据缺口包括完整账户余额序列、合同发票原件、物流或服务成果、客户历史基线、交易对手尽调、设备实际使用人和外部调查反馈。建议逐笔核验本报告列明的流水，分别复核每组模式的账户、日期、金额和行为链，补充增强尽职调查材料，并将核验结果提交有权审批岗复核。`;

  return { analysis_text1: analysisText1, analysis_text2: analysisText2 };
}

function buildRecord(caseIndex) {
  const caseId = `AML-HIST-${pad(caseIndex)}`;
  const city = cities[caseIndex % cities.length];
  const caseStart = new Date(Date.UTC(2023 + (caseIndex % 2), caseIndex % 12, 3 + (caseIndex % 20)));
  const patternCount = 1 + ((caseIndex * 7) % 6);
  const selected = Array.from({ length: patternCount }, (_, offset) =>
    patternDefinitions[(caseIndex * 5 + offset * 3) % patternDefinitions.length]
  );
  const accountGroupSizes = Array.from({ length: patternCount }, (_, index) =>
    2 + ((caseIndex + index * 2) % 3)
  );
  const accountCount = accountGroupSizes.reduce((sum, value) => sum + value, 0);
  const minimumCustomerCount = accountCount >= 12 ? 2 : 1;
  const maximumCustomerCount = Math.min(5, Math.max(minimumCustomerCount, Math.floor(accountCount / 2)));
  const customerCount = minimumCustomerCount
    + ((caseIndex * 37 + patternCount * 13) % (maximumCustomerCount - minimumCustomerCount + 1));
  const customers = Array.from({ length: customerCount }, (_, index) => customer(caseIndex, index, city));
  const accounts = Array.from({ length: accountCount }, (_, index) =>
    account(caseIndex, index, customers[index % customers.length], city, addDays(caseStart, -420 + index * 9))
  );
  let accountOffset = 0;
  const blocks = selected.map((pattern, index) => {
    const eventAccounts = accounts.slice(accountOffset, accountOffset + accountGroupSizes[index]);
    accountOffset += accountGroupSizes[index];
    return buildEventBlock(
      caseId, caseIndex, index, pattern, eventAccounts, customers, addDays(caseStart, index * 10)
    );
  });
  // Historical framework extraction does not project an evidence layer.
  // Transaction facts remain in the reports and pattern metadata only.
  const evidences = [];
  const totalAmount = money(blocks.reduce((sum, block) => sum + block.total_amount, 0));
  const transactionCount = blocks.reduce((sum, block) => sum + block.transactions.length, 0);
  const nightTransactionCount = blocks.flatMap(block => block.transactions)
    .filter(item => Number(item.transaction_time.slice(0, 2)) >= 22).length;
  const caseEnd = new Date(Math.max(...blocks.map(block => new Date(block.end_date).getTime())));
  const basicInfo = {
    case_id: caseId,
    case_name: `${industries[caseIndex % industries.length]}领域${selected[0].name}风险案例${pad(caseIndex)}`,
    case_description: `本案涉及${customers.map(item => item.customer_name).join('、')}等${customers.length}名客户，${isoDate(caseStart)}至${isoDate(caseEnd)}期间，${accounts.length}个关联账户累计发生${transactionCount}笔重点交易、金额${totalAmount.toFixed(2)}元，呈现${selected.map(item => item.name).join('、')}等${blocks.length}组相互独立的可疑模式；相关资金形成可逐笔复核的多段行为链，现已提高客户风险等级并开展增强尽职调查。`,
    business_domain: '反洗钱与资金链风险识别', case_type: '多模式异常资金链历史案例',
    submission_direction: '01', case_trigger: '01', urgency: caseIndex % 3 === 0 ? '03' : '02',
    report_date: isoDate(addDays(caseEnd, 12)), case_status: '03',
    risk_level: caseIndex % 5 === 0 ? '03' : '02', suspected_crime_type: caseIndex % 4 === 0 ? '2003' : '1002',
    suspicious_transaction_codes: selected.map(item => item.id).join('、'),
    suspicious_pattern_details: blocks.map((block, index) => ({
      event_id: block.event_id,
      event_type_id: block.event_type_id,
      event_type: block.event_type,
      event_name: block.event_name,
      subject_entity_id: customers[index % customers.length].entity_id,
      subject_name: customers[index % customers.length].customer_name,
      account_ids: block.accounts.map(item => item.entity_id),
      account_numbers: block.accounts.map(item => item.account_number),
      event_start_date: block.start_date,
      event_end_date: block.end_date,
      total_amount_cny: block.total_amount,
      transaction_ids: block.transactions.map(item => item.transaction_id),
      behavior_chain: block.behavior_chain,
      fact: block.fact,
      risk_indicator: block.indicator,
      source_fact_summary: block.evidence,
    })),
    disposition_measures: '提高客户风险等级、发起增强尽职调查、设置交易复核',
    province: city, stat_window_start: isoDate(caseStart), stat_window_end: isoDate(caseEnd),
    shared_device_id: `DEV-${caseId}-SHARED`,
    structured_transaction_summary: {
      stat_window_days: Math.round((caseEnd.getTime() - caseStart.getTime()) / 86400000) + 1,
      customer_count: customers.length, account_count: accounts.length,
      tx_cnt_30d: transactionCount,
      in_tx_cnt_30d: Math.ceil(transactionCount / 2), out_tx_cnt_30d: Math.floor(transactionCount / 2), tx_cirmb_amt_sum_30d: totalAmount,
      in_cirmb_amt_sum_30d: money(totalAmount * 0.51), out_cirmb_amt_sum_30d: money(totalAmount * 0.49),
      night_tx_cnt_30d: nightTransactionCount, night_tx_ratio: money(nightTransactionCount / transactionCount),
      counterparty_cnt_30d: 12 + (caseIndex % 18), currency_cnt_30d: 1
    }
  };
  return [
    json(basicInfo),
    json(customers),
    json(suspiciousReports(basicInfo, customers, accounts, blocks)),
    json(accounts),
    json(evidences),
  ];
}

if (!Number.isInteger(count) || count < 1 || count > 8000) {
  throw new Error('案例数量必须是 1 至 8000 的整数');
}
if (!Number.isInteger(chunkSize) || chunkSize < 1 || chunkSize > 500) {
  throw new Error('分批大小必须是 1 至 500 的整数');
}
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.mkdirSync(chunkDirectory, { recursive: true });
const file = fs.openSync(output, 'w');
let forbiddenHits = 0;
let chunkFile = null;
let chunkCount = 0;
fs.writeSync(file, 'basic_info,customers,analysis_texts,accounts,evidences\n');
for (let index = 1; index <= count; index += 1) {
  if ((index - 1) % chunkSize === 0) {
    if (chunkFile !== null) fs.closeSync(chunkFile);
    chunkCount += 1;
    const chunkPath = path.join(chunkDirectory, `historical_cases_${pad(chunkCount, 4)}.csv`);
    chunkFile = fs.openSync(chunkPath, 'w');
    fs.writeSync(chunkFile, 'basic_info,customers,analysis_texts,accounts,evidences\n');
  }
  const line = `${buildRecord(index).map(cell).join(',')}\n`;
  if (/(?:mock|测试|模拟)/i.test(line)) forbiddenHits += 1;
  fs.writeSync(file, line);
  fs.writeSync(chunkFile, line);
}
fs.closeSync(file);
if (chunkFile !== null) fs.closeSync(chunkFile);
if (forbiddenHits) throw new Error(`生成结果包含禁止占位词，共 ${forbiddenHits} 行`);
console.log(JSON.stringify({ output, count, bytes: fs.statSync(output).size,
  forbiddenHits, chunkDirectory, chunkSize, chunkCount }));
