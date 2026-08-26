<template>
  <div class="g6-shell" :style="{ height: `${height}px` }">
    <div ref="container" class="g6-canvas"></div>
    <div class="g6-tools" aria-label="图谱视图工具">
      <button title="放大" @click="zoom(1.2)">＋</button>
      <button title="缩小" @click="zoom(0.82)">－</button>
      <button title="适应画布" @click="fitView()">适</button>
      <button title="重新布局" @click="resetLayout">重</button>
    </div>
    <div class="g6-guide">滚轮缩放 · 拖动画布 · 拖拽节点 · 点击查看详情</div>
    <transition name="tooltip">
      <div v-if="tooltip.visible" class="g6-tooltip" :style="{ left: `${tooltip.x}px`, top: `${tooltip.y}px` }">
        <b><i :style="{ background: tooltip.color }">{{ tooltip.icon }}</i>{{ tooltip.typeName }}</b>
        <strong>{{ tooltip.title }}</strong>
        <span v-for="item in tooltip.attributes" :key="item.key"><em>{{ item.key }}</em>{{ item.value }}</span>
      </div>
    </transition>
    <div v-if="loading" class="g6-loading"><span></span>图谱布局计算中</div>
    <div v-else-if="!nodes.length" class="g6-empty">暂无可展示的图谱数据</div>
  </div>
</template>

<script setup lang="ts">
import { CanvasEvent, EdgeEvent, Graph, GraphEvent, NodeEvent } from '@antv/g6'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

export interface G6TypeMeta {
  name: string
  color: string
  icon: string
  planeName?: string
}

type LayoutName = 'grid' | 'force' | 'dagre' | 'radial' | 'concentric'

const props = withDefaults(defineProps<{
  nodes: any[]
  edges: any[]
  typeMeta: Record<string, G6TypeMeta>
  layout?: LayoutName
  height?: number
  highlightedIds?: string[]
}>(), {
  layout: 'grid',
  height: 680,
  highlightedIds: () => []
})

const emit = defineEmits<{
  select: [node: any | null]
  selectEdge: [edge: any | null]
  ready: []
}>()

const container = ref<HTMLElement>()
const loading = ref(false)
const tooltip = reactive({
  visible: false,
  x: 0,
  y: 0,
  title: '',
  typeName: '',
  color: '#64748b',
  icon: '其',
  attributes: [] as Array<{ key: string; value: string }>
})

let graph: Graph | null = null
let resizeObserver: ResizeObserver | null = null
let renderVersion = 0
let pendingLayoutFit = false
let lastObservedWidth = 0
const rawNodes = new Map<string, any>()
const rawEdges = new Map<string, any>()
const edgeEndpoints = new Map<string, { source: string; target: string }>()

