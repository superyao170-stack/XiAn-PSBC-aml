<template>
  <div class="visualize-page">
    <el-card class="graph-card">
      <div class="filter-row">
        <el-form :inline="true">
          <el-form-item label="时间窗口"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="loadGraph" /></el-form-item>
          <el-form-item label="案例场景"><el-select v-model="scenarios" multiple collapse-tags placeholder="全部场景" style="width:240px" @change="loadGraph"><el-option label="反洗钱" value="AML"/><el-option label="反欺诈" value="ANTI_FRAUD"/></el-select></el-form-item>
        </el-form>
        <div class="graph-actions">
          <el-button :disabled="filteredCases.length < 2" @click="openAnalysis('/graph/association-clues')">关联线索分析</el-button>
          <el-button type="primary" :disabled="filteredCases.length < 2" @click="openAnalysis('/graph/hidden-risk')">隐蔽风险挖掘</el-button>
          <div class="layout-switch" role="group" aria-label="图谱布局">
            <button v-for="item in layouts" :key="item.key" :class="{active:layout===item.key}" @click="layout=item.key">
              {{ item.label }}
            </button>
          </div>
        </div>
      </div>
      <div class="legend">
        <div v-for="plane in graphPlanes" :key="plane.key" class="legend-plane">
          <button class="plane-toggle"
            :class="{active:isPlaneEnabled(plane.key),partial:isPlanePartiallyEnabled(plane.key)}"
            :aria-pressed="isPlaneEnabled(plane.key)"
            @click="togglePlane(plane.key)">
            <i></i><b>{{ plane.label }}</b>
          </button>
          <button v-for="item in plane.items" :key="item.key" class="legend-item" :class="{ muted: !visibleTypes[item.key] }" @click="toggleType(item.key)">
            <span class="legend-icon" :style="{ background: item.color }">{{ item.icon }}</span>
            <span>{{ item.label }}</span><em>{{ typeCounts[item.key] || 0 }}</em>
          </button>
          <span class="legend-summary">{{ summaryText }}</span>
        </div>
      </div>
      <div class="graph-stage">
        <G6GraphCanvas
          ref="graphCanvas"
          :nodes="displayedNodes"
          :edges="displayedEdges"
          :type-meta="g6TypeMeta"
          :layout="layout"
          :height="720"
          @select="selected=$event"
        />
        <aside v-if="selected" class="node-inspector">
          <button class="close" title="关闭" @click="clearSelected">×</button>
          <div class="inspector-title">
            <i :style="{background:typeMeta(selected).color}">{{ typeMeta(selected).icon }}</i>
            <div><small>节点详情 · {{ typeMeta(selected).label }} · {{ selectedPlaneName }}</small><b>{{ nodeName(selected) }}</b></div>
          </div>
          <dl>
            <div v-for="item in selectedAttributes" :key="item.key">
              <dt>{{ item.key }}</dt><dd :title="item.value">{{ item.value }}</dd>
            </div>
          </dl>
        </aside>
      </div>
      <el-empty
        v-if="!loading&&!nodes.length"
        class="graph-empty-state"
        description="当前过滤条件没有可展示的案件图谱"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import G6GraphCanvas from '@/components/graph/G6GraphCanvas.vue'
import { getCasesApi, getCaseGraphApi } from '@/api/case'
import { getApprovedCaseSimilarityGraphApi } from '@/api/caseProcessing'
import { formatDateTime } from '@/utils/datetime'
import { saveAnalysisScope } from '@/utils/analysisScope'

type LayoutName = 'grid'|'force'|'radial'|'concentric'
type GraphPlane = 'EVENT_GRAPH'
type NodeType = {key:string;label:string;color:string;icon:string;raw:string[];plane:GraphPlane}

