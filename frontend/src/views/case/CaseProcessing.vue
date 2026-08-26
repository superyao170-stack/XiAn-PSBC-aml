<template>
  <div class="processing-page">
    <div class="page-header">
      <div><h2>{{ page.title }}</h2><p>{{ page.subtitle }}</p></div>
      <el-button v-if="page.action" type="primary" :loading="processing" :disabled="!selected.length" @click="runBatch">
        {{ page.button }}{{ selected.length ? `（${selected.length}）` : '' }}
      </el-button>
    </div>
    <el-card>
      <el-form inline class="filters">
        <el-form-item label="案例ID"><el-input v-model="filters.caseId" clearable /></el-form-item>
        <el-form-item label="案例场景"><el-select v-model="filters.scenarioCode" clearable placeholder="全部场景"><el-option label="反洗钱" value="AML"/><el-option label="反欺诈" value="ANTI_FRAUD"/></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <div class="selection-bar">已选择 {{ selected.length }} 个案例</div>
      <el-table v-loading="loading" :data="records" row-class-name="clickable-row" @selection-change="selected=$event" @row-click="openDetail">
        <el-table-column v-if="page.action" type="selection" width="48" @click.stop />
        <el-table-column prop="id" label="案例ID" width="90" />
        <el-table-column prop="caseName" label="案例名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="案例场景" width="110"><template #default="{row}">{{ sceneText(row.scenarioCode) }}</template></el-table-column>
        <el-table-column label="案例来源" width="110"><template #default="{row}"><el-tag :type="row.recognitionMode==='HISTORICAL'?'info':'primary'">{{ row.recognitionMode==='HISTORICAL'?'历史案例':'新增案例' }}</el-tag></template></el-table-column>
        <el-table-column prop="bankCode" label="所属银行" min-width="150" show-overflow-tooltip />
        <el-table-column label="案例状态" width="140"><template #default="{row}"><el-tag :type="statusType(row.processingStage)">{{ statusText(row.processingStage) }}</el-tag></template></el-table-column>
        <el-table-column label="更新时间" width="170"><template #default="{row}">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="openDetail(row)">查看</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :total="total" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`${detail.caseName||''} · ${page.title}`" width="1100px" top="4vh" destroy-on-close>
      <div v-loading="detailLoading" class="detail-body">
        <div class="detail-toolbar">
          <el-tag>{{ detail.caseId }}</el-tag><el-tag type="success">{{ sceneText(detail.scenarioCode) }}</el-tag>
          <template v-if="page.key==='approval'">
            <span class="risk-label">系统建议</span><el-tag :type="riskType(detail.recommendedRiskLevel)">{{ riskText(detail.recommendedRiskLevel) }} · {{ detail.recommendedRiskScore ?? 0 }} 分</el-tag>
            <el-select v-model="finalRisk" placeholder="请选择最终风险等级"><el-option label="低风险" value="LOW"/><el-option label="中风险" value="MEDIUM"/><el-option label="高风险" value="HIGH"/></el-select>
            <el-button type="success" :loading="approving" @click="approve">审核通过</el-button>
          </template>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane v-if="detail.sourcePayload" label="基本信息" name="basic">
            <el-collapse v-model="basicSections">
              <el-collapse-item v-for="item in sourceSections" :key="item.key" :title="`${item.label}（${item.count}）`" :name="item.key"><JsonPanel :value="item.value" /></el-collapse-item>
            </el-collapse>
          </el-tab-pane>
          <el-tab-pane v-if="detail.suspiciousReport" label="可疑报告" name="report">
            <el-input v-model="reportText" type="textarea" :rows="18" />
            <div class="report-actions"><el-button type="primary" :loading="savingReport" @click="saveReport">保存并重新框架抽取</el-button></div>
          </el-tab-pane>
          <el-tab-pane v-if="detail.frameworkResult" label="框架抽取结果" name="framework">
            <div class="five-layer-strip"><span v-for="(label,index) in layerLabels" :key="label"><i>0{{index+1}}</i>{{label}}</span></div>
            <el-collapse v-model="frameworkSections"><el-collapse-item v-for="item in extractionSections" :key="item.key" :title="`${item.label}（${item.count}）`" :name="item.key"><JsonPanel :value="item.value" /></el-collapse-item></el-collapse>
          </el-tab-pane>
          <el-tab-pane v-if="detail.similarityResult || detail.similarityRanking?.length" label="相似案例" name="similarity">
            <el-alert v-if="page.key==='approval'" title="可通过上下移动调整最终顺序；审核通过后顺序和权重会永久写回。" type="info" :closable="false" />
            <div v-for="(item,index) in orderedMatches" :key="item.caseId" class="match-row">
              <b>#{{index+1}}</b><span>{{ item.caseName||item.caseId }}</span><em>相似度 {{ similarityText(item.similarity) }}</em>
              <div v-if="page.key==='approval'"><el-button size="small" :disabled="index===0" @click="move(index,-1)">上移</el-button><el-button size="small" :disabled="index===orderedMatches.length-1" @click="move(index,1)">下移</el-button></div>
            </div>
          </el-tab-pane>
          <el-tab-pane v-if="detail.riskBreakdown" label="评级依据" name="risk"><JsonPanel :value="detail.riskBreakdown" /></el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { approveProcessingCaseApi, getCaseProcessingApi, getCaseProcessingDetailApi, processCasesApi, updateProcessingReportApi } from '@/api/caseProcessing'
