<template>
  <div class="visualize-page" v-loading="loading" element-loading-text="正在加载案件图谱">
    <section ref="workspace" class="panorama-workspace" :class="{ 'detail-collapsed': detailCollapsed }">
      <header class="workspace-toolbar">
        <div class="toolbar-scope"><strong>实体关系</strong><span class="stat">{{ typeNodes.length }} 个节点</span><span class="stat">{{ baseEdges.length }} 条关系</span></div>
        <div class="filters-group">
          <el-select v-model="scenario" placeholder="全部场景" :empty-values="[null, undefined]" aria-label="案件场景" class="scenario-select" :disabled="loading" @change="loadCases">
            <el-option label="全部场景" value="" /><el-option label="反洗钱" value="AML" /><el-option label="反欺诈" value="ANTI_FRAUD" />
          </el-select>
          <el-autocomplete v-model="searchText" class="entity-search" :fetch-suggestions="searchEntities" placeholder="搜索实体或案件" aria-label="搜索实体或案件" :prefix-icon="Search" clearable @select="selectSearchResult" highlight-first-item>
            <template #default="{ item }"><div class="search-result"><i :style="{ background: typeMeta(item.node).color }"></i><span>{{ item.value }}</span><small>{{ typeMeta(item.node).label }}</small></div></template>
          </el-autocomplete>
        </div>
        <div class="toolbar-actions"><el-button :icon="Refresh" aria-label="刷新图谱" title="刷新图谱" :disabled="loading" @click="loadCases" /><el-button :disabled="loading" @click="saveView">保存视图</el-button><el-button type="primary" :disabled="!displayedNodes.length || loading" @click="graphCanvas?.exportImage()">导出图谱</el-button></div>
      </header>
      <main class="graph-column">
        <div class="legend-row">
          <button v-for="item in activeLegendTypes" :key="item.key" :class="{ muted: !visibleTypes[item.key] }" :aria-pressed="visibleTypes[item.key]" :title="`${item.label} ${typeCounts[item.key] || 0} 个，点击切换显示`" @click="toggleType(item.key)"><i class="type-mark" :class="item.key.toLowerCase()" :style="{ background: item.color }"></i>{{ item.label }}<em>{{ typeCounts[item.key] }}</em></button>
          <el-popover v-if="inactiveLegendTypes.length" placement="bottom" :width="260" trigger="click"><template #reference><button class="more-types">其他类型 <el-icon><ArrowDown /></el-icon></button></template><div class="more-type-list"><el-checkbox v-for="item in inactiveLegendTypes" :key="item.key" v-model="visibleTypes[item.key]">{{ item.label }} · {{ typeCounts[item.key] || 0 }}</el-checkbox></div></el-popover>
          <div class="relation-legend"><span>点击节点或关系查看详情</span></div>
        </div>
        <div class="graph-tools"><label class="case-window">案件窗口<el-input-number v-model="windowSize" :min="1" :max="PANORAMA_CASE_LIMIT" size="small" controls-position="right" aria-label="案件窗口" :disabled="loading" @change="loadCases" /></label><button :class="{ active: clustered }" :aria-pressed="clustered" @click="clustered = !clustered">关系簇</button><button :disabled="!selected" :class="{ active: focusOnly }" :aria-pressed="focusOnly" @click="focusOnly = !focusOnly">只看选中路径</button><button :class="{ active: hideLowFrequency }" :aria-pressed="hideLowFrequency" title="仅显示同类关系至少两条或记录频次至少两次的关系" @click="hideLowFrequency = !hideLowFrequency">隐藏低频关系</button><span class="scope-info" :title="scopeSummary">{{ validCaseCount }} 个案件<span v-if="collapsedGroupCount"> · {{ collapsedGroupCount }} 个关系簇</span></span><button class="icon-tool" title="适应画布" aria-label="适应画布" @click="graphCanvas?.fitView()"><el-icon><Aim /></el-icon></button><button class="icon-tool" title="全屏图谱" aria-label="全屏图谱" @click="toggleFullscreen"><el-icon><FullScreen /></el-icon></button></div>
        <PanoramaGraphCanvas ref="graphCanvas" :nodes="displayedNodes" :edges="displayedEdges" :type-meta="canvasTypeMeta" :selected-id="selected ? String(selected.id) : ''" :focused-edge-key="focusedEdgeKey" :clustered="clustered" @select="selectNode" @select-edge="selectRelation" @display-count="updateDisplayCounts" />
      </main>
      <aside class="node-inspector">
        <div class="inspector-heading"><strong>实体详情</strong><button :title="detailCollapsed ? '展开实体详情' : '收起实体详情'" :aria-label="detailCollapsed ? '展开实体详情' : '收起实体详情'" @click="detailCollapsed = !detailCollapsed"><el-icon><component :is="detailCollapsed ? ArrowLeft : ArrowRight" /></el-icon></button></div>
        <div v-if="!detailCollapsed" ref="inspectorContent" class="inspector-content">
          <template v-if="selected">
            <div class="entity-card"><div class="entity-kind"><i :style="{ background: typeMeta(selected).color }"></i>{{ typeMeta(selected).label }}</div><h2>{{ nodeName(selected) }}</h2><div class="entity-id">{{ selected.visualCaseName || selected.visualCaseId }}</div><span v-if="selectedRisk" class="risk-tag" :class="{ high: /高|HIGH|H$/i.test(selectedRisk) }">{{ selectedRisk }}</span><dl><div v-for="item in selectedAttributes" :key="item.key"><dt>{{ item.key }}</dt><dd :title="item.value">{{ item.value }}</dd></div></dl></div>
            <div v-if="selectedRelation" class="selected-relation"><strong>{{ graphEdgeName(selectedRelation) }}</strong><span>{{ nodeName(nodeMap.get(String(selectedRelation.source))) }} → {{ nodeName(nodeMap.get(String(selectedRelation.target))) }}</span><dl><div v-for="item in relationAttributes" :key="item.key"><dt>{{ item.key }}</dt><dd>{{ item.value }}</dd></div></dl></div>
            <div class="section-heading"><strong>直接关联</strong><button v-if="relationships.length > 6" @click="showAllRelations = !showAllRelations">{{ showAllRelations ? '收起' : `全部 ${relationships.length} 条` }} <el-icon><ArrowRight /></el-icon></button><span v-else>{{ relationships.length }} 条</span></div>
            <button v-for="item in visibleRelationships" :key="item.key" class="relation-item" :class="{ active: focusedEdgeKey === item.key }" @click="highlightRelation(item.edge)"><span class="relation-icon" :style="{ color: graphRelationMeta(item.edge).color }"><el-icon><Connection /></el-icon></span><span class="relation-main"><b :title="item.name">{{ item.name }}</b><small :title="graphEdgeName(item.edge)">{{ graphEdgeName(item.edge) }}</small></span><el-icon class="relation-arrow"><ArrowRight /></el-icon></button>
            <div v-if="pathNodes.length > 1" class="key-path"><strong>关键关系路径</strong><div><template v-for="(item, index) in pathNodes" :key="item.id"><el-icon v-if="index"><ArrowRight /></el-icon><button :title="nodeName(item)" @click="graphCanvas?.focusNode(String(item.id))">{{ nodeName(item) }}</button></template></div></div>
            <el-button class="expand-relations" @click="expandSelected">{{ nodeType(selected) === 'CASE' ? '展开案件关系簇' : '展开该实体的全部关系' }} <el-icon><ArrowRight /></el-icon></el-button>
          </template>
          <el-empty v-else description="选择一个实体" :image-size="50" />
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, ArrowDown, ArrowLeft, ArrowRight, Aim, FullScreen, Connection } from '@element-plus/icons-vue'
import PanoramaGraphCanvas from '@/components/graph/PanoramaGraphCanvas.vue'
import { getCasesApi, getCaseGraphApi } from '@/api/case'
import { getApprovedCaseSimilarityGraphApi } from '@/api/caseProcessing'
import { graphEdgeName, graphEdgeKey, graphRelationMeta, graphNeighborhood, graphCaseKey } from '@/utils/panoramaGraph'
type GraphPlane = 'EVENT_GRAPH'
type NodeType = {key:string;label:string;color:string;icon:string;raw:string[];plane:GraphPlane}
const cases=ref<any[]>([]), scenario=ref('AML'), windowSize=ref(4), totalGraphCases=ref(0), PANORAMA_CASE_LIMIT=50
const nodes=ref<any[]>([]), edges=ref<any[]>([]), selected=ref<any>(null), selectedRelation=ref<any>(null), validCaseCount=ref(0), loading=ref(false)
const graphCanvas=ref<InstanceType<typeof PanoramaGraphCanvas>>(), workspace=ref<HTMLElement>()
const inspectorContent=ref<HTMLElement>()
const searchText=ref(''), detailCollapsed=ref(true), clustered=ref(false), focusOnly=ref(false), hideLowFrequency=ref(false), showAllRelations=ref(false), collapsedGroupCount=ref(0), focusedEdgeKey=ref('')
let requestVersion=0
const nodeTypes:NodeType[]=[
  {key:'CASE',label:'案件',color:'#13233a',icon:'案',raw:['CASE'],plane:'EVENT_GRAPH'},
  {key:'EVENT',label:'事件',color:'#1769e0',icon:'事',raw:['EVENT'],plane:'EVENT_GRAPH'},
  {key:'EVIDENCE',label:'证据',color:'#e9a400',icon:'证',raw:['EVIDENCE'],plane:'EVENT_GRAPH'},
  {key:'CUSTOMER',label:'客户',color:'#00a870',icon:'客',raw:['CUSTOMER'],plane:'EVENT_GRAPH'},
  {key:'ORGANIZATION',label:'机构',color:'#0d9488',icon:'机',raw:['ORGANIZATION'],plane:'EVENT_GRAPH'},
  {key:'MERCHANT',label:'商户',color:'#059669',icon:'商',raw:['MERCHANT'],plane:'EVENT_GRAPH'},
  {key:'ACCOUNT',label:'账户',color:'#7c3aed',icon:'户',raw:['ACCOUNT'],plane:'EVENT_GRAPH'},
  {key:'WALLET',label:'钱包',color:'#8b5cf6',icon:'包',raw:['WALLET'],plane:'EVENT_GRAPH'},
  {key:'DEVICE',label:'设备',color:'#0369a1',icon:'设',raw:['DEVICE'],plane:'EVENT_GRAPH'},
  {key:'IPADDRESS',label:'IP地址',color:'#0284c7',icon:'IP',raw:['IPADDRESS'],plane:'EVENT_GRAPH'},
  {key:'ADDRESS',label:'地址',color:'#0891b2',icon:'址',raw:['ADDRESS'],plane:'EVENT_GRAPH'},
  {key:'OTHERENTITY',label:'其他实体',color:'#64748b',icon:'其',raw:['OTHERENTITY'],plane:'EVENT_GRAPH'},
  {key:'OTHER',label:'其他',color:'#64748b',icon:'其',raw:[],plane:'EVENT_GRAPH'}
]
const reasoningRawTypes=new Set(['INDICATORRESULT','BEHAVIORPATTERN','BEHAVIORPATTERNOCCURRENCE','RISKHYPOTHESIS','RISKEVENT','ATTACKPATHCANDIDATE','INVESTIGATIONHYPOTHESIS','TECHNIQUEOCCURRENCE','TECHNIQUE','TACTIC','INDICATORDEFINITION','BEHAVIORPATTERNDEFINITION','BASEEVENTTYPEDEFINITION','RISKEVENTTYPEDEFINITION','ATTACKPATHTYPEDEFINITION','MATTER','ALTERNATIVEEXPLANATION'])
const normalizeType=(value:any)=>String(value||'OTHER').replace(/[^A-Za-z0-9]/g,'').toUpperCase()
const rawNodeType=(node:any)=>normalizeType(node.properties?.canonicalType||node.properties?.nodeType||node.type||node.label)
const nodeType=(node:any)=>nodeTypes.find(item=>item.raw.includes(rawNodeType(node)))?.key||'OTHER'
const typeMeta=(node:any)=>nodeTypes.find(item=>item.key===nodeType(node))||nodeTypes[nodeTypes.length-1]