const edgePalette = [
  '#2563eb',
  '#dc2626',
  '#059669',
  '#7c3aed',
  '#ea580c',
  '#0891b2',
  '#be123c',
  '#4f46e5',
  '#0f766e',
  '#a16207'
]
const tooltipFieldLabels: Record<string, string> = {
  name: '名称', displayName: '显示名称', title: '标题',
  caseName: '案例名称', description: '说明', summary: '摘要', proves: '证明事项',
  type: '业务类型', eventName: '事件名称', event_name: '事件名称',
  eventType: '事件类型', event_type: '事件类型', eventTime: '事件时间', event_time: '事件时间',
  startedAt: '开始时间', started_at: '开始时间', endedAt: '结束时间', ended_at: '结束时间',
  amount: '金额', currency: '币种', riskLevel: '风险等级', risk_level: '风险等级',
  customerNo: '客户号', customer_no: '客户号', idType: '证件类型', id_type: '证件类型',
  accountNo: '账号', account_no: '账号', holderName: '账户持有人', holder_name: '账户持有人',
  address: '地址', nationality: '国籍', openedAt: '开户日期', opened_at: '开户日期',
  organizationName: '机构名称', merchantName: '商户名称',
  deviceName: '设备名称', deviceType: '设备类型', device_type: '设备类型',
  ipAddress: 'IP地址', ip_address: 'IP地址', walletAddress: '钱包地址', wallet_address: '钱包地址',
  scenarioCode: '场景编码', bankCode: '所属银行', caseStatus: '案例状态',
  source: '来源',
  dataSource: '数据来源', data_source: '数据来源', confidence: '置信度',
  certainty: '确定性', status: '状态', patternCode: '模式编码',
  hypothesisCode: '假设编码', result: '结果', value: '数值', threshold: '阈值',
  relationName: '关系', edgeOrigin: '关系来源', directionSemantics: '关系方向'
}
const tooltipFieldOrder = Object.keys(tooltipFieldLabels)
const internalTooltipFields = new Set([
  'uid', 'namespace_uid', 'namespaceUid', 'id', 'sourceId', 'targetId',
  'accountHash', 'account_hash', 'hash', 'sha256', 'snapshotId', 'snapshot_id',
  'createdAt', 'created_at', 'updatedAt', 'updated_at', 'canonicalType', 'nodeType',
  'edgeCategory', 'edgeOriginCode', 'semanticLevel', 'visualType', 'visualLabel',
  'x', 'y', 'style'
])
const commonNameFields = ['name', 'displayName', 'title']
const nodeTooltipFieldsByType: Record<string, string[]> = {
  CASE: [...commonNameFields, 'caseName', 'scenarioCode', 'bankCode', 'caseStatus', 'riskLevel', 'risk_level', 'summary', 'description'],
  EVENT: [...commonNameFields, 'eventName', 'event_name', 'eventType', 'event_type', 'eventTime', 'event_time', 'startedAt', 'started_at', 'endedAt', 'ended_at', 'amount', 'currency', 'riskLevel', 'risk_level', 'summary', 'description', 'dataSource', 'data_source'],
  EVIDENCE: [...commonNameFields, 'type', 'summary', 'proves', 'source'],
  CUSTOMER: [...commonNameFields, 'customerNo', 'customer_no', 'idType', 'id_type', 'nationality', 'address', 'riskLevel', 'risk_level'],
  ACCOUNT: [...commonNameFields, 'accountNo', 'account_no', 'holderName', 'holder_name', 'type', 'openedAt', 'opened_at', 'riskLevel', 'risk_level'],
  ORGANIZATION: [...commonNameFields, 'organizationName', 'type', 'address'],
  MERCHANT: [...commonNameFields, 'merchantName', 'type', 'address'],
  WALLET: [...commonNameFields, 'walletAddress', 'wallet_address', 'type'],
  DEVICE: [...commonNameFields, 'deviceName', 'deviceType', 'device_type'],
  IPADDRESS: [...commonNameFields, 'ipAddress', 'ip_address', 'address'],
  ADDRESS: [...commonNameFields, 'address', 'type'],
  INDICATORRESULT: [...commonNameFields, 'result', 'value', 'threshold', 'confidence', 'status', 'summary'],
  BEHAVIORPATTERN: [...commonNameFields, 'patternCode', 'confidence', 'certainty', 'status', 'summary'],
  RISKHYPOTHESIS: [...commonNameFields, 'hypothesisCode', 'riskLevel', 'risk_level', 'confidence', 'certainty', 'status', 'summary'],
  INVESTIGATIONHYPOTHESIS: [...commonNameFields, 'hypothesisCode', 'confidence', 'certainty', 'status', 'summary']
}

