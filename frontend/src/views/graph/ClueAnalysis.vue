<template>
  <div class="clue-analysis">
    <div v-if="!embedded" class="page-header"><div><h2>跨案直接关联</h2><p>以有效案例和TuGraph案件子图为输入，输出可研判、可合并到案例的候选线索。</p></div><div><el-button @click="openEditor()">新增线索</el-button><el-button type="primary" :loading="analyzing" @click="openRunDialog">运行线索分析</el-button></div></div>
    <div v-else class="embedded-actions"><el-button @click="openEditor()">新增线索</el-button><el-button type="primary" :loading="analyzing" @click="openRunDialog">运行跨案直接关联</el-button></div>
    <div class="io-flow">
      <div><b>输入</b><span>银行、日期、场景、案例范围</span></div><i>→</i><div><b>处理</b><span>TuGraph账户复用与事件模式分析</span></div><i>→</i><div><b>输出</b><span>候选线索、关联案例、证据子图</span></div>
    </div>
    <el-alert class="flow-tip" type="info" :closable="false" show-icon title="自动分析和手工线索统一写入线索记录；候选线索经编辑、驳回或合并后，状态与目标案例同步留痕。" />
    <el-card>
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="线索类型"><el-select v-model="searchForm.clueType" clearable placeholder="全部"><el-option v-for="item in clueTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="searchForm.status" clearable placeholder="全部"><el-option label="候选" value="CANDIDATE"/><el-option label="已合并" value="MERGED"/><el-option label="已驳回" value="DISMISSED"/></el-select></el-form-item>
        <el-form-item label="分析批次"><el-input v-model="searchForm.analysisRunId" clearable placeholder="全部批次" /></el-form-item>
        <el-form-item><el-button type="primary" @click="search">搜索</el-button><el-button @click="resetForm">重置</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="clues" border class="adaptive-list-table clue-table" table-layout="fixed" :fit="true">
        <el-table-column prop="clue_id" label="线索ID" min-width="112" show-overflow-tooltip />
        <el-table-column prop="clue_type" label="类型" width="90"><template #default="{row}"><el-tag>{{ getTypeText(row.clue_type) }}</el-tag></template></el-table-column>
        <el-table-column label="算法 / 来源" min-width="112" show-overflow-tooltip><template #default="{row}"><div class="source-cell"><span :title="row.algorithm_code">{{ row.algorithm_code || '—' }}</span><small :title="row.analysis_run_id || row.source_type">{{ row.analysis_run_id || sourceText(row.source_type) }}</small></div></template></el-table-column>
        <el-table-column prop="confidence" label="置信度" width="80" />
        <el-table-column prop="explanation" label="分析说明" min-width="112" show-overflow-tooltip />
        <el-table-column prop="related_case_ids" label="关联案例" min-width="112" show-overflow-tooltip><template #default="{row}"><span class="compact-cell" :title="caseIdsText(row)">{{ caseIdsText(row) }}</span></template></el-table-column>
        <el-table-column label="状态 / 创建时间" width="150"><template #default="{row}"><div class="status-time"><el-tag size="small" :type="getStatusTagType(row.status)">{{ getStatusText(row.status) }}</el-tag><span :title="formatDateTime(row.created_at)">{{ formatDateTime(row.created_at) }}</span></div></template></el-table-column>
        <el-table-column label="操作" width="132" fixed="right" align="right" header-align="right" class-name="operation-column"><template #default="{row}">
          <div class="table-actions"><el-button size="small" type="primary" plain @click="showDetail(row)">详情</el-button>
          <el-dropdown @command="handleClueAction($event,row)"><el-button size="small">更多</el-button><template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="edit" :disabled="row.status!=='CANDIDATE'">编辑</el-dropdown-item>
            <el-dropdown-item command="merge" :disabled="row.status!=='CANDIDATE'">合并案例</el-dropdown-item>
            <el-dropdown-item command="dismiss" :disabled="row.status!=='CANDIDATE'">驳回</el-dropdown-item>
            <el-dropdown-item command="delete" :disabled="row.status==='MERGED'" divided>删除</el-dropdown-item>
          </el-dropdown-menu></template></el-dropdown></div>
        </template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="[10,20,50]" :total="total" layout="total, sizes, prev, pager, next, jumper" />
    </el-card>

    <el-dialog v-model="runVisible" title="运行图谱线索分析" width="720px">
      <el-alert type="info" :closable="false" title="输入范围先从关系数据库筛选有效案例，再仅分析这些案例在TuGraph中的账户和事件节点。" />
      <el-form :model="runForm" label-width="110px" class="dialog-form">
        <el-form-item label="分析银行"><el-input :model-value="bankCode" disabled /></el-form-item>
        <el-form-item label="创建时间范围"><el-date-picker v-model="runForm.dateRange" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" style="width:100%" /></el-form-item>
        <el-form-item label="场景编码"><el-input v-model="runForm.scenarioCode" clearable placeholder="留空表示全部场景" /></el-form-item>
        <el-form-item label="指定案例"><el-select v-model="runForm.caseIds" multiple filterable allow-create default-first-option clearable style="width:100%" placeholder="留空使用上述范围；也可输入案例ID"><el-option v-for="item in caseOptions" :key="item.caseId" :label="`${item.caseName || '未命名'} · ${item.caseId}`" :value="item.caseId" /></el-select></el-form-item>
      </el-form>
      <el-alert v-if="runForm.caseIds.length === 1" type="warning" :closable="false" show-icon
                title="当前以本案为种子，请再选择至少一个案例后运行跨案分析。" />
      <el-card v-if="runResult" shadow="never" class="run-result"><template #header><b>本次输出 · {{ runResult.runId }}</b></template><el-descriptions :column="3" border class="balanced-descriptions"><el-descriptions-item label="输入案例">{{ runResult.inputCaseCount }}</el-descriptions-item><el-descriptions-item label="账户节点">{{ runResult.scannedAccountNodes }}</el-descriptions-item><el-descriptions-item label="事件节点">{{ runResult.scannedEventNodes }}</el-descriptions-item><el-descriptions-item label="跨案例线索">{{ runResult.crossCaseClues }}</el-descriptions-item><el-descriptions-item label="模式线索">{{ runResult.motifClues }}</el-descriptions-item><el-descriptions-item label="输出线索">{{ runResult.generatedClues }}</el-descriptions-item></el-descriptions></el-card>
      <h4>最近分析运行</h4>
      <el-table :data="runRecords" size="small" border table-layout="fixed" max-height="250"><el-table-column prop="run_id" label="运行批次" width="145" show-overflow-tooltip/><el-table-column prop="input_case_count" label="输入案例" width="78"/><el-table-column label="扫描节点" width="88"><template #default="{row}">{{ row.scanned_account_nodes }}/{{ row.scanned_event_nodes }}</template></el-table-column><el-table-column prop="generated_clues" label="输出线索" width="78"/><el-table-column label="状态 / 时间" min-width="145"><template #default="{row}"><div class="status-time"><el-tag size="small" :type="row.status==='SUCCEEDED'?'success':row.status==='FAILED'?'danger':'warning'">{{ row.status }}</el-tag><span>{{ formatDateTime(row.created_at) }}</span></div></template></el-table-column><el-table-column label="操作" width="88" align="right" header-align="right"><template #default="{row}"><el-button link type="primary" :disabled="!row.generated_clues" @click="showRunOutputs(row)">查看输出</el-button></template></el-table-column></el-table>
      <template #footer><el-button @click="runVisible=false">关闭</el-button><el-button type="primary" :disabled="runForm.caseIds.length === 1" :loading="analyzing" @click="analyze">开始分析</el-button></template>
    </el-dialog>

    <el-dialog v-model="editorVisible" :title="editingId ? '编辑候选线索' : '新增手工线索'" width="680px">
      <el-alert type="warning" :closable="false" title="手工线索必须关联至少一个已有案例，保存后进入与自动分析结果相同的候选研判流程。" />
      <el-form :model="form" label-width="100px" class="dialog-form">
        <el-form-item label="线索类型" required><el-select v-model="form.clueType" style="width:100%"><el-option v-for="item in clueTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="输入案例" required><el-select v-model="form.relatedCaseIds" multiple filterable allow-create default-first-option style="width:100%" placeholder="选择或输入案例ID"><el-option v-for="item in caseOptions" :key="item.caseId" :label="`${item.caseName || '未命名'} · ${item.caseId}`" :value="item.caseId" /></el-select></el-form-item>
        <el-form-item label="主体标识"><el-input v-model="subjectIdsText" placeholder="可选，多个账户/主体ID用逗号分隔" /></el-form-item>
        <el-form-item label="证据来源"><el-input v-model="form.sourceRef" placeholder="可选，如文档、交易记录或图子图引用" /></el-form-item>
        <el-form-item label="置信度"><el-input-number v-model="form.confidence" :min="0" :max="1" :step="0.05" /></el-form-item>
        <el-form-item label="分析说明" required><el-input v-model="form.explanation" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editorVisible=false">取消</el-button><el-button type="primary" @click="save">保存为候选线索</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="线索输入、分析与输出" width="800px">
      <el-descriptions v-if="detail" :column="2" border class="balanced-descriptions"><el-descriptions-item label="线索ID"><span class="long-detail-value">{{ detail.clue_id }}</span></el-descriptions-item><el-descriptions-item label="来源">{{ sourceText(detail.source_type) }}</el-descriptions-item><el-descriptions-item label="分析批次"><span class="long-detail-value">{{ detail.analysis_run_id || '手工录入' }}</span></el-descriptions-item><el-descriptions-item label="算法">{{ detail.algorithm_code }} / {{ detail.algorithm_version }}</el-descriptions-item><el-descriptions-item label="状态">{{ getStatusText(detail.status) }}</el-descriptions-item><el-descriptions-item label="置信度">{{ detail.confidence }}</el-descriptions-item><el-descriptions-item label="输入场景">{{ detail.analysis_scenario_code || '全部/手工' }}</el-descriptions-item><el-descriptions-item label="输入案例数">{{ detail.input_case_count ?? (detail.related_case_ids || []).length }}</el-descriptions-item><el-descriptions-item label="扫描账户">{{ detail.scanned_account_nodes ?? '—' }}</el-descriptions-item><el-descriptions-item label="扫描事件">{{ detail.scanned_event_nodes ?? '—' }}</el-descriptions-item><el-descriptions-item label="证据子图" :span="2"><span class="long-detail-value">{{ detail.evidence_subgraph_ref || '—' }}</span></el-descriptions-item><el-descriptions-item label="分析说明" :span="2">{{ detail.explanation }}</el-descriptions-item></el-descriptions>
      <h4>关联案例（输入与后续合并目标）</h4><div class="case-links"><el-button v-for="caseId in detail?.related_case_ids || []" :key="caseId" link type="primary" @click="router.push(`/case/detail/${caseId}`)">{{ caseId }}</el-button><span v-if="!(detail?.related_case_ids || []).length">暂无关联案例</span></div>
      <h4>关联账户/主体</h4><div class="subject-list">{{ (detail?.related_subject_ids || []).join('、') || '暂无主体标识' }}</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { analyzeGraphCluesApi, createClueApi, deleteClueApi, getClueApi, getClueRunsApi, getCluesApi, mergeClueApi, updateClueApi, updateClueStatusApi } from '@/api/clue'