const cases=ref<any[]>([]), dateRange=ref<string[]>([]), scenarios=ref<string[]>(['AML','ANTI_FRAUD'])
const totalApprovedCases=ref(0)
const PANORAMA_CASE_LIMIT=50
const router=useRouter()
const nodes=ref<any[]>([]), edges=ref<any[]>([]), selected=ref<any>(null), validCaseCount=ref(0), loading=ref(false)
const graphCanvas=ref<InstanceType<typeof G6GraphCanvas>>()
const layout=ref<LayoutName>('grid')
const layouts:Array<{key:LayoutName;label:string}>=[
  {key:'grid',label:'均匀分布'},
  {key:'force',label:'关系探索'},
  {key:'radial',label:'辐射视图'},
  {key:'concentric',label:'同心分层'}
]
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
  {key:'OTHER',label:'其他',color:'#64748b',icon:'其',raw:[],plane:'EVENT_GRAPH'}
]
const reasoningRawTypes=new Set(['INDICATORRESULT','BEHAVIORPATTERN','BEHAVIORPATTERNOCCURRENCE','RISKHYPOTHESIS','RISKEVENT','ATTACKPATHCANDIDATE','INVESTIGATIONHYPOTHESIS','TECHNIQUEOCCURRENCE','TECHNIQUE','TACTIC','INDICATORDEFINITION','BEHAVIORPATTERNDEFINITION','BASEEVENTTYPEDEFINITION','RISKEVENTTYPEDEFINITION','ATTACKPATHTYPEDEFINITION','MATTER','ALTERNATIVEEXPLANATION'])
const normalizeType=(value:any)=>String(value||'OTHER').replace(/[^A-Za-z0-9]/g,'').toUpperCase()
const rawNodeType=(node:any)=>normalizeType(node.label||node.properties?.nodeType||node.type)
const nodeType=(node:any)=>nodeTypes.find(item=>item.raw.includes(rawNodeType(node)))?.key||'OTHER'
const typeMeta=(node:any)=>nodeTypes.find(item=>item.key===nodeType(node))||nodeTypes[nodeTypes.length-1]
const visibleTypes=ref<Record<string,boolean>>(Object.fromEntries(nodeTypes.map(item=>[item.key,true])))
const displayedNodes=computed(()=>nodes.value.filter(node=>visibleTypes.value[nodeType(node)]).map(node=>{
  const type=nodeType(node)
  const meta=nodeTypes.find(item=>item.key===type)||nodeTypes[nodeTypes.length-1]
  return {...node,visualType:type,visualPlane:meta.plane,visualLabel:nodeName(node)}
}))
const displayedEdges=computed(()=>{
  const ids=new Set(displayedNodes.value.map(node=>String(node.id)))
  return edges.value.filter(edge=>ids.has(String(edge.source))&&ids.has(String(edge.target)))
})
const typeCounts=computed(()=>nodes.value.reduce((counts:Record<string,number>,node:any)=>{
  const type=nodeType(node);counts[type]=(counts[type]||0)+1;return counts
},{}))
const graphPlanes=computed(()=>[{key:'EVENT_GRAPH',label:'案例图谱',items:nodeTypes}])
const planeTypeKeys=(plane:string)=>nodeTypes.filter(item=>item.plane===plane).map(item=>item.key)
const isPlaneEnabled=(plane:string)=>planeTypeKeys(plane).every(key=>visibleTypes.value[key])
const isPlanePartiallyEnabled=(plane:string)=>{
  const keys=planeTypeKeys(plane)
  const enabled=keys.filter(key=>visibleTypes.value[key]).length
  return enabled>0&&enabled<keys.length
}
const togglePlane=(plane:string)=>{
  const keys=planeTypeKeys(plane)
  const enabled=!keys.every(key=>visibleTypes.value[key])
  keys.forEach(key=>{visibleTypes.value[key]=enabled})
  if(selected.value&&!visibleTypes.value[nodeType(selected.value)])selected.value=null
}
const g6TypeMeta=computed(()=>Object.fromEntries(nodeTypes.map(item=>[item.key,{name:item.label,color:item.color,icon:item.icon,planeName:'案例图谱'}])))
const filteredCases=computed(()=>cases.value.filter(item=>{
  if(scenarios.value.length&&!scenarios.value.includes(item.scenarioCode))return false
  const date=formatDateTime(item.createdAt).slice(0,10)
  return !dateRange.value?.length||(date>=dateRange.value[0]&&date<=dateRange.value[1])
}))
const summaryText=computed(()=>{
  const scope=totalApprovedCases.value>cases.value.length
    ? `${validCaseCount.value}/${totalApprovedCases.value} 个案件已入图（当前展示最近 ${cases.value.length} 个）`
    : `${validCaseCount.value} 个案件已入图`
  return `${scope}：共 ${displayedNodes.value.length} 个节点、${displayedEdges.value.length} 条关系`
})
const nodeName=(node:any)=>{
  const properties=node.properties||{}
  if(nodeType(node)==='CASE')return String(node.name||properties.caseName||properties.name||properties.caseId||node.caseId||node.uid||node.id||'未命名案件')
  return String(node.name||properties.displayName||properties.eventName||properties.event_name||properties.name
    ||properties.customerName||properties.organizationName||properties.merchantName||properties.deviceName
    ||properties.ipAddress||properties.address||properties.indicatorName||properties.patternName||properties.hypothesis
    ||properties.title||properties.summary||properties.accountNo||properties.accountAlias||friendlyNodeFallback(node))
}
const friendlyNodeFallback=(node:any)=>{
  const label=typeMeta(node).label
  const graphId=String(node.properties?.graphId||node.properties?.instanceId||'')
  const suffix=graphId.match(/_(\d{1,6})$/)?.[1]
  const order=Number(node.visualOrder||0)
  return suffix?`${label} ${suffix}`:order?`${label} ${String(order).padStart(2,'0')}`:`未命名${label}`
}
const selectedAttributes=computed(()=>Object.entries(selected.value?.properties||selected.value||{})
  .filter(([,value])=>value!==null&&value!==undefined&&value!==''&&typeof value!=='object')
  .slice(0,14)
  .map(([key,value])=>({key,value:String(value)})))
