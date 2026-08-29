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
        <el-table-column prop="bankCode" label="所属银行" min-width="150" show-overflow-tooltip />
        <el-table-column label="案例状态" width="140"><template #default="{row}"><el-tag :type="statusType(row.processingStage)">{{ statusText(row.processingStage) }}</el-tag></template></el-table-column>
        <el-table-column label="更新时间" width="170"><template #default="{row}">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="openDetail(row)">查看</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :total="total" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`${detail.caseName||''} · ${page.title}`" width="94vw" top="4vh" destroy-on-close>
      <div v-loading="detailLoading" class="detail-body">
        <div class="detail-toolbar">
          <el-tag>{{ detail.caseId }}</el-tag><el-tag type="success">{{ sceneText(detail.scenarioCode) }}</el-tag>
          <template v-if="page.key==='approval'">
            <span class="risk-label">系统建议风险等级</span><el-tag :type="riskType(detail.recommendedRiskLevel)">{{ riskText(detail.recommendedRiskLevel) }}<template v-if="detail.recommendedRiskScore!=null"> · {{ detail.recommendedRiskScore }}分</template></el-tag>
            <el-select v-model="finalRisk" placeholder="请选择最终风险等级"><el-option label="低风险" value="LOW"/><el-option label="中风险" value="MEDIUM"/><el-option label="高风险" value="HIGH"/></el-select>
            <el-button type="success" :loading="approving" @click="approve">审核通过</el-button>
          </template>
        </div>
        <el-alert v-if="page.key==='approval'" title="系统依据基础字段、事件、关系和相似案例给出默认等级；复核人员可在右上角二次核定。" type="info" :closable="false" class="rating-hint" />

        <el-tabs v-model="activeTab">
          <el-tab-pane v-if="detail.sourcePayload" label="基本信息" name="basic">
            <el-collapse v-model="basicSections">
              <el-collapse-item v-for="item in sourceSections" :key="item.key" :title="`${item.label}（${item.count}）`" :name="item.key"><JsonPanel :value="item.value" /></el-collapse-item>
            </el-collapse>
          </el-tab-pane>
          <el-tab-pane v-if="detail.scenarioCode==='ANTI_FRAUD'" label="风险事件链" name="risk-chain">
            <template v-if="riskEventChain.length">
              <div class="risk-chain-overview">
                <div><strong>{{ sourceEvents.length }}</strong><span>原始事件</span></div>
                <div><strong>{{ riskEventChain.length }}</strong><span>风险事件</span></div>
                <p>红色节点为经反欺诈工作流审查后保留的风险节点；序号可回溯至原始事件链 JSON。</p>
              </div>
              <div class="risk-chain-layout">
              <div class="source-event-chain">
                <article v-for="(event,index) in sourceEvents" :key="index" :class="{risk:isRiskSource(index+1)}">
                  <i>{{ index+1 }}</i>
                  <div><header><b>{{ event['类型'] || event.type || '事件' }}</b><time>{{ event['发生时间'] || event.occurred_at }}</time><el-tag v-if="isRiskSource(index+1)" size="small" type="danger">风险节点</el-tag></header><p>{{ event['具体内容'] || event.content }}</p></div>
                </article>
              </div>
              <aside class="risk-event-list">
                <article v-for="risk in riskEventChain" :key="risk.risk_event_id">
                  <header><b>{{ risk.risk_event_id }}</b><el-tag type="danger" size="small">{{ risk.risk_type || risk.category }}</el-tag></header>
                  <p>{{ risk.content || risk.reason }}</p>
                  <small>{{ risk.start_time || risk.event_start }} 至 {{ risk.end_time || risk.event_end }}</small>
                  <footer>来源节点：{{ riskSourceLabels(risk) }}</footer>
                </article>
              </aside>
              </div>
            </template>
            <el-empty v-else description="尚未生成风险事件链，请先执行反欺诈可疑报告生成" :image-size="72" />
          </el-tab-pane>
          <el-tab-pane v-if="detail.suspiciousReport" label="可疑报告" name="report">
            <el-input v-model="reportText" type="textarea" :rows="18" />
            <div class="report-actions"><el-button type="primary" :loading="savingReport" @click="saveReport">保存</el-button></div>
          </el-tab-pane>
          <el-tab-pane v-if="detail.frameworkResult" label="框架抽取结果" name="framework">
            <div class="five-layer-strip"><span v-for="(label,index) in layerLabels" :key="label"><i>0{{index+1}}</i>{{label}}</span></div>
            <el-collapse v-model="frameworkSections"><el-collapse-item v-for="item in extractionSections" :key="item.key" :title="`${item.label}（${item.count}）`" :name="item.key"><JsonPanel :value="item.value" /></el-collapse-item></el-collapse>
          </el-tab-pane>
          <el-tab-pane v-if="detail.similarityResult || detail.similarityRanking?.length" label="相似案例" name="similarity">
            <el-alert v-if="page.key==='approval'" title="可通过上下移动调整最终顺序；审核通过后顺序和权重会永久写回。" type="info" :closable="false" />
            <div v-for="(item,index) in orderedMatches" :key="matchKey(item,index)" class="match-card" :class="{expanded:isMatchExpanded(item,index)}">
              <div
                class="match-row"
                role="button"
                tabindex="0"
                :aria-expanded="isMatchExpanded(item,index)"
                @click="toggleMatch(item,index)"
                @keydown.enter.prevent="toggleMatch(item,index)"
                @keydown.space.prevent="toggleMatch(item,index)"
              >
                <b>#{{index+1}}</b><span>{{ item.caseName||item.caseId }}</span><em>相似度 {{ similarityText(item.similarity) }}</em>
                <div v-if="page.key==='approval'" class="match-actions" @click.stop @keydown.stop><el-button size="small" :disabled="index===0" @click="move(index,-1)">上移</el-button><el-button size="small" :disabled="index===orderedMatches.length-1" @click="move(index,1)">下移</el-button></div>
                <span class="match-toggle">{{ isMatchExpanded(item,index)?'收起':'匹配详情' }}<i>⌄</i></span>
              </div>

              <div v-show="isMatchExpanded(item,index)" class="match-detail">
                <div class="match-metrics">
                  <div><small>综合相似度</small><strong>{{ similarityText(item.similarity) }}</strong><span>图结构综合得分</span></div>
                  <div><small>事件向量相似度</small><strong>{{ similarityText(item.vectorSimilarity ?? item.vector_similarity) }}</strong><span>事件类型向量余弦</span></div>
                  <div><small>事件类型重叠率</small><strong>{{ similarityText(item.eventTypeOverlap ?? item.event_type_overlap) }}</strong><span>共同事件类型 {{ item.sharedEventTypeCount ?? item.shared_event_type_count ?? 0 }} 类</span></div>
                  <div><small>事件数量比</small><strong>{{ similarityText(item.eventCountRatio ?? item.event_count_ratio) }}</strong><span>两侧事件规模接近度</span></div>
                  <div><small>归一化 GED</small><strong>{{ decimalText(item.normalizedGed ?? item.normalized_ged) }}</strong><span>越接近 0 越相似</span></div>
                </div>

                <div class="match-counts">
                  <span>命中节点 <strong>{{ matchedValue(item,'matchedNodeCount','matched_node_count') }}</strong></span>
                  <span>命中关系 <strong>{{ matchedValue(item,'matchedEdgeCount','matched_edge_count') }}</strong></span>
                  <span>原始 GED <strong>{{ decimalText(item.ged) }}</strong></span>
                </div>

                <MatchedGraphComparison :match="item" :query-title="detail.caseName||detail.caseId" />

                <section class="event-similarity-panel">
                  <div class="event-similarity-head">
                    <div><small>EVENT SIMILARITY</small><h4>事件之间的相似点</h4></div>
                    <el-tag round>{{ eventAnchors(item).length }} 个锚点</el-tag>
                  </div>
                  <p class="event-summary">{{ eventSummary(item).summary || '暂无事件锚点摘要' }}</p>
                  <div v-if="eventAnchors(item).length" class="event-anchor-list">
                    <article v-for="(anchor,anchorIndex) in eventAnchors(item)" :key="`${matchKey(item,index)}-${anchorIndex}`" class="event-anchor-card">
                      <header>
                        <b>锚点 {{ anchorIndex+1 }}</b>
                        <el-tag size="small" :type="anchor.anchor_level==='high'?'success':'warning'">{{ anchor.anchor_level==='high'?'高置信度':'普通候选' }}</el-tag>
                        <span>描述相似度 {{ similarityText(anchor.description_similarity ?? anchor.descriptionSimilarity) }}</span>
                      </header>
                      <div class="event-compare-grid">
                        <div class="event-side query-side">
                          <small>查询事件</small><h5>{{ eventField(anchor,'query','event_name') || anchor.query_event_id || '—' }}</h5>
                          <el-tag size="small">{{ eventField(anchor,'query','event_type') || anchor.event_type || '—' }}</el-tag>
                          <p>{{ eventField(anchor,'query','event_description') || '暂无事件描述' }}</p>
                        </div>
                        <div class="event-side candidate-side">
                          <small>候选事件</small><h5>{{ eventField(anchor,'candidate','event_name') || anchor.candidate_event_id || '—' }}</h5>
                          <el-tag size="small" type="info">{{ eventField(anchor,'candidate','event_type') || anchor.event_type || '—' }}</el-tag>
                          <p>{{ eventField(anchor,'candidate','event_description') || '暂无事件描述' }}</p>
                        </div>
                      </div>
                      <div v-if="commonPoints(anchor).length" class="common-points">
                        <b>共同描述</b>
                        <div v-for="(point,pointIndex) in commonPoints(anchor)" :key="pointIndex">
                          <span>相似点 {{ pointIndex+1 }} · {{ similarityText(point.similarity) }}</span>
                          <p>查询：{{ point.query_text ?? point.queryText }}</p>
                          <p>候选：{{ point.candidate_text ?? point.candidateText }}</p>
                        </div>
                      </div>
                    </article>
                  </div>
                  <el-empty v-else description="当前案例未形成满足阈值的事件锚点" :image-size="54" />
                </section>
              </div>
            </div>
          </el-tab-pane>
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
import MatchedGraphComparison from '@/components/case/MatchedGraphComparison.vue'