import { getCasesApi } from '@/api/case'
import { formatDateTime } from '@/utils/datetime'
import { readAnalysisScope, saveAnalysisScope } from '@/utils/analysisScope'
defineProps<{ embedded?: boolean }>()
const route=useRoute(),router=useRouter()
const initialScope=readAnalysisScope(route.query as Record<string,unknown>)
const clueTypes=[{label:'交易线索',value:'TRANSACTION'},{label:'账户线索',value:'ACCOUNT'},{label:'主体线索',value:'SUBJECT'},{label:'团伙线索',value:'GANG'},{label:'跨案例线索',value:'CROSS_CASE'},{label:'异常模式',value:'ANOMALY_MOTIF'}]
const searchForm=reactive({clueType:'',status:'',analysisRunId:''})
const form=reactive({clueType:'CROSS_CASE',confidence:.7,explanation:'',relatedCaseIds:[] as string[],sourceRef:''})
const runForm=reactive({
  dateRange:initialScope.startTime&&initialScope.endTime?[new Date(initialScope.startTime),new Date(initialScope.endTime)]:[] as Date[],
  scenarioCode:initialScope.scenarioCode,
  caseIds:[...initialScope.caseIds]
})
const currentPage=ref(1),pageSize=ref(10),total=ref(0),loading=ref(false),analyzing=ref(false),clues=ref<any[]>([])
const editorVisible=ref(false),editingId=ref(''),runVisible=ref(false),runResult=ref<any>(null),detailVisible=ref(false),detail=ref<any>(null)
const subjectIdsText=ref(''),caseOptions=ref<any[]>([])
const runRecords=ref<any[]>([])
const bankCode=localStorage.getItem('bankCode')||'中国测试银行'
const getTypeText=(type:string)=>clueTypes.find(item=>item.value===type)?.label||type
const getStatusText=(status:string)=>({CANDIDATE:'候选',MERGED:'已合并',DISMISSED:'已驳回',SUPERSEDED:'已替代'} as Record<string,string>)[status]||status
const getStatusTagType=(status:string)=>({CANDIDATE:'warning',MERGED:'success',DISMISSED:'danger'} as Record<string,any>)[status]||'info'
const sourceText=(source:string)=>source==='MANUAL'?'手工录入':'TuGraph图谱分析'
const caseIdsText=(row:any)=>(row.related_case_ids||[]).join?.('、')||'—'
const load=async()=>{loading.value=true;try{const r:any=await getCluesApi({...searchForm,pageNum:currentPage.value,pageSize:pageSize.value});clues.value=r.data?.records||[];total.value=r.data?.total||0}finally{loading.value=false}}
const loadCases=async()=>{const r:any=await getCasesApi({pageNum:1,pageSize:200});caseOptions.value=r.data?.records||[]}
const loadRuns=async()=>{const r:any=await getClueRunsApi(bankCode);runRecords.value=r.data||[]}
const search=()=>{currentPage.value=1;load()},resetForm=()=>{Object.assign(searchForm,{clueType:'',status:'',analysisRunId:''});search()}
const dismiss=async(row:any)=>{await ElMessageBox.confirm('确认驳回该线索？','提示');await updateClueStatusApi(row.clue_id,'DISMISSED');ElMessage.success('线索已驳回');await load()}
const merge=async(row:any)=>{const result=await ElMessageBox.prompt('请输入要合并到的目标案例ID','合并线索',{inputValue:row.related_case_ids?.[0]||'',inputPattern:/\S+/,inputErrorMessage:'案例ID不能为空'});await mergeClueApi(row.clue_id,result.value);ElMessage.success('线索已合并到案例');await load()}
const openRunDialog=()=>{runResult.value=null;runVisible.value=true;Promise.all([loadCases(),loadRuns()])}
const analyze=async()=>{
  if(runForm.caseIds.length===1)return ElMessage.warning('跨案分析至少需要两个案例')
  analyzing.value=true
  try{
    const dates=runForm.dateRange||[]
    const startTime=dates[0]?new Date(dates[0]).toISOString():''
    const endTime=dates[1]?new Date(dates[1]).toISOString():''
    saveAnalysisScope({caseIds:runForm.caseIds,scenarioCode:runForm.scenarioCode,startTime,endTime})
    const r:any=await analyzeGraphCluesApi({bankCode,scenarioCode:runForm.scenarioCode,caseIds:runForm.caseIds,startTime,endTime})
    runResult.value=r.data
    ElMessage.success(`分析完成：输入 ${r.data?.inputCaseCount||0} 个案例，输出 ${r.data?.generatedClues||0} 条线索`)
    await Promise.all([load(),loadRuns()])
  }finally{analyzing.value=false}
}
const showRunOutputs=(row:any)=>{searchForm.analysisRunId=row.run_id;currentPage.value=1;runVisible.value=false;load()}
const openEditor=(row?:any)=>{editingId.value=row?.clue_id||'';Object.assign(form,row?{clueType:row.clue_type,confidence:Number(row.confidence),explanation:row.explanation,relatedCaseIds:[...(row.related_case_ids||[])],sourceRef:row.evidence_subgraph_ref||''}:{clueType:'CROSS_CASE',confidence:.7,explanation:'',relatedCaseIds:[],sourceRef:''});subjectIdsText.value=(row?.related_subject_ids||[]).join(',');editorVisible.value=true;loadCases()}
const save=async()=>{if(!form.relatedCaseIds.length)return ElMessage.warning('请至少关联一个输入案例');if(!form.explanation.trim())return ElMessage.warning('请输入分析说明');const payload={...form,bankCode,relatedSubjectIds:subjectIdsText.value.split(/[,，\s]+/).filter(Boolean)};editingId.value?await updateClueApi(editingId.value,payload):await createClueApi(payload);editorVisible.value=false;ElMessage.success('候选线索已保存并关联案例');await load()}
const showDetail=async(row:any)=>{const r:any=await getClueApi(row.clue_id);detail.value=r.data;detailVisible.value=true}
const remove=async(row:any)=>{await ElMessageBox.confirm('确认删除该线索？','删除线索');await deleteClueApi(row.clue_id);ElMessage.success('线索已删除');await load()}
const handleClueAction=(command:string,row:any)=>command==='edit'?openEditor(row):command==='merge'?merge(row):command==='dismiss'?dismiss(row):remove(row)
watch([currentPage,pageSize],load);onMounted(async()=>{
  await Promise.all([load(),loadCases(),loadRuns()])
  if(route.query.caseIds)runVisible.value=true
})
</script>