const visibleTypes=ref<Record<string,boolean>>(Object.fromEntries(nodeTypes.map(item=>[item.key,true])))
const typeNodes=computed(()=>nodes.value.filter(node=>visibleTypes.value[nodeType(node)]).map(node=>({...node,visualType:nodeType(node),visualLabel:nodeName(node)})))
const nodeMap=computed(()=>new Map(nodes.value.map(node=>[String(node.id),node])))
const baseEdges=computed(()=>{const ids=new Set(typeNodes.value.map(node=>String(node.id)));return edges.value.filter(edge=>ids.has(String(edge.source))&&ids.has(String(edge.target)))})
const neighborhood=computed(()=>graphNeighborhood(String(selected.value?.id||''),typeNodes.value,baseEdges.value))
const displayedNodes=computed(()=>focusOnly.value&&selected.value?typeNodes.value.filter(node=>neighborhood.value.nodeIds.has(String(node.id))):typeNodes.value)
const edgeCounts=computed(()=>baseEdges.value.reduce((result:Record<string,number>,edge:any)=>{const key=graphEdgeName(edge);result[key]=(result[key]||0)+1;return result},{}))
const displayedEdges=computed(()=>{const ids=new Set(displayedNodes.value.map(node=>String(node.id)));return baseEdges.value.filter(edge=>ids.has(String(edge.source))&&ids.has(String(edge.target))&&(!hideLowFrequency.value||Number(edge.properties?.count||edge.properties?.frequency||edge.properties?.transactionCount||edge.properties?.occurrences||0)>=2||(edgeCounts.value[graphEdgeName(edge)]||0)>=2))})
const typeCounts=computed(()=>nodes.value.reduce((result:Record<string,number>,node:any)=>{const key=nodeType(node);result[key]=(result[key]||0)+1;return result},{}))
const activeLegendTypes=computed(()=>nodeTypes.filter(item=>(typeCounts.value[item.key]||0)>0))
const inactiveLegendTypes=computed(()=>nodeTypes.filter(item=>!(typeCounts.value[item.key]||0)))
const canvasTypeMeta=computed(()=>Object.fromEntries(nodeTypes.map(item=>[item.key,{name:item.label,color:item.color,icon:item.icon}])))
const filteredCases=computed(()=>cases.value.slice(0,windowSize.value))
const scopeSummary=computed(()=>`当前场景共 ${totalGraphCases.value} 个有图谱的案件，展示最近 ${filteredCases.value.length} 个；${validCaseCount.value} 个案件已入图`)
const nodeName=(node:any)=>{
  if(!node)return '未知实体'
  const properties=node.properties||{}
  if(nodeType(node)==='CASE')return String(node.name||properties.caseName||properties.name||properties.caseId||node.caseId||node.uid||node.id||'未命名案件')
  return String(node.name||properties.displayName||properties.eventName||properties.event_name||properties.name
    ||properties.customerName||properties.organizationName||properties.merchantName||properties.deviceName
    ||properties.ipAddress||properties.address||properties.indicatorName||properties.patternName||properties.hypothesis
    ||properties.title||properties.entity_attr_1||properties.summary||properties.accountNo||properties.account_no||properties.account_number||properties.accountAlias||friendlyNodeFallback(node))
}
const friendlyNodeFallback=(node:any)=>{
  const label=typeMeta(node).label
  const graphId=String(node.properties?.graphId||node.properties?.instanceId||'')
  const suffix=graphId.match(/_(\d{1,6})$/)?.[1]
  const order=Number(node.visualOrder||0)
  return suffix?`${label} ${suffix}`:order?`${label} ${String(order).padStart(2,'0')}`:`未命名${label}`
}