import { formatDateTime } from '@/utils/datetime'

const JsonPanel=defineComponent({props:{value:{type:[Object,Array,String,Number,Boolean],default:null}},setup(props){return()=>h('pre',{class:'json-panel'},JSON.stringify(props.value,null,2))}})
const route=useRoute()
const configs:any={
  report:{title:'可疑报告',subtitle:'选择新增案例生成可疑报告，支持批量处理',stage:'PENDING_REPORT',action:'REPORT',button:'生成可疑报告'},
  framework:{title:'框架抽取',subtitle:'查看案例材料和可疑报告，并执行标准五层框架抽取',stage:'PENDING_EXTRACTION',action:'FRAMEWORK',button:'进行框架抽取'},
  similarity:{title:'相似案例',subtitle:'基于已审核案例库执行语义召回与图结构匹配',stage:'PENDING_SIMILARITY',action:'SIMILARITY',button:'进行相似匹配'},
  approval:{title:'复核审批',subtitle:'复核可疑报告、抽取结果、相似排序和系统风险建议',stage:'PENDING_APPROVAL',action:null,button:''}
}
const page=computed(()=>({...configs[String(route.meta.processingPage||'report')],key:String(route.meta.processingPage||'report')}))
const loading=ref(false),processing=ref(false),records=ref<any[]>([]),selected=ref<any[]>([]),total=ref(0),pageNum=ref(1),pageSize=ref(10)
const filters=reactive({caseId:'',scenarioCode:''})
const detailVisible=ref(false),detailLoading=ref(false),detail=ref<any>({}),activeTab=ref('basic'),reportText=ref(''),savingReport=ref(false),approving=ref(false),finalRisk=ref('')
const basicSections=ref(['basic_info']),frameworkSections=ref(['basic_info']),orderedMatches=ref<any[]>([])
const layerLabels=['案例基本信息','实体层','事件层','关系层','证据层']
const sourceLabels:any={basic_info:'基本信息',customers:'客户',transaction_features:'交易特征',accounts:'账户',other_entities:'其他实体',devices:'设备',event_chain:'事件链'}
const extractionLabels:any={basic_info:'案例基本信息',customers:'客户',accounts:'账户',other_entities:'其他实体',events:'事件',relationships:'关系',evidences:'证据'}
const sections=(value:any,labels:any)=>Object.entries(value||{}).filter(([key])=>labels[key]).map(([key,item]:any)=>({key,label:labels[key],value:item,count:Array.isArray(item)?item.length:(item&&typeof item==='object'?Object.keys(item).length:1)}))
const sourceSections=computed(()=>sections(detail.value.sourcePayload,sourceLabels)),extractionSections=computed(()=>sections(detail.value.frameworkResult,extractionLabels))
const sceneText=(value:string)=>({AML:'反洗钱',ANTI_FRAUD:'反欺诈'} as any)[value]||value
const statusText=(value:string)=>({PENDING_REPORT:'待生成报告',PENDING_EXTRACTION:'待框架抽取',PENDING_SIMILARITY:'待相似匹配',PENDING_APPROVAL:'待复核审批',APPROVED:'已审核通过'} as any)[value]||value
const statusType=(value:string)=>({PENDING_REPORT:'info',PENDING_EXTRACTION:'warning',PENDING_SIMILARITY:'primary',PENDING_APPROVAL:'danger',APPROVED:'success'} as any)[value]||'info'
const riskText=(value:string)=>({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险'} as any)[value]||'未定级'
const riskType=(value:string)=>({LOW:'info',MEDIUM:'warning',HIGH:'danger'} as any)[value]||'info'
const similarityText=(value:any)=>{const number=Number(value||0);return `${(number<=1?number*100:number).toFixed(1)}%`}
async function load(){loading.value=true;try{const response:any=await getCaseProcessingApi({stage:page.value.stage,pageNum:pageNum.value,pageSize:pageSize.value,caseId:filters.caseId||undefined,scenarioCode:filters.scenarioCode||undefined});records.value=response.data?.records||[];total.value=response.data?.total||0;selected.value=[]}finally{loading.value=false}}
const search=()=>{pageNum.value=1;load()},reset=()=>{filters.caseId='';filters.scenarioCode='';search()}
async function runBatch(){processing.value=true;try{const response:any=await processCasesApi(page.value.action,selected.value.map(item=>item.caseId));ElMessage.success(`处理完成：成功 ${response.data?.succeeded||0}，失败 ${response.data?.failed||0}`);await load()}finally{processing.value=false}}
async function openDetail(row:any){detailVisible.value=true;detailLoading.value=true;try{const response:any=await getCaseProcessingDetailApi(row.caseId);detail.value=response.data||{};reportText.value=detail.value.suspiciousReport?.analysisText||Object.values(detail.value.suspiciousReport?.analysisTexts||{}).join('\n\n');finalRisk.value=detail.value.recommendedRiskLevel||'';const matches=detail.value.similarityResult?.matches||detail.value.similarityResult?.similarCases||[];const rankMap=new Map((detail.value.similarityRanking||[]).map((item:any)=>[item.caseId,item]));orderedMatches.value=[...matches].map((item:any)=>({...item,...(rankMap.get(item.caseId)||{})})).sort((a:any,b:any)=>(a.finalRank||a.rank||99)-(b.finalRank||b.rank||99));activeTab.value=page.value.key==='framework'?'basic':page.value.key==='similarity'?'framework':page.value.key==='approval'?(detail.value.suspiciousReport?'report':detail.value.frameworkResult?'framework':'basic'):'basic'}finally{detailLoading.value=false}}
async function saveReport(){savingReport.value=true;try{await updateProcessingReportApi(detail.value.caseId,reportText.value);ElMessage.success('可疑报告已保存，案例已放回框架抽取页面');detailVisible.value=false;await load()}finally{savingReport.value=false}}
function move(index:number,delta:number){const target=index+delta;[orderedMatches.value[index],orderedMatches.value[target]]=[orderedMatches.value[target],orderedMatches.value[index]]}
async function approve(){if(!finalRisk.value)return ElMessage.warning('请选择低风险、中风险或高风险');approving.value=true;try{await approveProcessingCaseApi(detail.value.caseId,finalRisk.value as any,orderedMatches.value.map(item=>item.caseId));ElMessage.success('审核通过，案例已进入全景图谱');detailVisible.value=false;await load()}finally{approving.value=false}}
watch([pageNum,pageSize],load);watch(()=>route.meta.processingPage,()=>{pageNum.value=1;load()});onMounted(load)
</script>

<style scoped>
.processing-page{height:100%}.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 5px}.page-header p{margin:0;color:#64748b}.filters{display:flex}.selection-bar{margin:8px 0;padding:10px 14px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff;color:#334155}.processing-page :deep(.clickable-row){cursor:pointer}.processing-page :deep(.el-pagination){justify-content:flex-end;margin-top:18px}.detail-body{min-height:480px}.detail-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:12px}.detail-toolbar .el-select{margin-left:auto;width:150px}.risk-label{margin-left:auto;color:#64748b;font-size:13px}.risk-label+.el-tag+.el-select{margin-left:0}.json-panel{max-height:430px;overflow:auto;margin:0;padding:14px;border-radius:8px;background:#f5f7fa;color:#334155;white-space:pre-wrap;word-break:break-word}.report-actions{display:flex;justify-content:flex-end;margin-top:12px}.five-layer-strip{display:flex;gap:8px;margin-bottom:14px}.five-layer-strip span{display:flex;align-items:center;gap:8px;flex:1;padding:12px;border:1px solid #dbeafe;border-radius:9px;color:#1e3a5f;font-weight:600}.five-layer-strip i{display:inline-flex;width:28px;height:28px;align-items:center;justify-content:center;border-radius:8px;background:#e0edff;color:#2563eb;font-style:normal}.match-row{display:flex;align-items:center;gap:14px;margin-top:10px;padding:13px 15px;border:1px solid #dbe3ef;border-radius:9px}.match-row b{color:#2563eb}.match-row span{flex:1}.match-row em{color:#64748b;font-style:normal}.match-row div{display:flex;gap:6px}
</style>
