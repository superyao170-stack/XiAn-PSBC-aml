<template>
  <div class="indicator-page">
    <header class="page-header">
      <div><h2>指标管理</h2><p>将事件识别规则拆解为可复用原子指标，并通过逻辑与时间窗口组合形成复合指标。</p></div>
      <el-tag type="info">静态指标目录 · V1.0</el-tag>
    </header>

    <section class="summary-grid">
      <article><span>原子指标</span><strong>{{ atomics.length }}</strong><small>单一事实或阈值判断</small></article>
      <article><span>复合指标</span><strong>{{ composites.length }}</strong><small>跨指标组合研判</small></article>
      <article><span>覆盖场景</span><strong>2</strong><small>反洗钱 · 反欺诈</small></article>
      <article><span>风险阈值</span><strong>{{ thresholdCount }}</strong><small>可审计阈值条件</small></article>
    </section>

    <el-card shadow="never">
      <div class="toolbar">
        <el-segmented v-model="activeType" :options="typeOptions" />
        <el-select v-model="scenario" clearable placeholder="全部场景"><el-option label="反洗钱" value="AML"/><el-option label="反欺诈" value="ANTI_FRAUD"/></el-select>
        <el-input v-model="keyword" clearable placeholder="搜索指标名称、编码或来源规则" />
        <span>共 {{ filteredRows.length }} 项</span>
      </div>

      <el-table v-if="activeType==='ATOMIC'" :data="filteredRows" border table-layout="fixed">
        <el-table-column prop="code" label="指标编码" width="150" />
        <el-table-column prop="name" label="原子指标" min-width="190" />
        <el-table-column label="场景" width="100"><template #default="{row}"><el-tag size="small">{{ sceneText(row.scenario) }}</el-tag></template></el-table-column>
        <el-table-column prop="source" label="来源识别规则" min-width="210" show-overflow-tooltip />
        <el-table-column label="口径与阈值" min-width="310"><template #default="{row}"><div class="threshold"><b>{{ row.operator }} {{ row.threshold }}</b><span>{{ row.definition }}</span></div></template></el-table-column>
        <el-table-column prop="window" label="统计窗口" width="120" />
      </el-table>

      <div v-else class="composite-grid">
        <article v-for="item in filteredRows" :key="item.code" class="composite-card">
          <header><div><small>{{ item.code }}</small><h3>{{ item.name }}</h3></div><el-tag>{{ sceneText(item.scenario) }}</el-tag></header>
          <p>{{ item.description }}</p>
          <div class="formula"><span v-for="(token,index) in item.tokens" :key="index" :class="{logic:token==='AND'||token==='OR'}">{{ token }}</span></div>
          <footer><span>判定阈值</span><b>{{ item.threshold }}</b></footer>
        </article>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const activeType=ref<'ATOMIC'|'COMPOSITE'>('ATOMIC'),scenario=ref(''),keyword=ref('')