const fieldLabels:Record<string,string>={caseName:'案件名称',name:'名称',displayName:'显示名称',description:'说明',summary:'摘要',caseId:'案件编号',eventName:'事件名称',event_name:'事件名称',eventTime:'事件时间',event_time:'事件时间',eventType:'事件类型',event_type:'事件类型',amount:'金额',currency:'币种',riskLevel:'风险等级',risk_level:'风险等级',customerNo:'客户号',customer_no:'客户号',customerName:'客户名称',holderName:'开户人',holder_name:'开户人',accountNo:'账号',account_no:'账号',account_number:'账号',account_type:'账户类型',entity_id:'实体ID',entity_type:'实体类型',entity_attr_1:'实体属性1',entity_attr_2:'实体属性2',entity_attr_3:'实体属性3',entity_attr_4:'实体属性4',accountAlias:'账户别名',bankCode:'所属银行',openedAt:'开户日期',opened_at:'开户日期',organizationName:'机构名称',merchantName:'商户名称',deviceName:'设备名称',deviceType:'设备类型',device_type:'设备类型',ipAddress:'IP地址',ip_address:'IP地址',address:'地址',nationality:'国籍',status:'状态',caseStatus:'案件状态',scenarioCode:'案件场景',dataSource:'数据来源',data_source:'数据来源',source:'来源',proves:'证明事项',type:'业务类型',confidence:'置信度',weight:'权重',similarity:'相似度',finalRank:'相似排序',count:'次数',frequency:'频次',transactionCount:'交易笔数',relationName:'关系名称',relationType:'关系类型',eventText:'事件说明'}
const attributes=(entity:any)=>Object.entries(entity?.properties||{}).filter(([key,value])=>fieldLabels[key]&&value!==null&&value!==undefined&&value!==''&&typeof value!=='object').map(([key,value])=>({key:fieldLabels[key],value:key==='scenarioCode'?({AML:'反洗钱',ANTI_FRAUD:'反欺诈'}[String(value)]||String(value)):String(value)})).filter((item,index,items)=>items.findIndex(x=>x.key===item.key&&x.value===item.value)===index)
const selectedAttributes=computed(()=>attributes(selected.value).filter(item=>!['风险等级','显示名称','名称','案件名称','事件名称'].includes(item.key)).slice(0,14))
const selectedRisk=computed(()=>{const value=String(selected.value?.properties?.riskLevel||selected.value?.properties?.risk_level||'');return ({HIGH:'高风险',MEDIUM:'中风险',LOW:'低风险',H:'高风险',M:'中风险',L:'低风险'}[value]||value)})
const relationAttributes=computed(()=>attributes(selectedRelation.value))
const relationships=computed(()=>!selected.value?[]:baseEdges.value.filter(edge=>String(edge.source)===String(selected.value.id)||String(edge.target)===String(selected.value.id)).map(edge=>{const other=String(edge.source)===String(selected.value.id)?String(edge.target):String(edge.source);return {key:graphEdgeKey(edge),edge,name:nodeName(nodeMap.value.get(other))}}))
const visibleRelationships=computed(()=>showAllRelations.value?relationships.value:relationships.value.slice(0,6))
const pathNodes=computed(()=>neighborhood.value.path.map(id=>nodeMap.value.get(id)).filter(Boolean))
function resetInspectorScroll(){nextTick(()=>{if(inspectorContent.value)inspectorContent.value.scrollTop=0})}
function selectNode(node:any){selected.value=node;selectedRelation.value=null;focusedEdgeKey.value='';showAllRelations.value=false;if(node){detailCollapsed.value=false;resetInspectorScroll()}else{detailCollapsed.value=true;focusOnly.value=false}}
function selectRelation(edge:any){selectedRelation.value=edge;focusedEdgeKey.value=graphEdgeKey(edge);selected.value=nodeMap.value.get(String(edge.target))||nodeMap.value.get(String(edge.source));detailCollapsed.value=false;showAllRelations.value=false;resetInspectorScroll()}
function highlightRelation(edge:any){selectedRelation.value=edge;focusedEdgeKey.value=graphEdgeKey(edge);resetInspectorScroll()}
function toggleType(key:string){visibleTypes.value[key]=!visibleTypes.value[key];if(selected.value&&!visibleTypes.value[nodeType(selected.value)])selectNode(null)}
function searchEntities(query:string,callback:(items:any[])=>void){const keyword=query.trim().toLowerCase();callback(nodes.value.filter(node=>!keyword||`${nodeName(node)} ${node.properties?.accountNo||''} ${node.visualCaseName||''} ${node.visualCaseId||''}`.toLowerCase().includes(keyword)).sort((a,b)=>Number(nodeName(b).toLowerCase()===keyword)-Number(nodeName(a).toLowerCase()===keyword)).slice(0,30).map(node=>({id:String(node.id),node,value:nodeName(node)})))}
async function selectSearchResult(item:any){visibleTypes.value[nodeType(item.node)]=true;focusOnly.value=false;hideLowFrequency.value=false;await nextTick();await graphCanvas.value?.focusNode(String(item.id))}
function updateDisplayCounts(_count:number,clusterCount:number){collapsedGroupCount.value=clusterCount}
async function expandSelected(){if(!selected.value)return;focusOnly.value=false;await nextTick();await graphCanvas.value?.expandCase(graphCaseKey(selected.value))}
async function toggleFullscreen(){try{if(document.fullscreenElement)await document.exitFullscreen();else await workspace.value?.requestFullscreen()}catch{ElMessage.info('当前浏览器不支持全屏，可使用浏览器全屏功能')}}
const viewStorageKey=()=>`bankgraph.panorama.view:${localStorage.getItem('username')||'guest'}:${localStorage.getItem('bankCode')||''}`
function saveView(){try{localStorage.setItem(viewStorageKey(),JSON.stringify({scenario:scenario.value,windowSize:windowSize.value,visibleTypes:visibleTypes.value,clustered:clustered.value,hideLowFrequency:hideLowFrequency.value,focusOnly:focusOnly.value,selectedId:selected.value?.id,detailCollapsed:detailCollapsed.value,canvas:graphCanvas.value?.getViewState()}));ElMessage.success('视图已保存，下次打开自动恢复')}catch{ElMessage.error('视图保存失败，请检查浏览器存储空间')}}
async function mapGraphs(items:any[],task:(item:any)=>Promise<any>){const results:any[]=new Array(items.length);let cursor=0;await Promise.all(Array.from({length:Math.min(6,items.length)},async()=>{while(cursor<items.length){const index=cursor++;results[index]=await task(items[index])}}));return results}
async function loadCases(){
  const version=++requestVersion
  loading.value=true
  selectNode(null)
  try { await graphCanvas.value?.restoreViewState({}) } catch {}
  try{
    // A completed graph snapshot, rather than the workflow status, determines
    // whether a case belongs in the panorama. This also covers historical cases
    // that already have graph data but are still awaiting manual approval.
    const response:any=await getCasesApi({pageNum:1,pageSize:PANORAMA_CASE_LIMIT,hasGraph:true,scenarioCode:scenario.value||undefined})
    if(version!==requestVersion)return
    cases.value=response.data?.records||[]
    totalGraphCases.value=Number(response.data?.total||0)
    await loadGraph(version)
  }catch(error:any){
    ElMessage.error(error.message||'案件列表加载失败')
  }finally{
    if(version===requestVersion)loading.value=false
  }
}
async function loadGraph(version:number){
  loading.value=true
  selected.value=null
  try{
    let failedGraphCount=0
    const graphs=await mapGraphs(filteredCases.value,async item=>{
      const graphResponse:any=await getCaseGraphApi(item.caseId)
        .catch(()=>{
          failedGraphCount+=1
          return {data:{nodes:[],edges:[],chains:[],multiStageMatter:null}}
        })
      const presentation=graphResponse.data||{}
      return {
        caseId:item.caseId,
        caseName:item.caseName||item.caseId,
        graph:{nodes:presentation.nodes||[],edges:presentation.edges||[]},
        knowledge:presentation
      }
    })
    if(version!==requestVersion)return
    let caseCount=0
    const mergedNodes:any[]=[], mergedEdges:any[]=[]
    const seen=new Set<string>(), edgeSeen=new Set<string>()
    const caseNodeIds=new Map<string,string>()
    graphs.forEach(item=>{
      const graph=item.graph||{}
      const idMap=new Map<string,string>()
      const typeOrders=new Map<string,number>()
      const hasCaseNode=(graph.nodes||[]).some((node:any)=>rawNodeType(node)==='CASE')
      if(hasCaseNode)caseCount+=1
      ;(graph.nodes||[]).filter((node:any)=>!reasoningRawTypes.has(rawNodeType(node))).forEach((node:any)=>{
        const raw=String(node.id||node.uid)
        const id=`${item.caseId}::${raw}`
        idMap.set(raw,id)
        if(!seen.has(id)){
          seen.add(id)
          const originalCaseId=String(node.properties?.caseId||node.caseId||raw)
          const rawType=rawNodeType(node)
          const visualOrder=(typeOrders.get(rawType)||0)+1
          typeOrders.set(rawType,visualOrder)
          const mapped={...node,id,visualCaseId:item.caseId,visualCaseName:item.caseName,visualOrder,visualPrimary:rawType==='CASE'&&originalCaseId===item.caseId,properties:{...(node.properties||{})}}
          mergedNodes.push(mapped)
          if(rawType==='CASE'&&!caseNodeIds.has(item.caseId))caseNodeIds.set(item.caseId,id)
        }
      })
      ;(graph.edges||[]).forEach((edge:any)=>{
        const source=idMap.get(String(edge.source))||`${item.caseId}::${String(edge.source)}`
        const target=idMap.get(String(edge.target))||`${item.caseId}::${String(edge.target)}`
        const key=`${source}|${target}|${graphEdgeName(edge)}`
        if(!edgeSeen.has(key)){
          edgeSeen.add(key)
          const mapped={...edge,source,target}
          mergedEdges.push(mapped)
        }
      })
    })
    const similarityResponse:any=await getApprovedCaseSimilarityGraphApi().catch(()=>({data:[]}))
    ;(similarityResponse.data||[]).forEach((relation:any)=>{
      const source=caseNodeIds.get(relation.sourceCaseId),target=caseNodeIds.get(relation.targetCaseId)
      if(!source||!target)return
      const key=`${source}|${target}|SIMILAR_CASE`
      if(edgeSeen.has(key))return
      edgeSeen.add(key)
      mergedEdges.push({id:key,source,target,type:'SIMILAR_CASE',label:`相似 #${relation.finalRank}`,
        properties:{finalRank:relation.finalRank,weight:relation.weight,similarity:relation.similarity,
          manuallyReordered:relation.manuallyReordered}})
    })
    if(version!==requestVersion)return
    validCaseCount.value=caseCount
    nodes.value=mergedNodes
    edges.value=mergedEdges
    if(filteredCases.value.length&&failedGraphCount===filteredCases.value.length){
      ElMessage.error('图谱服务连接失败，请检查图数据库配置')
    }
  }finally{
    if(version===requestVersion)loading.value=false
  }
}