const normalizeType = (value: any) => String(value || 'OTHER').replace(/[\s_-]/g, '').toUpperCase()
const nodeId = (node: any) => String(node.id ?? node.uid ?? '')
const sourceId = (edge: any) => String(edge.source ?? edge.from ?? edge.start ?? '')
const targetId = (edge: any) => String(edge.target ?? edge.to ?? edge.end ?? '')
const nodeType = (node: any) => normalizeType(
  node.visualType || node.properties?.canonicalType || node.properties?.nodeType || node.type || node.label
)
const metaFor = (node: any): G6TypeMeta => {
  const type = nodeType(node)
  return props.typeMeta[type] || props.typeMeta.OTHER || { name: '其他节点', color: '#64748b', icon: '其' }
}
const nodePlane=(node:any)=>{
  const value=String(node.visualPlane||metaFor(node).planeName||'EVENT_GRAPH')
  return value==='REASONING_GRAPH'||value==='事理图谱'?'REASONING_GRAPH':'EVENT_GRAPH'
}
const nodeTitle = (node: any) => String(
  node.visualLabel || node.name || node.properties?.caseName || node.properties?.caseId ||
  node.properties?.eventName || node.properties?.name || node.properties?.accountNo ||
  node.properties?.accountHash || node.properties?.title || node.properties?.summary ||
  node.uid || node.id || '未命名节点'
)
const shorten = (value: string, max = 22) => Array.from(value).length > max ? `${Array.from(value).slice(0, max).join('')}...` : value
const abbreviate = (value: any) => {
  const chars=Array.from(String(value || ''))
  return chars.length>3?`${chars.slice(0,3).join('')}…`:chars.join('')
}
const edgeColor = (value: any) => {
  const label = String(value || 'REL')
  let hash = 2166136261
  for (let i = 0; i < label.length; i += 1) {
    hash ^= label.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return edgePalette[(hash >>> 0) % edgePalette.length]
}
const nodeAttributes = (node: any) => {
  const type = nodeType(node)
  const allowed = new Set(nodeTooltipFieldsByType[type] || commonNameFields)
  const titleFields = new Set([
    ...commonNameFields,
    ...(type === 'EVENT' ? ['eventName', 'event_name'] : []),
    ...(type === 'CASE' ? ['caseName'] : [])
  ])
  const seen = new Set<string>()
  return Object.entries(node.properties || node)
    .filter(([key, value]) => allowed.has(key) && !internalTooltipFields.has(key) && tooltipFieldLabels[key] && value !== '' && value !== null && value !== undefined && typeof value !== 'object')
    .sort(([left], [right]) => tooltipFieldOrder.indexOf(left) - tooltipFieldOrder.indexOf(right))
    .map(([sourceKey, value]) => ({
      sourceKey,
      key: tooltipFieldLabels[sourceKey],
      value: shorten(String(value), 46)
    }))
    // The node title already carries the event/case/object name. Do not repeat
    // name aliases in the hover attribute list, and collapse snake/camel
    // aliases that resolve to the same localized field and value.
    .filter(item => {
      if (titleFields.has(item.sourceKey)) return false
      const signature = `${item.key}|${item.value}`
      if (seen.has(signature)) return false
      seen.add(signature)
      return true
    })
    .slice(0, 6)
    .map(({ key, value }) => ({ key, value }))
}
const edgeDisplayName = (edge: any) => String(
  edge.visualLabel || edge.properties?.relationName ||
  edge.type || edge.label || edge.relationType || '关系'
)
const edgeAttributes = (edge: any) => {
  const source = sourceId(edge)
  const target = targetId(edge)
  const properties = edge.properties && typeof edge.properties === 'object' ? edge.properties : {}
  return [
    { key: '起点', value: shorten(nodeTitle(rawNodes.get(source) || { id: source }), 32) },
    { key: '终点', value: shorten(nodeTitle(rawNodes.get(target) || { id: target }), 32) },
    ...Object.entries(properties)
      .filter(([key, value]) => !internalTooltipFields.has(key) && tooltipFieldLabels[key] && value !== '' && value !== null && value !== undefined && typeof value !== 'object')
      .sort(([left], [right]) => tooltipFieldOrder.indexOf(left) - tooltipFieldOrder.indexOf(right))
      .slice(0, 4)
      .map(([key, value]) => ({ key: tooltipFieldLabels[key], value: shorten(String(value), 46) }))
  ]
}
const stablePoint = (id: string, index: number) => {
  let hash = 2166136261
  for (let i = 0; i < id.length; i += 1) {
    hash ^= id.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  const angle = ((hash >>> 0) % 360) * Math.PI / 180
  const radius = 90 + (index % 9) * 52
  return { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius }
}
const gridPoint = (index: number, total: number) => {
  const width = Math.max(620, (container.value?.clientWidth || 900) - 180)
  const height = Math.max(420, props.height - 180)
  const aspect = width / height
  const columns = Math.max(1, Math.ceil(Math.sqrt(total * aspect)))
  const rows = Math.max(1, Math.ceil(total / columns))
  const column = index % columns
  const row = Math.floor(index / columns)
  return {
    x: columns === 1 ? 0 : -width / 2 + column * width / (columns - 1),
    y: rows === 1 ? 0 : -height / 2 + row * height / (rows - 1)
  }
}
const nodeCaseKey=(node:any)=>String(node.visualCaseId||node.properties?.caseId||node.caseId||'')
type CaseRegion={x:number;y:number;width:number;height:number}
const equalAreaRegions=(total:number,width:number,height:number)=>{
  const regions:CaseRegion[]=[]
  const split=(count:number,region:CaseRegion,vertical:boolean)=>{
    if(count<=1){
      regions.push(region)
      return
    }
    const firstCount=Math.floor(count/2)
    const secondCount=count-firstCount
    const ratio=firstCount/count
    if(vertical){
      const firstWidth=region.width*ratio
      split(firstCount,{...region,width:firstWidth},false)
      split(secondCount,{x:region.x+firstWidth,y:region.y,width:region.width-firstWidth,height:region.height},false)
    }else{
      const firstHeight=region.height*ratio
      split(firstCount,{...region,height:firstHeight},true)
      split(secondCount,{x:region.x,y:region.y+firstHeight,width:region.width,height:region.height-firstHeight},true)
    }
  }
  split(Math.max(1,total),{x:-width/2,y:-height/2,width,height},width>=height)
  return regions
}
const caseGroupGeometry=(index:number,total:number)=>{
  const width=Math.max(720,(container.value?.clientWidth||1000)-180)
  const height=Math.max(440,props.height-180)
  const region=equalAreaRegions(total,width,height)[index]||{x:-width/2,y:-height/2,width,height}
  const angle=index*2.399963229728653
  const jitterX=total===1?0:Math.cos(angle)*region.width*.035
  const jitterY=total===1?0:Math.sin(angle)*region.height*.035
  return{
    center:{
      x:region.x+region.width/2+jitterX,
      y:region.y+region.height/2+jitterY
    },
    cellWidth:region.width,
    cellHeight:region.height
  }
}
const caseOrbitPoint=(geometry:any,index:number,total:number)=>{
  const progress=Math.sqrt((index+1)/Math.max(total,1))
  const angle=-Math.PI/2+index*2.399963229728653
  return{
    x:geometry.center.x+Math.cos(angle)*geometry.cellWidth*.43*progress,
    y:geometry.center.y+Math.sin(angle)*geometry.cellHeight*.41*progress
  }
}
const anchoredReasoningPoint=(anchor:any,index:number,fallback=false)=>{
  const radius=(fallback?68:30)+(fallback?13:9)*Math.sqrt(index+1)
  const angle=-Math.PI/2+index*2.399963229728653
  return{x:anchor.x+Math.cos(angle)*radius,y:anchor.y+Math.sin(angle)*radius}
}
function graphData() {
  rawNodes.clear()
  rawEdges.clear()
  edgeEndpoints.clear()
  const highlighted = new Set(props.highlightedIds.map(String))
  const isHighlighting = highlighted.size > 0
  const caseKeys=Array.from(new Set(props.nodes.filter(raw=>nodeType(raw)==='CASE').map(nodeCaseKey).filter(Boolean)))
  const groupGeometries=new Map(caseKeys.map((key,index)=>[key,caseGroupGeometry(index,caseKeys.length)]))
  const eventNodesByCase=new Map(caseKeys.map(key=>[key,props.nodes.filter(raw=>nodeType(raw)!=='CASE'&&nodeCaseKey(raw)===key&&nodePlane(raw)==='EVENT_GRAPH')]))
  const reasoningNodesByCase=new Map(caseKeys.map(key=>[key,props.nodes.filter(raw=>nodeType(raw)!=='CASE'&&nodeCaseKey(raw)===key&&nodePlane(raw)==='REASONING_GRAPH')]))
  const rawById=new Map(props.nodes.map(raw=>[nodeId(raw),raw]))
  const adjacency=new Map<string,Set<string>>()
  const connect=(left:string,right:string)=>{
    if(!adjacency.has(left))adjacency.set(left,new Set())
    adjacency.get(left)?.add(right)
  }
  props.edges.forEach(edge=>{
    const source=sourceId(edge),target=targetId(edge)
    if(source&&target){connect(source,target);connect(target,source)}
  })
  const eventPositions=new Map<string,{x:number;y:number}>()
  eventNodesByCase.forEach((items,key)=>{
    const geometry=groupGeometries.get(key)
    if(geometry)items.forEach((raw,index)=>eventPositions.set(nodeId(raw),caseOrbitPoint(geometry,index,items.length)))
  })
  const nearestEvent=(startId:string,caseKey:string)=>{
    const visited=new Set([startId])
    const queue=[startId]
    while(queue.length){
      const current=queue.shift() as string
      for(const next of adjacency.get(current)||[]){
        if(visited.has(next))continue
        visited.add(next)
        const raw=rawById.get(next)
        if(!raw||nodeCaseKey(raw)!==caseKey)continue
        if(nodeType(raw)==='EVENT')return next
        queue.push(next)
      }
    }
    return ''
  }
  const reasoningBuckets=new Map<string,any[]>()
  reasoningNodesByCase.forEach((items,key)=>items.forEach(raw=>{
    const anchorId=nearestEvent(nodeId(raw),key)
    const bucketKey=anchorId||`__case__${key}`
    if(!reasoningBuckets.has(bucketKey))reasoningBuckets.set(bucketKey,[])
    reasoningBuckets.get(bucketKey)?.push(raw)
  }))
  const reasoningPositions=new Map<string,{x:number;y:number}>()
  reasoningBuckets.forEach((items,anchorId)=>{
    const fallback=anchorId.startsWith('__case__')
    const caseKey=fallback?anchorId.slice(8):nodeCaseKey(rawById.get(anchorId))
    const anchor=fallback?groupGeometries.get(caseKey)?.center:eventPositions.get(anchorId)
    if(anchor)items.forEach((raw,index)=>reasoningPositions.set(nodeId(raw),anchoredReasoningPoint(anchor,index,fallback)))
  })
  const usePlaneLayout=caseKeys.length>0
  const nodes = props.nodes.map((raw, index) => {
    const id = nodeId(raw)
    const type = nodeType(raw)
    const meta = metaFor(raw)
    const isPrimary = Boolean(raw.visualPrimary ?? type === 'CASE')
    const active = !isHighlighting || highlighted.has(id) || isPrimary
    const caseKey=nodeCaseKey(raw)
    const geometry=groupGeometries.get(caseKey)
    const point = props.layout === 'grid'
      ? usePlaneLayout&&geometry
        ? type==='CASE'
          ? geometry.center
          : reasoningPositions.get(id)||eventPositions.get(id)||geometry.center
        : gridPoint(index,props.nodes.length)
      : stablePoint(id, index)
    rawNodes.set(id, raw)
    return {
      id,
      data: { raw, type, meta, title: nodeTitle(raw), active, primary: isPrimary, planeName: meta.planeName },
      style: { x: point.x, y: point.y }
    }
  }).filter(node => node.id)
  const ids = new Set(nodes.map(node => node.id))
  const edges:any[] = props.edges.map((raw, index) => {
    const source = sourceId(raw)
    const target = targetId(raw)
    const label = edgeDisplayName(raw)
    const id = `${String(raw.id ?? raw.uid ?? 'edge')}::${source}→${target}::${label}::${index}`
    rawEdges.set(id, raw)
    edgeEndpoints.set(id, { source, target })
    return {
      id,
      source,
      target,
      data: { raw, label, color: edgeColor(label), virtual: false }
    }
  }).filter(edge => edge.source && edge.target && ids.has(edge.source) && ids.has(edge.target))
  return { nodes, edges }
}

function layoutOptions() {
  if (props.layout === 'dagre') {
    return { type: 'antv-dagre', rankdir: 'LR', ranksep: 100, nodesep: 52, controlPoints: true, animation: true }
  }
  if (props.layout === 'radial') {
    return { type: 'radial', unitRadius: 115, preventOverlap: true, nodeSize: 52, linkDistance: 150, animation: true }
  }
  if (props.layout === 'concentric') {
    return { type: 'concentric', preventOverlap: true, nodeSize: 52, minNodeSpacing: 38, sortBy: 'degree', animation: true }
  }
  return {
    type: 'd3-force',
    link: { distance: 135, strength: 0.78 },
    manyBody: { strength: (node: any) => node.data?.type === 'CASE' ? -560 : -380 },
    center: { strength: 0.15 },
    x: { x: 0, strength: 0.075 },
    y: { y: 0, strength: 0.075 },
    collide: { radius: (node: any) => node.data?.type === 'CASE' ? 58 : 42, strength: 1 },
    alphaMin: 0.03,
    alphaDecay: props.nodes.length > 80 ? 0.15 : 0.12,
    velocityDecay: 0.42,
    animation: false
  }
}

function graphOptions() {
  return {
    container: container.value!,
    autoResize: true,
    data: graphData(),
    layout: props.layout === 'grid' ? undefined : layoutOptions(),
    animation: props.layout !== 'grid',
    background: '#f8fbff',
    behaviors: [
      'drag-canvas',
      'zoom-canvas',
      props.layout === 'force'
        ? 'drag-element-force'
        : { type: 'drag-element', enable: true, dropEffect: 'none', animation: false }
    ],
    node: {
      type: 'rect',
      style: (datum: any) => {
        const { meta, title, active, primary } = datum.data
        return {
          size: primary ? [84,38] : [68,32],
          radius: primary ? 12 : 10,
          fill: meta.color,
          fillOpacity: active ? 1 : 0.48,
          stroke: primary ? '#10243f' : '#ffffff',
          strokeOpacity: active ? 1 : 0.7,
          lineWidth: primary ? 4 : 2,
          shadowColor: active ? `${meta.color}55` : 'transparent',
          shadowBlur: active ? (primary ? 18 : 10) : 0,
          icon: true,
          iconText: abbreviate(title),
          iconFill: '#ffffff',
          iconFontSize: primary ? 14 : 13,
          iconFontWeight: 700,
          iconOpacity: active ? 1 : 0.72,
          label: false
        }
      },
      state: {
        normal: { opacity: 1 },
        selected: { halo: true, haloStroke: '#1677ff', haloLineWidth: 14, haloStrokeOpacity: 0.2, lineWidth: 4 },
        active: { opacity: 1 },
        inactive: { opacity: 0.14 }
      }
    },
    edge: {
      type: 'quadratic',
      style: (datum: any) => ({
        stroke: datum.data?.virtual ? 'transparent' : datum.data?.color,
        strokeOpacity: datum.data?.virtual ? 0 : 0.72,
        lineWidth: datum.data?.virtual ? 0 : 1.8,
        increasedLineWidthForHitTesting: datum.data?.virtual ? 0 : 8,
        endArrow: !datum.data?.virtual,
        endArrowType: 'triangle',
        endArrowSize: 7,
        endArrowFill: datum.data?.virtual ? 'transparent' : datum.data?.color,
        curveOffset: 10,
        label: !datum.data?.virtual,
        labelText: datum.data?.virtual ? '' : abbreviate(datum.data?.label),
        labelFill: datum.data?.virtual ? 'transparent' : datum.data?.color,
        labelFontSize: 9,
        labelFontWeight: 600,
        labelBackground: true,
        labelBackgroundFill: '#ffffff',
        labelBackgroundOpacity: 0.94,
        labelBackgroundStroke: datum.data?.virtual ? 'transparent' : `${datum.data?.color}44`,
        labelBackgroundLineWidth: 1,
        labelBackgroundRadius: 3,
        labelPadding: [2, 3]
      }),
      state: {
        normal: { strokeOpacity: 0.72, lineWidth: 1.8 },
        active: { strokeOpacity: 1, lineWidth: 3 },
        inactive: { strokeOpacity: 0.06 }
      }
    }
  }
}

function bindEvents() {
  if (!graph) return
  const moveTooltip = (event: any, avoidNode = false) => {
    const bounds = container.value?.getBoundingClientRect()
    const client = event.client || { x: 0, y: 0 }
    const width = bounds?.width || 0
    const height = bounds?.height || 0
    const localX = client.x - (bounds?.left || 0)
    const localY = client.y - (bounds?.top || 0)
    const tooltipWidth = 308
    const tooltipHeight = 216
    const margin = 12
    const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max))
    if (!avoidNode) {
      tooltip.x = clamp(localX + 16, margin, width - tooltipWidth - margin)
      tooltip.y = clamp(localY + 16, margin, height - tooltipHeight - margin)
      return
    }
    const horizontalGap = 56
    const verticalGap = 40
    if (localX + horizontalGap + tooltipWidth <= width - margin) {
      tooltip.x = localX + horizontalGap
      tooltip.y = clamp(localY - tooltipHeight / 2, margin, height - tooltipHeight - margin)
    } else if (localX - horizontalGap - tooltipWidth >= margin) {
      tooltip.x = localX - horizontalGap - tooltipWidth
      tooltip.y = clamp(localY - tooltipHeight / 2, margin, height - tooltipHeight - margin)
    } else {
      tooltip.x = clamp(localX - tooltipWidth / 2, margin, width - tooltipWidth - margin)
      tooltip.y = localY + verticalGap + tooltipHeight <= height - margin
        ? localY + verticalGap
        : clamp(localY - verticalGap - tooltipHeight, margin, height - tooltipHeight - margin)
    }
  }
  graph.on(NodeEvent.CLICK, async (event: any) => {
    const id = String(event.target.id)
    const raw = rawNodes.get(id)
    if (!raw) return
    await highlightNeighborhood(id)
    emit('selectEdge', null)
    emit('select', raw)
  })
  graph.on(NodeEvent.POINTER_MOVE, (event: any) => {
    const raw = rawNodes.get(String(event.target.id))
    if (!raw) return
    const meta = metaFor(raw)
    tooltip.visible = true
    moveTooltip(event, true)
    tooltip.title = nodeTitle(raw)
    tooltip.typeName = meta.name
    tooltip.color = meta.color
    tooltip.icon = meta.icon
    tooltip.attributes = nodeAttributes(raw)
  })
  graph.on(NodeEvent.POINTER_LEAVE, () => { tooltip.visible = false })
  graph.on(EdgeEvent.POINTER_MOVE, (event: any) => {
    const raw = rawEdges.get(String(event.target.id))
    if (!raw) return
    const label = edgeDisplayName(raw)
    tooltip.visible = true
    moveTooltip(event)
    tooltip.title = label
    tooltip.typeName = '关系'
    tooltip.color = edgeColor(label)
    tooltip.icon = '边'
    tooltip.attributes = edgeAttributes(raw)
  })
  graph.on(EdgeEvent.POINTER_LEAVE, () => { tooltip.visible = false })
  graph.on(EdgeEvent.CLICK, async (event: any) => {
    const id = String(event.target.id)
    const raw = rawEdges.get(id)
    const endpoints = edgeEndpoints.get(id)
    if (!raw || !endpoints) return
    const states: Record<string, string[]> = {}
    rawNodes.forEach((_, nodeId) => {
      states[nodeId] = nodeId === endpoints.source || nodeId === endpoints.target
        ? ['active'] : ['inactive']
    })
    edgeEndpoints.forEach((_, edgeId) => {
      states[edgeId] = edgeId === id ? ['active'] : ['inactive']
    })
    await graph?.setElementState(states, false)
    emit('select', null)
    emit('selectEdge', raw)
  })
  graph.on(CanvasEvent.CLICK, async () => {
    tooltip.visible = false
    await clearSelection()
    emit('select', null)
    emit('selectEdge', null)
  })
  graph.on(GraphEvent.AFTER_LAYOUT, async () => {
    if (!pendingLayoutFit || !graph || !props.nodes.length) return
    pendingLayoutFit = false
    await fitView(260)
  })
}