<style scoped>
.clue-analysis{height:100%}.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;gap:16px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.embedded-actions{display:flex;justify-content:flex-end;gap:8px;margin-bottom:12px}.io-flow{display:grid;grid-template-columns:1fr 32px 1fr 32px 1fr;align-items:center;margin-bottom:12px}.io-flow div{display:flex;flex-direction:column;gap:5px;padding:12px 16px;border:1px solid #dbeafe;background:#f8fbff;border-radius:8px}.io-flow b{color:#1d4ed8}.io-flow span{font-size:12px;color:#64748b}.io-flow i{text-align:center;color:#3b82f6;font-style:normal}.flow-tip{margin-bottom:14px}.search-form{margin-bottom:14px}.el-pagination{margin-top:16px;justify-content:flex-end}.compact-cell,.source-cell span,.source-cell small{display:block;width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.source-cell{min-width:0}.source-cell small{margin-top:4px;color:#64748b;font-size:11px}.status-time{display:flex;flex-direction:column;align-items:flex-start;gap:5px;min-width:0}.status-time span{display:block;width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#64748b;font-size:12px}.dialog-form{margin-top:16px}.run-result{margin-top:14px}.case-links{display:flex;flex-wrap:wrap;gap:4px 10px;padding:10px;border:1px solid #ebeef5;border-radius:6px}.subject-list{padding:10px;background:#f8fafc;border-radius:6px;overflow-wrap:anywhere}h4{margin:16px 0 8px}@media(max-width:900px){.page-header{align-items:flex-start;flex-wrap:wrap}.io-flow{grid-template-columns:1fr}.io-flow i{text-align:center;transform:rotate(90deg)}}
</style>
