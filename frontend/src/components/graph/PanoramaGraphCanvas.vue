<template>
  <div ref="shell" class="panorama-canvas">
    <svg ref="svgElement" :viewBox="`0 0 ${size.width} ${size.height}`" aria-label="案件实体关系图谱"
      @pointerdown="startPan" @pointermove="movePointer" @pointerup="endPointer" @pointercancel="endPointer" @wheel.prevent="onWheel" @click="clearCanvas">
      <defs>
        <pattern :id="`${prefix}-dots`" width="20" height="20" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".7" fill="#dce5ef" /></pattern>
        <marker v-for="color in arrowColors" :id="`${prefix}-${color.slice(1)}`" :key="color" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M1 1 L7 4 L1 7" fill="none" :stroke="color" stroke-width="1.2" /></marker>
      </defs>
      <rect width="100%" height="100%" fill="#fbfcfe" />
      <rect width="100%" height="100%" :fill="`url(#${prefix}-dots)`" />
      <g class="graph-scene" :transform="`translate(${view.x} ${view.y}) scale(${view.scale})`">
        <g v-if="clustered">
          <g v-for="group in model.groups.filter(g => !g.collapsed)" :key="group.id">
            <rect :x="group.x" :y="group.y" :width="group.width" :height="group.height" rx="22" fill="#f1f6fd" fill-opacity=".74" stroke="#dfe8f5" stroke-dasharray="4 5" />
            <text :x="group.x + 22" :y="group.y + 28" fill="#667e9b" font-size="12" font-weight="600"><title>{{ group.title }}</title>{{ shortGraphText(group.title, Math.max(6, Math.floor((group.width - 44) / 12))) }}</text>
            <text :x="group.x + 22" :y="group.y + 47" fill="#8c9aae" font-size="10">{{ group.count }} 个实体</text>
          </g>
        </g>
        <g v-for="item in renderedEdges" :key="item.key" :opacity="edgeOpacity(item)">
          <path :d="item.path" fill="none" :stroke="isActiveEdge(item.key) ? '#3478e5' : item.meta.color" :stroke-width="isActiveEdge(item.key) ? 2.5 : 1.35" :stroke-dasharray="item.meta.dash" :marker-end="item.meta.directed ? `url(#${prefix}-${(isActiveEdge(item.key) ? '#3478e5' : item.meta.color).slice(1)})` : undefined" />
          <path :d="item.path" fill="none" stroke="transparent" stroke-width="14" class="edge-target" @pointerdown.stop @click.stop="selectEdge(item.raw)" @pointerenter="hoveredEdge = item.key" @pointerleave="hoveredEdge = ''"><title>{{ graphNodeName(item.source.raw) }} → {{ graphEdgeName(item.raw) }} → {{ graphNodeName(item.target.raw) }}</title></path>
        </g>
        <g v-for="item in edgeLabels" :key="item.key" class="edge-label" @pointerdown.stop @click.stop="selectEdge(item.raw)">
          <path v-if="item.labelOffset" :d="`M${item.curveX},${item.curveY} L${item.labelX},${item.labelY}`" fill="none" :stroke="item.meta.color" stroke-width=".8" />
          <rect :x="item.labelX - item.labelWidth / 2" :y="item.labelY - 10" :width="item.labelWidth" height="20" rx="6" fill="#fff" stroke="#dfe7f0" />
          <text :x="item.labelX" :y="item.labelY + 3" font-size="10" fill="#597594" text-anchor="middle">{{ shortGraphText(graphEdgeName(item.raw), 16) }}<title>{{ graphEdgeName(item.raw) }}</title></text>
        </g>
        <g v-for="point in points" :key="point.id" :transform="`translate(${point.x} ${point.y})`" :opacity="nodeOpacity(point.id)" class="graph-node" tabindex="0" role="button" :aria-label="graphNodeName(point.raw)" @pointerdown.stop="startNodeDrag($event, point)" @click.stop="selectNode(point.raw)" @dblclick.stop="expandCase(point.caseKey)" @keydown.enter.stop.prevent="selectNode(point.raw)" @keydown.space.stop.prevent="selectNode(point.raw)">
          <title>{{ meta(point.raw).name }} · {{ graphNodeName(point.raw) }}{{ point.collapsed ? ` · ${point.count} 个实体` : '' }}</title>
          <rect v-if="point.collapsed" x="-76" y="-30" width="152" height="68" rx="13" :fill="selectedId === point.id ? '#f0f6ff' : '#fff'" :stroke="selectedId === point.id ? '#3478e5' : '#d7e1ed'" stroke-width="1.7" />
          <g v-if="point.collapsed">
            <rect x="-62" y="-16" width="6" height="34" rx="3" fill="#253e5b" />
            <text x="-47" y="-3" fill="#253e5b" font-size="12" font-weight="600">{{ shortGraphText(graphNodeName(point.raw), 10) }}</text>
            <text x="-47" y="18" fill="#7e90a5" font-size="10">{{ point.count }} 个实体</text>
            <text x="61" y="19" fill="#3478e5" font-size="15" text-anchor="end">＋</text>
          </g>
          <template v-else>
            <circle v-if="selectedId === point.id" r="32" fill="#3478e5" fill-opacity=".1" stroke="#3478e5" stroke-dasharray="3 4" />
            <rect v-if="graphNodeType(point.raw) === 'CASE'" x="-65" y="-22" width="130" height="44" rx="12" :fill="meta(point.raw).color" stroke="#fff" stroke-width="3" />
            <rect v-else-if="['ACCOUNT', 'WALLET'].includes(graphNodeType(point.raw))" x="-27" y="-18" width="54" height="36" rx="10" :fill="meta(point.raw).color" stroke="#fff" stroke-width="3" />
            <path v-else-if="['MERCHANT', 'DEVICE', 'IPADDRESS', 'ADDRESS'].includes(graphNodeType(point.raw))" d="M0 -23 L23 0 L0 23 L-23 0 Z" :fill="meta(point.raw).color" stroke="#fff" stroke-width="3" />
            <path v-else-if="graphNodeType(point.raw) === 'EVIDENCE'" d="M0 -22 L20 -11 L20 11 L0 22 L-20 11 L-20 -11 Z" :fill="meta(point.raw).color" stroke="#fff" stroke-width="3" />
            <circle v-else r="20" :fill="meta(point.raw).color" stroke="#fff" stroke-width="3" />
            <text y="4" fill="#fff" :font-size="graphNodeType(point.raw) === 'CASE' ? 11 : 12" font-weight="600" text-anchor="middle">{{ graphNodeType(point.raw) === 'CASE' ? shortGraphText(graphNodeName(point.raw), 10) : meta(point.raw).icon }}</text>
            <g v-if="graphNodeType(point.raw) !== 'CASE' && (view.scale >= .42 || neighborhood.nodeIds.has(point.id) && selectedId)">
              <rect x="-68" y="28" width="136" height="21" rx="5" fill="#fbfcfe" fill-opacity=".97" />
              <text y="42" :fill="selectedId === point.id ? '#286cd5' : '#40566f'" font-size="12" text-anchor="middle">{{ nodeCaption(point.raw) }}</text>
            </g>
          </template>
        </g>
      </g>
    </svg>
    <div class="zoom-tools" aria-label="缩放工具"><button title="放大" aria-label="放大" @click="zoom(1.2)">＋</button><button title="缩小" aria-label="缩小" @click="zoom(1 / 1.2)">−</button><button title="适应画布" aria-label="适应画布" @click="fitView"><el-icon><Aim /></el-icon></button></div>
    <div v-if="!nodes.length" class="empty">当前场景没有可展示的图谱</div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { Aim } from '@element-plus/icons-vue'