async function highlightNeighborhood(id: string) {
  if (!graph || !rawNodes.has(id)) return
  const relatedNodes = new Set<string>([id])
  const relatedEdges = new Set<string>()
  const adjacency = new Map<string, Array<{ nodeId: string; edgeId: string }>>()
  edgeEndpoints.forEach(({ source, target }, edgeId) => {
    if (!adjacency.has(source)) adjacency.set(source, [])
    if (!adjacency.has(target)) adjacency.set(target, [])
    adjacency.get(source)!.push({ nodeId: target, edgeId })
    adjacency.get(target)!.push({ nodeId: source, edgeId })
    if (source === id || target === id) {
      relatedNodes.add(source)
      relatedNodes.add(target)
      relatedEdges.add(edgeId)
    }
  })
  // 除一度邻域外，补齐选中节点到最近案件节点的最短连通路径。
  if (nodeType(rawNodes.get(id)) !== 'CASE') {
    const queue = [id]
    const visited = new Set<string>([id])
    const parent = new Map<string, { nodeId: string; edgeId: string }>()
    let nearestCaseId = ''
    while (queue.length && !nearestCaseId) {
      const current = queue.shift()!
      for (const next of adjacency.get(current) || []) {
        if (visited.has(next.nodeId)) continue
        visited.add(next.nodeId)
        parent.set(next.nodeId, { nodeId: current, edgeId: next.edgeId })
        if (nodeType(rawNodes.get(next.nodeId)) === 'CASE') {
          nearestCaseId = next.nodeId
          break
        }
        queue.push(next.nodeId)
      }
    }
    let current = nearestCaseId
    while (current && current !== id) {
      relatedNodes.add(current)
      const step = parent.get(current)
      if (!step) break
      relatedEdges.add(step.edgeId)
      relatedNodes.add(step.nodeId)
      current = step.nodeId
    }
  }
  const states: Record<string, string[]> = {}
  rawNodes.forEach((_, nodeId) => {
    states[nodeId] = nodeId === id ? ['selected', 'active'] : relatedNodes.has(nodeId) ? ['active'] : ['inactive']
  })
  edgeEndpoints.forEach((_, edgeId) => {
    states[edgeId] = relatedEdges.has(edgeId) ? ['active'] : ['inactive']
  })
  await graph.setElementState(states, false)
}