const JsonPanel=defineComponent({props:{value:{type:[Object,Array,String,Number,Boolean],default:null}},setup(props){return()=>h('pre',{class:'json-panel'},JSON.stringify(props.value,null,2))}})
const route=useRoute()
const configs:any={
  report:{title:'可疑报告',subtitle:'选择新增案例生成可疑报告，支持批量处理',stage:'PENDING_REPORT',action:'REPORT',button:'生成可疑报告'},
  framework:{title:'框架抽取',subtitle:'查看案例材料和可疑报告，并执行标准五层框架抽取',stage:'PENDING_EXTRACTION',action:'FRAMEWORK',button:'进行框架抽取'},
  similarity:{title:'相似案例',subtitle:'基于已审核案例库执行语义召回与图结构匹配',stage:'PENDING_SIMILARITY',action:'SIMILARITY',button:'进行相似匹配'},
  approval:{title:'复核审批',subtitle:'复核可疑报告、抽取结果、相似排序和基本信息风险等级',stage:'PENDING_APPROVAL',action:null,button:''}
}
const page=computed(()=>({...configs[String(route.meta.processingPage||'report')],key:String(route.meta.processingPage||'report')}))
const loading=ref(false),processing=ref(false),records=ref<any[]>([]),selected=ref<any[]>([]),total=ref(0),pageNum=ref(1),pageSize=ref(10)
const filters=reactive({caseId:'',scenarioCode:''})
const detailVisible=ref(false),detailLoading=ref(false),detail=ref<any>({}),activeTab=ref('basic'),reportText=ref(''),savingReport=ref(false),approving=ref(false),finalRisk=ref('')
const basicSections=ref(['basic_info']),frameworkSections=ref(['basic_info']),orderedMatches=ref<any[]>([]),expandedMatches=ref<string[]>([])
const layerLabels=['案例基本信息','实体层','事件层','关系层','证据层']
const sourceLabels:any={basic_info:'基本信息',customers:'客户',transaction_features:'交易特征',accounts:'账户',other_entities:'其他实体',devices:'设备',event_chain:'事件链',text_analysis:'分析文本'}
const extractionLabels:any={basic_info:'案例基本信息',customers:'客户',accounts:'账户',other_entities:'其他实体',events:'事件',relationships:'关系',evidences:'证据'}
const sections=(value:any,labels:any)=>Object.entries(value||{}).filter(([key])=>labels[key]).map(([key,item]:any)=>({key,label:labels[key],value:item,count:Array.isArray(item)?item.length:(item&&typeof item==='object'?Object.keys(item).length:1)}))
const sourceSections=computed(()=>sections(detail.value.sourcePayload,sourceLabels)),extractionSections=computed(()=>sections(detail.value.frameworkResult,extractionLabels))
const sourceEvents=computed<any[]>(()=>detail.value.sourcePayload?.event_chain||[])
const riskEventChain=computed<any[]>(()=>detail.value.suspiciousReport?.riskEventChain||[])
const riskSourceIndexes=computed(()=>new Set(riskEventChain.value.flatMap((item:any)=>item.source_event_indexes||item.evidence?.map((source:any)=>Number(source.source_index)+1)||[]).map(Number)))
const isRiskSource=(index:number)=>riskSourceIndexes.value.has(index)
const riskSourceLabels=(risk:any)=>(risk.source_event_indexes||risk.evidence?.map((item:any)=>Number(item.source_index)+1)||[]).join('、')||'—'
const sceneText=(value:string)=>({AML:'反洗钱',ANTI_FRAUD:'反欺诈'} as any)[value]||value
const statusText=(value:string)=>({PENDING_REPORT:'待生成报告',PENDING_EXTRACTION:'待框架抽取',PENDING_SIMILARITY:'待相似匹配',PENDING_APPROVAL:'待复核审批',APPROVED:'已审核通过'} as any)[value]||value
const statusType=(value:string)=>({PENDING_REPORT:'info',PENDING_EXTRACTION:'warning',PENDING_SIMILARITY:'primary',PENDING_APPROVAL:'danger',APPROVED:'success'} as any)[value]||'info'
const riskText=(value:string)=>({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险'} as any)[value]||'未定级'
const riskType=(value:string)=>({LOW:'info',MEDIUM:'warning',HIGH:'danger'} as any)[value]||'info'
const similarityText=(value:any)=>{const number=Number(value||0);return `${(number<=1?number*100:number).toFixed(1)}%`}
const decimalText=(value:any)=>Number.isFinite(Number(value))?Number(value).toFixed(4):'—'
const matchKey=(item:any,index:number)=>String(item.caseId||item.case_id||`${item.caseName||'case'}-${index}`)
const isMatchExpanded=(item:any,index:number)=>expandedMatches.value.includes(matchKey(item,index))
function toggleMatch(item:any,index:number){const key=matchKey(item,index);expandedMatches.value=isMatchExpanded(item,index)?expandedMatches.value.filter(value=>value!==key):[...expandedMatches.value,key]}
const matchedGraph=(item:any)=>item.matchedSubgraph||item.matched_subgraph||{}
const matchedValue=(item:any,camelKey:string,snakeKey:string)=>matchedGraph(item)[camelKey]??matchedGraph(item)[snakeKey]??0
const eventSummary=(item:any)=>item.eventSimilaritySummary||item.event_similarity_summary||{}
const eventAnchors=(item:any)=>eventSummary(item).similar_points||eventSummary(item).similarPoints||[]
const commonPoints=(anchor:any)=>anchor.common_description_points||anchor.commonDescriptionPoints||[]
function eventField(anchor:any,side:'query'|'candidate',field:string){const event=anchor[`${side}_event`]||anchor[`${side}Event`]||{};const camelField=field.replace(/_([a-z])/g,(_,letter)=>letter.toUpperCase());return event[field]??event[camelField]}
async function load(){loading.value=true;try{const response:any=await getCaseProcessingApi({stage:page.value.stage,pageNum:pageNum.value,pageSize:pageSize.value,caseId:filters.caseId||undefined,scenarioCode:filters.scenarioCode||undefined});records.value=response.data?.records||[];total.value=response.data?.total||0;selected.value=[]}finally{loading.value=false}}
const search=()=>{pageNum.value=1;load()},reset=()=>{filters.caseId='';filters.scenarioCode='';search()}
async function runBatch(){processing.value=true;try{const response:any=await processCasesApi(page.value.action,selected.value.map(item=>item.caseId));ElMessage.success(`处理完成：成功 ${response.data?.succeeded||0}，失败 ${response.data?.failed||0}`);await load()}finally{processing.value=false}}
async function openDetail(row:any){detailVisible.value=true;detailLoading.value=true;expandedMatches.value=[];try{const response:any=await getCaseProcessingDetailApi(row.caseId);detail.value=response.data||{};reportText.value=detail.value.suspiciousReport?.analysisText||Object.values(detail.value.suspiciousReport?.analysisTexts||{}).join('\n\n');finalRisk.value=detail.value.recommendedRiskLevel||'';const matches=detail.value.similarityResult?.matches||detail.value.similarityResult?.similarCases||[];const rankMap=new Map((detail.value.similarityRanking||[]).map((item:any)=>[item.caseId,item]));orderedMatches.value=[...matches].map((item:any)=>({...item,...(rankMap.get(item.caseId)||{})})).sort((a:any,b:any)=>(a.finalRank||a.rank||99)-(b.finalRank||b.rank||99));activeTab.value=page.value.key==='framework'?'basic':page.value.key==='similarity'?'framework':page.value.key==='approval'?(detail.value.suspiciousReport?'report':detail.value.frameworkResult?'framework':'basic'):'basic'}finally{detailLoading.value=false}}
async function saveReport(){savingReport.value=true;try{await updateProcessingReportApi(detail.value.caseId,reportText.value);ElMessage.success('保存成功，案例已进入框架抽取队列，请在框架抽取页面手动开始');detailVisible.value=false;await load()}finally{savingReport.value=false}}
function move(index:number,delta:number){const target=index+delta;[orderedMatches.value[index],orderedMatches.value[target]]=[orderedMatches.value[target],orderedMatches.value[index]]}
async function approve(){if(!finalRisk.value)return ElMessage.warning('请选择低风险、中风险或高风险');approving.value=true;try{await approveProcessingCaseApi(detail.value.caseId,finalRisk.value as any,orderedMatches.value.map(item=>item.caseId));ElMessage.success('审核通过，案例已进入全景图谱');detailVisible.value=false;await load()}finally{approving.value=false}}
watch([pageNum,pageSize],load);watch(()=>route.meta.processingPage,()=>{pageNum.value=1;load()});onMounted(load)
</script>

<style scoped>
.processing-page{height:100%}.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 5px}.page-header p{margin:0;color:#64748b}.filters{display:flex}.selection-bar{margin:8px 0;padding:10px 14px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff;color:#334155}.processing-page :deep(.clickable-row){cursor:pointer}.processing-page :deep(.el-pagination){justify-content:flex-end;margin-top:18px}.detail-body{min-height:480px}.detail-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:12px}.detail-toolbar .el-select{margin-left:auto;width:150px}.risk-label{margin-left:auto;color:#64748b;font-size:13px}.risk-label+.el-tag+.el-select{margin-left:0}.json-panel{max-height:430px;overflow:auto;margin:0;padding:14px;border-radius:8px;background:#f5f7fa;color:#334155;white-space:pre-wrap;word-break:break-word}.report-actions{display:flex;justify-content:flex-end;margin-top:12px}.five-layer-strip{display:flex;gap:8px;margin-bottom:14px}.five-layer-strip span{display:flex;align-items:center;gap:8px;flex:1;padding:12px;border:1px solid #dbeafe;border-radius:9px;color:#1e3a5f;font-weight:600}.five-layer-strip i{display:inline-flex;width:28px;height:28px;align-items:center;justify-content:center;border-radius:8px;background:#e0edff;color:#2563eb;font-style:normal}
.match-card{margin-top:10px;border:1px solid #dbe3ef;border-radius:10px;overflow:hidden;transition:border-color .2s,box-shadow .2s}.match-card.expanded{border-color:#a9c9f7;box-shadow:0 8px 24px rgba(37,99,235,.08)}.match-row{display:flex;align-items:center;gap:14px;padding:13px 15px;cursor:pointer;outline:none}.match-row:focus-visible{box-shadow:inset 0 0 0 2px #409eff}.match-row b{color:#2563eb}.match-row>span:first-of-type{flex:1}.match-row em{color:#64748b;font-style:normal;white-space:nowrap}.match-actions{display:flex;gap:6px}.match-toggle{display:inline-flex;align-items:center;justify-content:flex-end;gap:6px;min-width:78px;color:#2563eb;font-size:13px;white-space:nowrap}.match-toggle i{font-style:normal;transition:transform .2s}.expanded .match-toggle i{transform:rotate(180deg)}
.match-detail{padding:18px;border-top:1px solid #e5edf7;background:linear-gradient(135deg,#f7faff,#fffaf3)}.match-metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.match-metrics>div{display:flex;flex-direction:column;gap:5px;padding:13px;border:1px solid #e0e8f2;border-radius:10px;background:#fff}.match-metrics small{color:#64748b}.match-metrics strong{color:#174b82;font-size:20px}.match-metrics span{color:#94a3b8;font-size:11px;line-height:1.4}.match-counts{display:flex;gap:22px;padding:13px 2px 4px;color:#64748b;font-size:13px}.match-counts strong{color:#334155}
.event-similarity-panel{margin-top:12px;padding:17px;border:1px solid #d9e3f0;border-radius:13px;background:rgba(255,255,255,.72)}.event-similarity-head{display:flex;align-items:center;justify-content:space-between}.event-similarity-head small{color:#8090a3;font-size:10px;letter-spacing:.14em}.event-similarity-head h4{margin:4px 0 0;color:#173f69;font-size:17px}.event-summary{margin:10px 0 0;color:#64748b;font-size:13px}.event-anchor-list{display:grid;gap:12px;margin-top:14px}.event-anchor-card{overflow:hidden;border:1px solid #dde5ee;border-left:4px solid #d99a2b;border-radius:11px;background:#fff}.event-anchor-card>header{display:flex;align-items:center;gap:9px;padding:10px 13px;border-bottom:1px solid #edf1f5;background:#fbfcfe}.event-anchor-card>header>span{margin-left:auto;color:#64748b;font-size:12px}.event-compare-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:13px}.event-side{min-width:0;padding:12px;border:1px solid #dce5ef;border-radius:10px}.query-side{border-top:3px solid #2563eb}.candidate-side{border-top:3px solid #7c3aed}.event-side small{color:#64748b;font-weight:700}.event-side h5{margin:7px 0;font-size:14px;line-height:1.45}.event-side p{margin:9px 0 0;color:#475569;font-size:12px;line-height:1.7;word-break:break-word}.common-points{margin:0 13px 13px;padding:12px;border-radius:9px;background:#f8fafc;color:#475569;font-size:12px}.common-points>b{display:block;margin-bottom:8px;color:#334155}.common-points>div{margin-top:8px;padding-left:10px;border-left:3px solid #f2c94c}.common-points span{font-weight:600;color:#8a6200}.common-points p{margin:4px 0;line-height:1.55}
.rating-hint{margin-bottom:10px}.risk-chain-overview{display:flex;align-items:center;gap:12px;margin-bottom:16px;padding:14px 16px;border:1px solid #fee2e2;border-radius:12px;background:linear-gradient(135deg,#fff7f7,#fff)}.risk-chain-overview>div{display:flex;min-width:92px;flex-direction:column}.risk-chain-overview strong{color:#b42318;font-size:24px}.risk-chain-overview span{color:#64748b;font-size:12px}.risk-chain-overview p{margin:0 0 0 auto;color:#64748b;font-size:12px}.risk-chain-layout{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(300px,.8fr);gap:18px;max-height:560px;overflow:auto}.source-event-chain{padding-left:8px}.source-event-chain article{position:relative;display:flex;gap:12px;padding:0 0 18px 8px}.source-event-chain article:before{position:absolute;top:28px;bottom:0;left:21px;width:2px;background:#dbe4ef;content:''}.source-event-chain article:last-child:before{display:none}.source-event-chain i{z-index:1;display:grid;width:28px;height:28px;flex:none;place-items:center;border:2px solid #93a4b8;border-radius:50%;background:#fff;color:#64748b;font-size:11px;font-style:normal}.source-event-chain article.risk i{border-color:#ef4444;background:#fee2e2;color:#b91c1c;font-weight:700}.source-event-chain article>div{min-width:0;flex:1;padding:11px 13px;border:1px solid #dfe7f0;border-radius:10px;background:#fff}.source-event-chain article.risk>div{border-color:#fca5a5;box-shadow:0 5px 18px rgba(220,38,38,.08)}.source-event-chain header,.risk-event-list header{display:flex;align-items:center;gap:9px}.source-event-chain time{margin-left:auto;color:#64748b;font-size:11px}.source-event-chain p,.risk-event-list p{margin:8px 0 0;color:#475569;font-size:12px;line-height:1.65}.risk-event-list{display:grid;align-content:start;gap:10px}.risk-event-list article{padding:14px;border-left:4px solid #dc2626;border-radius:10px;background:#fff5f5}.risk-event-list small{display:block;margin-top:9px;color:#64748b}.risk-event-list footer{margin-top:9px;color:#b42318;font-size:11px;font-weight:600}
@media(max-width:900px){.match-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.event-compare-grid,.risk-chain-layout{grid-template-columns:1fr}.match-row{flex-wrap:wrap}.match-row>span:first-of-type{min-width:55%}}
</style>