import { graphNodeId, graphNodeType, graphCaseKey, graphNodeName, graphEdgeName, graphEdgeKey, graphRelationMeta, graphNeighborhood, layoutPanorama, shortGraphText, type PanoramaPoint } from '@/utils/panoramaGraph'
const props = withDefaults(defineProps<{ nodes: any[]; edges: any[]; typeMeta: Record<string, { name: string; color: string; icon: string }>; selectedId?: string; focusedEdgeKey?: string; clustered?: boolean; showRelationLabels?: boolean }>(), { selectedId: '', focusedEdgeKey: '', clustered: true, showRelationLabels: false })
const emit = defineEmits<{ select: [node: any | null]; selectEdge: [edge: any]; displayCount: [count: number, clusterCount: number] }>()
const shell = ref<HTMLElement>(), svgElement = ref<SVGSVGElement>()
const prefix = `panorama-${useId().replace(/[^a-zA-Z0-9]/g, '')}`
const arrowColors = ['#3478e5', '#5c82b0', '#53988b', '#8d9fb6', '#9aabbe', '#b87924']
const size = reactive({ width: 1000, height: 600 }), view = reactive({ x: 0, y: 0, scale: 1 })
const expanded = ref<string[]>([]), hoveredEdge = ref(''), overrides = ref<Record<string, { x: number; y: number }>>({})
let restoringView = false
const model = computed(() => layoutPanorama(props.nodes, props.edges, new Set(expanded.value), props.clustered))
const points = computed(() => model.value.nodes.map(point => ({ ...point, ...overrides.value[point.id] })))
const pointMap = computed(() => new Map(points.value.map(point => [point.id, point])))
const neighborhood = computed(() => props.selectedId ? graphNeighborhood(props.selectedId, props.nodes, props.edges) : { nodeIds: new Set<string>(), edgeKeys: new Set<string>(), path: [] })
const meta = (node: any) => props.typeMeta[graphNodeType(node)] || { name: '其他', color: '#718399', icon: '其' }
const nodeCaption = (node: any) => {
  const text = graphNodeName(node)
  if (['ACCOUNT', 'WALLET', 'IPADDRESS'].includes(graphNodeType(node)) && Array.from(text).length > 12) return `${text.slice(0, 4)}…${text.slice(-4)}`
  if (graphNodeType(node) === 'EVENT') return shortGraphText(text.replace(/^.*?(?:呈现|发生|出现)/, '').replace(/行为$/, ''), 10)
  return shortGraphText(text, 10)
}
const isActiveEdge = (key: string) => key === hoveredEdge.value || (props.focusedEdgeKey ? key === props.focusedEdgeKey : Boolean(props.selectedId) && neighborhood.value.edgeKeys.has(key))
const nodeOpacity = (id: string) => !props.selectedId || neighborhood.value.nodeIds.has(id) ? 1 : .45
const edgeOpacity = (item: any) => isActiveEdge(item.key) ? 1 : !props.selectedId ? .55 : .1
const pointFootprints = (p: PanoramaPoint) => {
  if (p.collapsed) return [{ x: p.x - 80, y: p.y - 32, w: 160, h: 74 }]
  if (graphNodeType(p.raw) === 'CASE') return [{ x: p.x - 70, y: p.y - 26, w: 140, h: 52 }]
  return [{ x: p.x - 33, y: p.y - 29, w: 66, h: 58 }, { x: p.x - 72, y: p.y + 25, w: 144, h: 27 }]
}
const renderedEdges = computed(() => {
  const lanes = new Map<string, number>()
  return props.edges.flatMap(raw => {
    const source = pointMap.value.get(String(raw.source)), target = pointMap.value.get(String(raw.target))
    if (!source || !target) return []
    const pair = [source.id, target.id].sort().join('|'), lane = lanes.get(pair) || 0
    lanes.set(pair, lane + 1)
    const dx = target.x - source.x, dy = target.y - source.y, distance = Math.hypot(dx, dy) || 1
    const sourceRadius = source.collapsed ? 80 : graphNodeType(source.raw) === 'CASE' ? 67 : 26
    const targetRadius = target.collapsed ? 80 : graphNodeType(target.raw) === 'CASE' ? 67 : 27
    const sx = source.x + dx / distance * Math.min(sourceRadius, distance * .2), sy = source.y + dy / distance * Math.min(sourceRadius, distance * .2)
    const tx = target.x - dx / distance * Math.min(targetRadius, distance * .2), ty = target.y - dy / distance * Math.min(targetRadius, distance * .2)
    // Try alternative curves around intervening shapes and their captions.
    const obstacles = points.value.filter(p => p.id !== source.id && p.id !== target.id && p.x >= Math.min(sx, tx) - 290 && p.x <= Math.max(sx, tx) + 290 && p.y >= Math.min(sy, ty) - 290 && p.y <= Math.max(sy, ty) + 290).flatMap(pointFootprints)
    let cx = (sx + tx) / 2, cy = (sy + ty) / 2, bestScore = Infinity
    for (const offset of [12, 60, -60, 120, -120, 190, -190, 270, -270].map(n => n + Math.sign(n) * lane * 22)) {
      const candidateX = (sx + tx) / 2 - dy / distance * offset, candidateY = (sy + ty) / 2 + dx / distance * offset
      let score = 0
      for (let step = 1; step < 20; step++) {
        const t = step / 20, x = (1 - t) ** 2 * sx + 2 * (1 - t) * t * candidateX + t ** 2 * tx, y = (1 - t) ** 2 * sy + 2 * (1 - t) * t * candidateY + t ** 2 * ty
        if (obstacles.some(b => x > b.x && x < b.x + b.w && y > b.y && y < b.y + b.h)) score++
      }
      if (score < bestScore) { bestScore = score; cx = candidateX; cy = candidateY }
      if (!score) break
    }
    const loop = source.id === target.id
    const path = loop ? `M${source.x - 10},${source.y - 20} C${source.x - 65},${source.y - 100} ${source.x + 65},${source.y - 100} ${source.x + 10},${source.y - 20}` : `M${sx},${sy} Q${cx},${cy} ${tx},${ty}`
    return [{ key: graphEdgeKey(raw), raw, source, target, path, sx, sy, tx, ty, cx, cy, loop, meta: graphRelationMeta(raw) }]
  })
})
// Greedy placement tries several points on each curve and rejects any candidate
// that touches a node/name footprint or another relation label.
const edgeLabels = computed(() => {
  const labels: any[] = []
  const boxes = points.value.flatMap(pointFootprints)
  for (const item of [...renderedEdges.value].sort((a, b) => Number(isActiveEdge(b.key)) - Number(isActiveEdge(a.key)))) {
    if (item.loop || !(isActiveEdge(item.key) || props.showRelationLabels && !props.selectedId && renderedEdges.value.length <= 24) || view.scale < .45) continue
    const labelWidth = Array.from(shortGraphText(graphEdgeName(item.raw), 16)).length * 10 + 16
    let placed = false
    for (const offset of [0, 24, -24, 48, -48, 80, -80]) {
      for (const t of [.5, .35, .65, .2, .8]) {
      const curveX = (1 - t) ** 2 * item.sx + 2 * (1 - t) * t * item.cx + t ** 2 * item.tx
      const curveY = (1 - t) ** 2 * item.sy + 2 * (1 - t) * t * item.cy + t ** 2 * item.ty
      const dx = 2 * (1 - t) * (item.cx - item.sx) + 2 * t * (item.tx - item.cx), dy = 2 * (1 - t) * (item.cy - item.sy) + 2 * t * (item.ty - item.cy), distance = Math.hypot(dx, dy) || 1
      const x = curveX - dy / distance * offset, y = curveY + dx / distance * offset
      const box = { x: x - labelWidth / 2 - 4, y: y - 14, w: labelWidth + 8, h: 28 }
      if (boxes.some(b => box.x < b.x + b.w && box.x + box.w > b.x && box.y < b.y + b.h && box.y + box.h > b.y)) continue
      boxes.push(box); labels.push({ ...item, labelX: x, labelY: y, curveX, curveY, labelOffset: offset, labelWidth }); placed = true; break
      }
      if (placed) break
    }
  }
  return labels
})
let observer: ResizeObserver | undefined
let pointer: { x: number; y: number; viewX: number; viewY: number; node?: PanoramaPoint; moved: boolean } | undefined
let skipClick = false
function startPan(event: PointerEvent) {
  if (event.button !== 0) return
  pointer = { x: event.clientX, y: event.clientY, viewX: view.x, viewY: view.y, moved: false }
  svgElement.value?.setPointerCapture(event.pointerId)
}
function startNodeDrag(event: PointerEvent, node: PanoramaPoint) {
  if (event.button !== 0) return
  pointer = { x: event.clientX, y: event.clientY, viewX: node.x, viewY: node.y, node, moved: false }
}
function movePointer(event: PointerEvent) {
  if (!pointer) return
  const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y
  if (Math.abs(dx) + Math.abs(dy) < 4 && !pointer.moved) return
  pointer.moved = true
  if (!svgElement.value?.hasPointerCapture(event.pointerId)) svgElement.value?.setPointerCapture(event.pointerId)
  if (pointer.node) overrides.value = { ...overrides.value, [pointer.node.id]: { x: pointer.viewX + dx / view.scale, y: pointer.viewY + dy / view.scale } }
  else { view.x = pointer.viewX + dx; view.y = pointer.viewY + dy }
}
function endPointer(event: PointerEvent) {
  skipClick = Boolean(pointer?.moved)
  if (svgElement.value?.hasPointerCapture(event.pointerId)) svgElement.value.releasePointerCapture(event.pointerId)
  pointer = undefined
}
async function selectNode(node: any) { if (skipClick) { skipClick = false; return }; const point = pointMap.value.get(graphNodeId(node)); if (point?.collapsed) await expandCase(point.caseKey); emit('select', node) }
function selectEdge(edge: any) { if (skipClick) { skipClick = false; return }; emit('selectEdge', edge) }
function clearCanvas() { if (skipClick) { skipClick = false; return }; emit('select', null) }
function zoom(ratio: number, x = size.width / 2, y = size.height / 2) {
  const next = Math.min(3, Math.max(.04, view.scale * ratio)), factor = next / view.scale
  view.x = x - (x - view.x) * factor; view.y = y - (y - view.y) * factor; view.scale = next
}
function onWheel(event: WheelEvent) { const rect = svgElement.value!.getBoundingClientRect(); zoom(Math.exp(-event.deltaY * .0015), event.clientX - rect.left, event.clientY - rect.top) }
function fitView() {
  const scale = Math.min((size.width - 48) / Math.max(model.value.width, 1), (size.height - 48) / Math.max(model.value.height, 1), 1.35)
  view.scale = Math.max(.04, scale); view.x = (size.width - model.value.width * scale) / 2; view.y = (size.height - model.value.height * scale) / 2
}
function focusGroup(key: string) {
  const group = model.value.groups.find(g => g.id === key)
  if (!group) return
  view.scale = Math.max(.8, Math.min((size.width - 60) / group.width, (size.height - 60) / group.height, 1.3))
  view.x = size.width / 2 - (group.x + group.width / 2) * view.scale; view.y = size.height / 2 - (group.y + group.height / 2) * view.scale
}
async function expandCase(key: string) { if (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]; await nextTick(); focusGroup(key) }
async function focusNode(id: string) {
  const node = props.nodes.find(item => graphNodeId(item) === id)
  if (!node) return false
  await expandCase(graphCaseKey(node)); emit('select', node)
  await nextTick()
  await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  focusGroup(graphCaseKey(node))
  const point = pointMap.value.get(id)
  if (point && (point.x * view.scale + view.x < 72 || point.x * view.scale + view.x > size.width - 72 || point.y * view.scale + view.y < 72 || point.y * view.scale + view.y > size.height - 72)) {
    view.x = size.width / 2 - point.x * view.scale; view.y = size.height / 2 - point.y * view.scale
  }
  return true
}
function resetLayout() { overrides.value = {}; expanded.value = []; nextTick(fitView) }
function getViewState() { return { expanded: [...expanded.value], overrides: overrides.value, viewport: { ...view } } }
async function restoreViewState(state: any) {
  restoringView = true
  expanded.value = Array.isArray(state?.expanded) ? state.expanded.filter((x: unknown) => typeof x === 'string') : []
  const safeOverrides: Record<string, { x: number; y: number }> = {}
  if (state?.overrides && typeof state.overrides === 'object') Object.entries(state.overrides).forEach(([id, point]: [string, any]) => { if (Number.isFinite(point?.x) && Number.isFinite(point?.y)) safeOverrides[id] = { x: point.x, y: point.y } })
  overrides.value = safeOverrides
  await nextTick()
  await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  if (state?.viewport && ['x', 'y', 'scale'].every(k => Number.isFinite(state.viewport[k])) && state.viewport.scale > 0) Object.assign(view, { ...state.viewport, scale: Math.max(.04, Math.min(3, state.viewport.scale)) })
  else fitView()
  restoringView = false
}
function exportSvg() {
  if (!svgElement.value) return
  const copy = svgElement.value.cloneNode(true) as SVGSVGElement
  copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  copy.setAttribute('viewBox', `0 0 ${model.value.width} ${model.value.height}`)
  copy.setAttribute('width', String(model.value.width)); copy.setAttribute('height', String(model.value.height))
  copy.querySelector('.graph-scene')?.removeAttribute('transform')
  const style = document.createElementNS('http://www.w3.org/2000/svg', 'style')
  style.textContent = 'text{font-family:Arial,"PingFang SC","Microsoft YaHei",sans-serif}.edge-target{display:none}'
  copy.prepend(style)
  const blob = new Blob([new XMLSerializer().serializeToString(copy)], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = `全景图谱-${new Date().toISOString().slice(0, 10)}.svg`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
defineExpose({ fitView, focusNode, expandCase, resetLayout, getViewState, restoreViewState, exportSvg })
watch(model, () => {
  emit('displayCount', model.value.nodes.length, model.value.groups.filter(group => group.collapsed).length)
  nextTick(() => {
    if (restoringView) return
    const selected = pointMap.value.get(props.selectedId)
    if (selected) focusGroup(selected.caseKey)
    else fitView()
  })
})
let datasetKey = ''
watch(() => props.nodes, async () => {
  const key = props.nodes.filter(node => graphNodeType(node) === 'CASE').map(graphNodeId).join('|')
  if (!key || key === datasetKey) return
  datasetKey = key
  if (props.selectedId) return
  // Open one substantive case on entry, keeping the remaining cases available
  // in the overview instead of starting with an unreadable wall of labels.
  const candidate = [...model.value.groups].sort((a, b) => b.count - a.count)[0]
  if (props.nodes.length > 120 && candidate && candidate.count > 3 && props.clustered) {
    expanded.value = [candidate.id]
    await nextTick(); await nextTick(); if (!props.selectedId && !restoringView) focusGroup(candidate.id)
  }
})
onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    const oldWidth = size.width, oldHeight = size.height
    size.width = Math.max(1, entry.contentRect.width); size.height = Math.max(1, entry.contentRect.height)
    if (oldWidth === 1000 && oldHeight === 600) fitView()
    else {
      const centerX = (oldWidth / 2 - view.x) / view.scale, centerY = (oldHeight / 2 - view.y) / view.scale
      view.x = size.width / 2 - centerX * view.scale; view.y = size.height / 2 - centerY * view.scale
    }
  })
  if (shell.value) observer.observe(shell.value)
  nextTick(fitView)
})
onBeforeUnmount(() => observer?.disconnect())
</script>
<style scoped>
.panorama-canvas{position:relative;flex:1;min-width:0;min-height:0;height:100%;overflow:hidden;background:#fbfcfe}.panorama-canvas>svg{width:100%;height:100%;display:block;touch-action:none;user-select:none;cursor:grab}.panorama-canvas>svg:active{cursor:grabbing}.graph-node{cursor:pointer;outline:none}.graph-node:focus-visible{filter:drop-shadow(0 0 5px #3478e5)}.edge-target,.edge-label{cursor:pointer}.zoom-tools{position:absolute;right:12px;top:12px;display:flex;flex-direction:column;border:1px solid #e0e7ef;border-radius:8px;background:white;box-shadow:0 2px 8px #20354d0d;overflow:hidden}.zoom-tools button{width:34px;height:34px;border:0;border-bottom:1px solid #edf1f5;background:white;color:#526c88;font-size:17px;cursor:pointer;display:grid;place-items:center}.zoom-tools button:last-child{border:0}.zoom-tools button:hover{color:#3478e5;background:#f1f6ff}.empty{position:absolute;inset:0;display:grid;place-items:center;color:#7f90a6;font-size:13px;pointer-events:none}
</style>