async function clearSelection() {
  if (!graph) return
  const states: Record<string, string[]> = {}
  rawNodes.forEach((_, id) => { states[id] = ['normal'] })
  edgeEndpoints.forEach((_, id) => { states[id] = ['normal'] })
  await graph.setElementState(states, false)
}

async function render() {
  const version = ++renderVersion
  await nextTick()
  if (!container.value) return
  loading.value = true
  tooltip.visible = false
  try {
    if (!props.nodes.length) {
      if (graph) {
        graph.setData({ nodes: [], edges: [] })
        await graph.draw()
      }
      return
    }
    if (!graph) {
      graph = new Graph(graphOptions() as any)
      bindEvents()
      pendingLayoutFit = true
      await graph.render()
      emit('ready')
    } else {
      graph.setOptions(graphOptions() as any)
      pendingLayoutFit = true
      await graph.render()
    }
    if (version === renderVersion && props.nodes.length) {
      await fitView(320)
    }
  } finally {
    if (version === renderVersion) loading.value = false
  }
}

async function zoom(ratio: number) {
  await graph?.zoomBy(ratio, { duration: 180 })
}
const nextFrame = () => new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
async function fitView(duration = 260) {
  if (!graph || !container.value || !props.nodes.length) return
  await nextTick()
  await nextFrame()
  await nextFrame()
  graph.resize()
  // Release the previous dynamic minimum before recomputing the fit zoom.
  graph.setZoomRange([0.01, 10])
  await graph.fitView({ when: 'always', direction: 'both' }, { duration })
  const fitZoom = graph.getZoom()
  // “适应画布”为100%正常显示比例：允许缩小到60%，最大仅放大到150%。
  graph.setZoomRange([fitZoom * 0.6, fitZoom * 1.5])
}
async function resetLayout() {
  if (!graph) return
  if (props.layout === 'grid') {
    await render()
    return
  }
  loading.value = true
  try {
    graph.setLayout(layoutOptions() as any)
    pendingLayoutFit = true
    await graph.layout()
    await fitView()
  } finally {
    loading.value = false
  }
}
async function focusNode(id: string) {
  if (!graph || !rawNodes.has(String(id))) return false
  await graph.focusElement(String(id), { duration: 320 })
  await highlightNeighborhood(String(id))
  emit('select', rawNodes.get(String(id)) || null)
  return true
}