const typeOptions=[{label:'原子指标',value:'ATOMIC'},{label:'复合指标',value:'COMPOSITE'}]
const atomics=[
  {code:'AF-A001',name:'新设备首次登录',scenario:'ANTI_FRAUD',source:'异常设备登录',operator:'=',threshold:'首次',window:'单次事件',definition:'主体账户在未登记设备上首次成功登录'},
  {code:'AF-A002',name:'屏幕共享进程检出',scenario:'ANTI_FRAUD',source:'远程控制或屏幕共享迹象',operator:'≥',threshold:'1 次',window:'单次会话',definition:'登录会话中检测到屏幕共享或远程控制进程'},
  {code:'AF-A003',name:'夜间高频操作数',scenario:'ANTI_FRAUD',source:'夜间异常操作',operator:'≥',threshold:'5 次',window:'30 分钟',definition:'22:00—06:00 内查询、认证或转账操作次数'},
  {code:'AF-A004',name:'异常入账累计金额',scenario:'ANTI_FRAUD',source:'多头分散转入',operator:'≥',threshold:'20 万元',window:'24 小时',definition:'多个非本人账户向主体账户转入的累计金额'},
  {code:'AF-A005',name:'当日转出比例',scenario:'ANTI_FRAUD',source:'资金快进快出',operator:'≥',threshold:'80%',window:'自然日',definition:'当日异常入账后转出金额占入账金额比例'},
  {code:'AF-A006',name:'非柜面限额增幅',scenario:'ANTI_FRAUD',source:'异常限额操作',operator:'≥',threshold:'100%',window:'7 日',definition:'非柜面限额相对调整前额度的增长比例'},
  {code:'AML-A001',name:'现金交易累计金额',scenario:'AML',source:'大额现金交易',operator:'≥',threshold:'5 万元',window:'自然日',definition:'客户现金存取交易累计金额'},
  {code:'AML-A002',name:'交易对手数量',scenario:'AML',source:'多对手分散交易',operator:'≥',threshold:'10 个',window:'24 小时',definition:'去重后的非本人交易对手数量'},
  {code:'AML-A003',name:'资金停留时长',scenario:'AML',source:'快进快出',operator:'≤',threshold:'2 小时',window:'单笔链路',definition:'入账至主要转出之间的时间间隔'},
  {code:'AML-A004',name:'跨境交易占比',scenario:'AML',source:'异常跨境交易',operator:'≥',threshold:'60%',window:'30 日',definition:'跨境交易金额占总交易金额比例'},
  {code:'AML-A005',name:'拆分交易笔数',scenario:'AML',source:'规避阈值拆分',operator:'≥',threshold:'3 笔',window:'24 小时',definition:'接近监管阈值且方向一致的交易笔数'},
  {code:'AML-A006',name:'高风险地区对手数',scenario:'AML',source:'高风险地域暴露',operator:'≥',threshold:'2 个',window:'30 日',definition:'高风险地区内去重交易对手数量'}
]
const composites=[
  {code:'AF-C001',name:'远程操控后资金外流',scenario:'ANTI_FRAUD',description:'识别设备接管后短时间内发生的高比例转出。',tokens:['AF-A001','AND','AF-A002','AND','AF-A005'],threshold:'三项同时满足'},
  {code:'AF-C002',name:'夜间多头入账快转',scenario:'ANTI_FRAUD',description:'识别夜间集中操作、多头资金汇入并快速转出的组合风险。',tokens:['AF-A003','AND','AF-A004','AND','AF-A005'],threshold:'组合得分 ≥ 80'},
  {code:'AF-C003',name:'提额后异常转账',scenario:'ANTI_FRAUD',description:'识别先提高非柜面限额、再由异常设备发起转账的链路。',tokens:['AF-A006','AND','AF-A001','AND','AF-A005'],threshold:'7 日内顺序命中'},
  {code:'AML-C001',name:'拆分现金快进快出',scenario:'AML',description:'识别拆分规避阈值且资金停留时间极短的可疑模式。',tokens:['AML-A001','AND','AML-A003','AND','AML-A005'],threshold:'三项同时满足'},
  {code:'AML-C002',name:'跨境多对手扩散',scenario:'AML',description:'识别跨境资金向多个交易对手快速扩散的网络型风险。',tokens:['AML-A002','AND','AML-A003','AND','AML-A004'],threshold:'组合得分 ≥ 70'},
  {code:'AML-C003',name:'高风险地域资金链',scenario:'AML',description:'识别高风险地域暴露叠加跨境和快进快出行为。',tokens:['AML-A006','AND','AML-A004','AND','AML-A003'],threshold:'30 日内累计命中'}
]
const thresholdCount=atomics.length+composites.length
const filteredRows=computed<any[]>(()=>{
  const rows=activeType.value==='ATOMIC'?atomics:composites
  const query=keyword.value.trim().toLowerCase()
  return rows.filter((item:any)=>(!scenario.value||item.scenario===scenario.value)&&(!query||JSON.stringify(item).toLowerCase().includes(query)))
})
const sceneText=(value:string)=>value==='AML'?'反洗钱':'反欺诈'
</script>

<style scoped>
.indicator-page{height:100%}.page-header{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 8px}.page-header p{margin:0;color:#64748b}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:16px}.summary-grid article{display:flex;flex-direction:column;padding:18px;border:1px solid #e2e8f0;border-radius:12px;background:linear-gradient(145deg,#fff,#f8fbff)}.summary-grid span,.summary-grid small{color:#64748b}.summary-grid strong{margin:7px 0;color:#174b82;font-size:28px}.toolbar{display:flex;align-items:center;gap:12px;margin-bottom:16px}.toolbar .el-select{width:130px}.toolbar .el-input{width:300px}.toolbar>span{margin-left:auto;color:#64748b}.threshold{display:flex;flex-direction:column;gap:5px}.threshold b{color:#b45309}.threshold span{color:#64748b;font-size:12px;line-height:1.5}.composite-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.composite-card{padding:18px;border:1px solid #dce5ef;border-radius:13px;background:linear-gradient(140deg,#fbfdff,#fff)}.composite-card header,.composite-card footer{display:flex;align-items:center;justify-content:space-between}.composite-card small{color:#64748b}.composite-card h3{margin:5px 0 0;color:#1e3a5f}.composite-card p{min-height:42px;color:#64748b;line-height:1.6}.formula{display:flex;align-items:center;gap:7px;flex-wrap:wrap;padding:12px;border-radius:9px;background:#f1f5f9}.formula span{padding:5px 8px;border-radius:6px;background:#dbeafe;color:#1d4ed8;font-size:12px;font-weight:700}.formula span.logic{background:#fff7ed;color:#c2410c}.composite-card footer{margin-top:13px;color:#64748b}.composite-card footer b{color:#b45309}@media(max-width:900px){.summary-grid,.composite-grid{grid-template-columns:1fr 1fr}.toolbar{flex-wrap:wrap}}
</style>