onMounted(async()=>{let saved:any=null;try{saved=JSON.parse(localStorage.getItem(viewStorageKey())||'null')}catch{};if(saved){if(Number.isInteger(saved.windowSize))windowSize.value=Math.max(1,Math.min(PANORAMA_CASE_LIMIT,saved.windowSize));scenario.value=['AML','ANTI_FRAUD'].includes(saved.scenario)?saved.scenario:'';for(const item of nodeTypes)if(typeof saved.visibleTypes?.[item.key]==='boolean')visibleTypes.value[item.key]=saved.visibleTypes[item.key];clustered.value=saved.clustered===true;hideLowFrequency.value=Boolean(saved.hideLowFrequency)}await loadCases();await nextTick();if(saved){selected.value=nodeMap.value.get(String(saved.selectedId))||null;detailCollapsed.value=!selected.value||Boolean(saved.detailCollapsed);focusOnly.value=Boolean(saved.focusOnly&&selected.value);await nextTick();await graphCanvas.value?.restoreViewState(saved.canvas)}})
</script>

<style scoped>
.visualize-page{height:100%;min-height:0;overflow:hidden;color:#263d57}.panorama-workspace{height:100%;min-height:0;display:grid;grid-template-columns:minmax(0,1fr) 264px;grid-template-rows:44px minmax(0,1fr);border:1px solid #e0e7ef;border-radius:11px;background:#fff;overflow:hidden;box-shadow:0 3px 14px #263d5706}.panorama-workspace.detail-collapsed{grid-template-columns:minmax(0,1fr) 38px}.workspace-toolbar{grid-column:1/-1;display:flex;align-items:center;gap:12px;padding:0 12px;border-bottom:1px solid #e9edf3;min-width:0}.toolbar-scope{display:flex;gap:6px;align-items:center;white-space:nowrap}.toolbar-scope strong{font-size:13px;margin-right:2px}.stat{color:#7d8fa4;background:#f3f6f9;border-radius:12px;font-size:11px;padding:3px 8px;font-variant-numeric:tabular-nums}.filters-group{display:flex;gap:8px;align-items:center;padding-left:12px;border-left:1px solid #e8edf3;min-width:0}.scenario-select{width:136px;flex:none}.entity-search{width:210px;min-width:120px}.toolbar-actions{display:flex;gap:6px;align-items:center;margin-left:auto;flex:none}.toolbar-actions :deep(.el-button){height:30px;margin-left:0;font-size:12px;padding:0 11px}.toolbar-actions :deep(.el-button:first-child){padding:0 8px}.workspace-toolbar :deep(.el-input__wrapper),.workspace-toolbar :deep(.el-select__wrapper){min-height:30px;font-size:12px}.graph-column{min-width:0;min-height:0;display:flex;flex-direction:column;overflow:hidden}.legend-row{display:flex;gap:4px;align-items:center;min-height:34px;padding:5px 11px;border-bottom:1px solid #edf1f5;flex-wrap:wrap}.legend-row>button{border:0;background:none;display:flex;gap:5px;align-items:center;padding:2px 4px;font-size:11px;color:#687e96;cursor:pointer;border-radius:4px}.legend-row>button:hover{background:#f1f6ff}.legend-row>button.muted{opacity:.35}.type-mark{width:9px;height:9px;border-radius:50%}.type-mark.case,.type-mark.account,.type-mark.wallet{border-radius:3px}.type-mark.device,.type-mark.merchant{transform:rotate(45deg);border-radius:1px}.legend-row em{font-size:10px;color:#92a0af;font-style:normal}.relation-legend{display:flex;gap:10px;align-items:center;margin-left:auto;padding:2px 0;color:#7a8da3;font-size:10px}.relation-legend span{display:flex;align-items:center;gap:5px;white-space:nowrap}.relation-legend i{width:18px;border-top:1.5px solid #9aabbe}.relation-legend .flow{border-color:#5c82b0}.relation-legend .shared{border-top:2px dashed #8d9fb6}.relation-legend .device{border-top:2px dotted #53988b}.more-type-list{display:grid;grid-template-columns:1fr 1fr}.graph-tools{height:34px;flex:none;display:flex;gap:6px;align-items:center;padding:0 11px;border-bottom:1px solid #edf1f5;background:#fcfdff}.case-window{display:flex;align-items:center;gap:6px;color:#73859b;font-size:11px;white-space:nowrap}.case-window :deep(.el-input-number){width:74px}.case-window :deep(.el-input__wrapper){min-height:24px}.graph-tools button{height:24px;border:1px solid #e2e9f2;border-radius:5px;color:#73859b;background:white;font:inherit;font-size:11px;padding:0 8px;cursor:pointer}.graph-tools button.active{color:#3478e5;background:#f0f6ff;border-color:#d9e7ff}.graph-tools button:disabled{opacity:.42;cursor:not-allowed}.scope-info{margin-left:auto;color:#92a0b0;font-size:10px;white-space:nowrap}.graph-tools .icon-tool{width:26px;padding:0;display:grid;place-items:center;color:#72869e}.node-inspector{min-width:0;min-height:0;display:flex;flex-direction:column;border-left:1px solid #e5ebf2;background:#fff}.inspector-heading{height:34px;flex:none;padding:0 12px;display:flex;align-items:center;border-bottom:1px solid #edf1f5;gap:7px}.inspector-heading strong{font-size:12px}.inspector-heading button{width:23px;height:23px;margin-left:auto;border:1px solid #e2e9f2;border-radius:5px;background:#fff;display:grid;place-items:center;color:#7d8fa4;cursor:pointer}.detail-collapsed .inspector-heading{flex-direction:column-reverse;justify-content:flex-end;height:100%;padding:10px 0;gap:12px;border-bottom:0}.detail-collapsed .inspector-heading button{margin:0}.detail-collapsed .inspector-heading strong{writing-mode:vertical-rl;letter-spacing:1px;font-size:11px}.inspector-content{overflow:auto;min-height:0;padding:11px 12px 14px;flex:1}.entity-card{border:1px solid #e3eaf2;border-radius:9px;padding:10px;background:#fcfdff}.entity-kind{display:flex;align-items:center;gap:6px;font-size:11px;color:#7b8da4}.entity-kind i{width:8px;height:8px;border-radius:3px}.entity-card h2{font-size:16px;line-height:1.5;margin:7px 0 3px;color:#243c58;overflow-wrap:anywhere}.entity-id{font-size:10px;color:#90a0b3;overflow-wrap:anywhere}.risk-tag{display:inline-block;font-size:10px;margin-top:7px;padding:3px 7px;border-radius:12px;background:#eff6ff;color:#5780aa}.risk-tag.high{background:#fff4e6;color:#b87b23}.entity-card dl,.selected-relation dl{display:grid;gap:7px;margin:10px 0 0}.entity-card dl>div,.selected-relation dl>div{display:grid;grid-template-columns:64px minmax(0,1fr);gap:7px;font-size:11px}.entity-card dt,.selected-relation dt{color:#8b9aac}.entity-card dd,.selected-relation dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap;color:#536a85;max-height:96px;overflow:auto}.section-heading{display:flex;align-items:center;margin:15px 0 4px;font-size:12px}.section-heading button,.section-heading>span{margin-left:auto;font-size:10px;color:#91a0b3;border:0;background:none;cursor:pointer;display:flex;align-items:center}.relation-item{width:100%;padding:9px 0;display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid #edf1f6;background:white;text-align:left;cursor:pointer}.relation-item:hover,.relation-item.active{background:#f5f9ff}.relation-icon{width:25px;height:25px;flex:none;display:grid;place-items:center;border-radius:7px;background:#eff5ff}.relation-main{min-width:0;flex:1}.relation-main b{display:block;font-weight:600;font-size:11px;color:#455e7a;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.relation-main small{display:block;font-size:10px;color:#8c9bb0;margin-top:2px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.relation-arrow{font-size:10px;color:#a0adbe}.key-path{margin-top:14px;padding:10px;border-radius:7px;background:#f6f8fc}.key-path>strong{display:block;font-size:11px;font-weight:600;color:#7488a1;margin-bottom:8px}.key-path>div{display:flex;flex-wrap:wrap;gap:5px;align-items:center;color:#8c9cb0}.key-path button{max-width:90px;border:1px solid #e3e9f1;border-radius:5px;padding:4px 6px;background:white;color:#7388a2;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:10px;cursor:pointer}.expand-relations{margin:11px 0 0;width:100%;font-size:11px;color:#3478e5}.selected-relation{padding:10px;border:1px solid #dce8fb;border-radius:8px;margin-top:10px;background:#f7faff}.selected-relation strong{display:block;font-size:12px;color:#3478e5}.selected-relation>span{display:block;font-size:10px;color:#7890ac;margin-top:5px;overflow-wrap:anywhere}.search-result{display:flex;gap:7px;align-items:center;max-width:450px}.search-result i{width:8px;height:8px;border-radius:50%;flex:none}.search-result span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:12px}.search-result small{margin-left:auto;font-size:10px;color:#93a2b6;white-space:nowrap}.panorama-workspace:fullscreen{border-radius:0;height:100vh;width:100vw}.panorama-workspace :deep(button:focus-visible){outline:2px solid #3478e5;outline-offset:2px}@media(max-width:1250px){.panorama-workspace{grid-template-columns:minmax(0,1fr) 240px}.workspace-toolbar{gap:8px;padding:0 10px}.toolbar-scope strong{display:none}.entity-search{width:180px}.relation-legend{gap:6px}.scope-info{max-width:120px;overflow:hidden;text-overflow:ellipsis}}@media(max-width:1000px){.stat{padding:3px 5px;font-size:10px}.filters-group{padding-left:7px;gap:6px}.scenario-select{width:115px}.entity-search{width:155px}.relation-legend{display:none}.toolbar-actions :deep(.el-button){padding:0 7px;font-size:11px}.panorama-workspace{grid-template-columns:minmax(0,1fr) 220px}}@media(max-width:800px){.workspace-toolbar{flex-wrap:wrap;padding:6px 8px;gap:6px}.panorama-workspace{grid-template-rows:auto minmax(0,1fr);position:relative}.toolbar-actions{margin-left:auto}.toolbar-scope{display:none}.node-inspector{position:absolute;right:0;top:72px;bottom:0;width:240px;z-index:5;box-shadow:-5px 0 18px #243c5810}.detail-collapsed .node-inspector{position:static;width:auto;box-shadow:none}.graph-column{grid-column:1/-1}.detail-collapsed .graph-column{grid-column:auto}.graph-tools{gap:4px;padding:0 7px}.graph-tools button{font-size:10px;padding:0 6px}.scope-info{display:none}}
</style>