const selectedPlaneName=computed(()=>'案例图谱')
const clearSelected=async()=>{
  selected.value=null
  await graphCanvas.value?.clearSelection()
}
const openAnalysis=(path:string)=>{
  const caseIds=filteredCases.value.map(item=>item.caseId)
  saveAnalysisScope({
    caseIds,scenarioCode:scenarios.value.join(','),
    startTime:dateRange.value?.[0]||'',endTime:dateRange.value?.[1]||''
  })
  router.push({path,query:{caseIds:caseIds.join(','),scenarioCode:scenarios.value.join(',')||undefined,
    startTime:dateRange.value?.[0]||undefined,endTime:dateRange.value?.[1]||undefined}})
}

const toggleType=(key:string)=>{
  visibleTypes.value[key]=!visibleTypes.value[key]
  if(selected.value&&!visibleTypes.value[nodeType(selected.value)])selected.value=null
}
async function loadCases(){
  loading.value=true
  try{
    // Keep the panorama usable instead of starting thousands of simultaneous
    // case-graph requests. The UI labels the bounded recent-case window.
    const response:any=await getCasesApi({pageNum:1,pageSize:PANORAMA_CASE_LIMIT,caseStatus:'APPROVED'})
    cases.value=response.data?.records||[]
    totalApprovedCases.value=Number(response.data?.total||0)
    await loadGraph()
  }catch(error:any){
    ElMessage.error(error.message||'案件列表加载失败')
  }finally{
    loading.value=false
  }
}
async function loadGraph(){
  loading.value=true
  selected.value=null
  try{
    const graphs=await Promise.all(filteredCases.value.map(async item=>{
      const graphResponse:any=await getCaseGraphApi(item.caseId)
        .catch(()=>({data:{nodes:[],edges:[],chains:[],multiStageMatter:null}}))
      const presentation=graphResponse.data||{}
      return {
        caseId:item.caseId,
        caseName:item.caseName||item.caseId,
        graph:{nodes:presentation.nodes||[],edges:presentation.edges||[]},
        knowledge:presentation
      }
    }))
    validCaseCount.value=0
    const mergedNodes:any[]=[], mergedEdges:any[]=[]
    const seen=new Set<string>(), edgeSeen=new Set<string>()
    const caseNodeIds=new Map<string,string>()
    graphs.forEach(item=>{
      const graph=item.graph||{}
      const idMap=new Map<string,string>()
      const typeOrders=new Map<string,number>()
      const hasCaseNode=(graph.nodes||[]).some((node:any)=>rawNodeType(node)==='CASE')
      if(hasCaseNode)validCaseCount.value+=1
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
        const key=`${source}|${target}|${String(edge.type||edge.label||'REL')}`
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
    nodes.value=mergedNodes
    edges.value=mergedEdges
  }finally{
    loading.value=false
  }
}

onMounted(loadCases)
</script>

<style scoped>
.graph-actions{display:flex;align-items:center;gap:7px}
.visualize-page{height:100%;overflow:auto}.graph-card :deep(.el-card__body){padding:18px}.filter-row{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.filter-row :deep(.el-form-item){margin-bottom:10px}.layout-switch{display:flex;flex:none;padding:3px;border:1px solid #dce5f0;border-radius:9px;background:#f7f9fc}.layout-switch button{border:0;border-radius:6px;background:transparent;padding:7px 11px;color:#617188;cursor:pointer}.layout-switch button.active{background:#fff;color:#1677ff;box-shadow:0 1px 5px rgba(50,72,99,.14);font-weight:600}.legend{display:grid;min-width:0;gap:2px;margin:5px 0 8px}.legend-plane{display:flex;min-width:0;align-items:center;flex-wrap:nowrap;gap:2px;padding:1px 0;overflow-x:auto}.plane-toggle{display:inline-flex;align-items:center;gap:4px;height:22px;flex:none;margin-right:2px;padding:0 7px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#475569;font-size:11px;cursor:pointer}.plane-toggle i{width:6px;height:6px;border-radius:50%;background:#94a3b8}.plane-toggle.active{border-color:#2563eb;background:#eff6ff;color:#1d4ed8}.plane-toggle.active i{background:#2563eb;box-shadow:0 0 0 2px #dbeafe}.plane-toggle.partial{border-color:#60a5fa;background:#f8fbff;color:#2563eb}.plane-toggle.partial i{background:linear-gradient(90deg,#2563eb 50%,#cbd5e1 50%)}.legend-item{display:inline-flex;height:22px;flex:none;align-items:center;gap:3px;border:1px solid #dbe3ef;background:#fff;border-radius:12px;padding:1px 5px 1px 2px;cursor:pointer;color:#334155;font-size:10px}.legend-item:hover{border-color:#93c5fd}.legend-item.muted{opacity:.32;filter:saturate(.3)}.legend-icon{display:inline-flex;width:16px;height:16px;align-items:center;justify-content:center;border-radius:50%;color:#fff;font-weight:700;font-size:9px;box-shadow:0 1px 3px rgba(71,85,105,.18)}.legend-item em{min-width:14px;padding:0 3px;border-radius:7px;background:#f1f5f9;color:#64748b;font-size:9px;font-style:normal;text-align:center}.legend-summary{margin-left:auto;flex:none;padding:0 2px 0 10px;color:#64748b;font-size:11px;white-space:nowrap}.graph-stage{position:relative;margin-top:6px}.node-inspector{position:relative;max-height:320px;overflow:auto;margin-top:12px;padding:16px 18px;border:1px solid rgba(202,216,233,.9);border-radius:12px;background:linear-gradient(180deg,#fff 0,#f8fafc 100%);box-shadow:0 8px 24px rgba(31,50,76,.09)}.close{position:absolute;right:10px;top:8px;border:0;background:transparent;color:#94a3b8;font-size:22px;cursor:pointer}.inspector-title{display:flex;align-items:center;gap:10px;padding-right:32px}.inspector-title i{display:inline-flex;width:38px;height:38px;flex:none;align-items:center;justify-content:center;border-radius:50%;color:#fff;font-style:normal;font-weight:700}.inspector-title div{display:flex;min-width:0;flex-direction:column;gap:2px}.inspector-title small{color:#7b8da5}.inspector-title b{overflow:hidden;color:#24364d;text-overflow:ellipsis;white-space:nowrap}.node-inspector dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px 18px;margin:15px 0 0}.node-inspector dl>div{min-width:0}.node-inspector dt{color:#8495aa;font-size:11px}.node-inspector dd{margin:2px 0 0;overflow:hidden;color:#344861;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.panorama-analysis-list{display:grid;gap:12px;margin-top:14px}.case-analysis-card{overflow:hidden;border:1px solid #d7e1ed;border-radius:12px;background:#fff;box-shadow:0 5px 18px rgba(31,50,76,.06)}.case-analysis-card>header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 14px;border-bottom:1px solid #e6edf5;background:#f5f8fc}.case-analysis-card>header b{overflow:hidden;color:#1e3a5f;text-overflow:ellipsis;white-space:nowrap}.case-analysis-card>header span{flex:none;color:#8493a7;font-size:10px}.graph-card :deep(.graph-empty-state.el-empty){margin-top:-610px;height:570px;pointer-events:none}@media(max-width:1050px){.filter-row{flex-direction:column}}@media(max-width:760px){.layout-switch{width:100%}.layout-switch button{flex:1}.node-inspector{max-height:360px}.case-analysis-card>header{align-items:flex-start;flex-direction:column}}
.knowledge-chain-table thead th{text-align:center}
</style>