defineExpose({ fitView, resetLayout, focusNode, clearSelection })

watch(
  [
    () => props.nodes,
    () => props.edges,
    () => props.typeMeta,
    () => props.layout,
    () => props.highlightedIds.map(String).join('\u0000')
  ],
  render
)
onMounted(() => {
  render()
  if (container.value) {
    resizeObserver = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width || 0
      graph?.resize()
      if (lastObservedWidth < 2 && width >= 2) void fitView(0)
      lastObservedWidth = width
    })
    resizeObserver.observe(container.value)
  }
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  graph?.destroy()
  graph = null
})
</script>

<style scoped>
.g6-shell{position:relative;min-height:420px;overflow:hidden;border:1px solid #dce6f2;border-radius:12px;background:radial-gradient(circle at 50% 45%,#fff 0,#f7faff 58%,#eef4fb 100%);box-shadow:inset 0 1px 0 rgba(255,255,255,.9)}
.g6-canvas{width:100%;height:100%}
.g6-tools{position:absolute;z-index:6;right:14px;top:14px;display:flex;gap:6px;padding:5px;border:1px solid #d9e4f0;border-radius:10px;background:rgba(255,255,255,.92);box-shadow:0 5px 18px rgba(35,56,84,.12);backdrop-filter:blur(8px)}
.g6-tools button{width:32px;height:32px;border:0;border-radius:7px;background:transparent;color:#344861;font-size:16px;font-weight:700;cursor:pointer}
.g6-tools button:hover{background:#eaf3ff;color:#1677ff}
.g6-guide{position:absolute;z-index:4;left:14px;bottom:12px;padding:5px 9px;border-radius:7px;background:rgba(255,255,255,.78);color:#7b8da5;font-size:11px;pointer-events:none}
.g6-tooltip{position:absolute;z-index:10;width:280px;max-height:190px;overflow:hidden;padding:12px 13px;border:1px solid rgba(133,157,187,.25);border-radius:10px;background:rgba(19,31,49,.96);box-shadow:0 12px 32px rgba(15,23,42,.25);color:#f7fbff;pointer-events:none;backdrop-filter:blur(8px)}
.g6-tooltip b{display:flex;align-items:center;gap:7px;color:#b9cae0;font-size:11px;font-weight:500}
.g6-tooltip b i{display:inline-flex;width:22px;height:22px;align-items:center;justify-content:center;border-radius:50%;color:#fff;font-size:11px;font-style:normal;font-weight:700}
.g6-tooltip strong{display:block;margin:7px 0 8px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}
.g6-tooltip span{display:grid;grid-template-columns:82px minmax(0,1fr);gap:7px;margin-top:4px;color:#e5edf7;font-size:11px}
.g6-tooltip em{overflow:hidden;color:#8ea4bf;font-style:normal;text-overflow:ellipsis;white-space:nowrap}
.g6-tooltip span:not(em){word-break:break-all}
.g6-loading,.g6-empty{position:absolute;inset:0;z-index:5;display:flex;align-items:center;justify-content:center;gap:9px;background:rgba(248,251,255,.72);color:#7b8da5;font-size:13px;pointer-events:none}
.g6-loading span{width:16px;height:16px;border:2px solid #c8d9ee;border-top-color:#1677ff;border-radius:50%;animation:spin .75s linear infinite}
.tooltip-enter-active,.tooltip-leave-active{transition:opacity .12s ease}
.tooltip-enter-from,.tooltip-leave-to{opacity:0}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
