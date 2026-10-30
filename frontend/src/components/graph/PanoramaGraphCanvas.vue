<template>
  <div ref="shell" class="panorama-canvas">
    <G6GraphCanvas ref="canvas" :nodes="renderedNodes" :edges="renderedEdges" :type-meta="typeMeta"
      layout="grid" :height="height" @select="selectNode" @select-edge="selectEdge" @ready="applySelection" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import G6GraphCanvas from './G6GraphCanvas.vue'
import { graphCaseKey, graphEdgeKey, graphEdgeName, graphNodeId, graphNodeName, graphNodeType } from '@/utils/panoramaGraph'

// Both panorama and case snapshots share the original G6 renderer through this workbench adapter.
const props = withDefaults(defineProps<{
  nodes: any[]
  edges: any[]
  typeMeta: Record<string, { name: string; color: string; icon: string; planeName?: string }>
  selectedId?: string
  focusedEdgeKey?: string
  clustered?: boolean
  showRelationLabels?: boolean
}>(), { selectedId: '', focusedEdgeKey: '', clustered: true, showRelationLabels: false })
const emit = defineEmits<{
  select: [node: any | null]
  selectEdge: [edge: any]
  displayCount: [count: number, clusterCount: number]
}>()
const shell = ref<HTMLElement>()
const canvas = ref<InstanceType<typeof G6GraphCanvas>>()
const height = ref(600)
const expanded = ref<string[]>([])
const model = computed(() => {
  const groups = new Map<string, any[]>()
  props.nodes.forEach(node => {
    const key = graphCaseKey(node)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(node)
  })
  const expandedKeys = new Set(expanded.value)
  let clusterCount = 0
  const nodes = Array.from(groups, ([caseKey, items]) => {
    const root = items.find(node => node.visualPrimary) || items.find(node => graphNodeType(node) === 'CASE') || items[0]
    const collapsed = props.clustered && props.nodes.length > 120 && !expandedKeys.has(caseKey) && graphNodeType(root) === 'CASE'
    if (collapsed) clusterCount += 1
    return (collapsed ? [root] : items).map(raw => ({ id: graphNodeId(raw), raw, collapsed, count: items.length }))
  }).flat()
  return { nodes, clusterCount }
})
const renderedNodes = computed(() => model.value.nodes.map(point => point.collapsed
  ? { ...point.raw, visualLabel: `${graphNodeName(point.raw)}（${point.count} 个实体）` }
  : point.raw))
const renderedEdges = computed(() => {
  const ids = new Set(renderedNodes.value.map(graphNodeId))
  return props.edges.filter(edge => ids.has(String(edge.source)) && ids.has(String(edge.target)))
    .map(edge => ({ ...edge, visualLabel: graphEdgeName(edge) }))
})
let observer: ResizeObserver | undefined

async function applySelection() {
  const edge = props.focusedEdgeKey ? renderedEdges.value.find(item => graphEdgeKey(item) === props.focusedEdgeKey) : undefined
  await canvas.value?.setSelection(props.selectedId, edge)
}
async function selectNode(node: any | null) {
  emit('select', node)
  if (node && model.value.nodes.some(point => point.id === graphNodeId(node) && point.collapsed)) {
    await expandCase(graphCaseKey(node))
  }
}
function selectEdge(edge: any | null) {
  // G6 clears the previous edge before selecting a node; the workbench clears it with select(node).
  if (edge) emit('selectEdge', edge)
}
async function expandCase(key: string) {
  if (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]
  await nextTick()
  const root = props.nodes.find(node => graphCaseKey(node) === key && graphNodeId(node) === props.selectedId)
    || props.nodes.find(node => graphCaseKey(node) === key && node.visualPrimary)
    || props.nodes.find(node => graphCaseKey(node) === key)
  if (root) await canvas.value?.focusNode(graphNodeId(root))
}
async function focusNode(id: string) {
  const node = props.nodes.find(item => graphNodeId(item) === id)
  if (!node) return false
  const key = graphCaseKey(node)
  if (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]
  await nextTick()
  return await canvas.value?.focusNode(id) || false
}
async function resetLayout() {
  const selected = props.nodes.find(node => graphNodeId(node) === props.selectedId)
  expanded.value = selected ? [graphCaseKey(selected)] : []
  await nextTick()
  await canvas.value?.resetLayout()
}
function getViewState() {
  return { renderer: 'g6', expanded: [...expanded.value], graph: canvas.value?.getViewState() }
}
async function restoreViewState(state: any) {
  expanded.value = Array.isArray(state?.expanded) ? state.expanded.filter((value: unknown) => typeof value === 'string') : []
  await nextTick()
  // Saved SVG camera coordinates do not apply to G6; filters and case expansion remain usable.
  await canvas.value?.restoreViewState(state?.renderer === 'g6' ? state.graph : undefined)
  await applySelection()
}
// Retain the existing toolbar API while exporting the full G6 graph as a PNG.
const exportImage = () => canvas.value?.exportImage()

watch([() => props.selectedId, () => props.focusedEdgeKey], applySelection)
watch([() => props.selectedId, () => props.clustered], () => {
  const selected = props.nodes.find(node => graphNodeId(node) === props.selectedId)
  if (props.clustered && selected) {
    const key = graphCaseKey(selected)
    if (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]
  }
})
watch(model, value => emit('displayCount', value.nodes.length, value.clusterCount), { immediate: true })
onMounted(() => {
  observer = new ResizeObserver(entries => {
    const measured = Math.round(entries[0]?.contentRect.height || 0)
    if (measured > 0) height.value = measured
  })
  if (shell.value) observer.observe(shell.value)
})
onBeforeUnmount(() => observer?.disconnect())
defineExpose({
  fitView: () => canvas.value?.fitView(), resetLayout, focusNode, expandCase,
  getViewState, restoreViewState, exportImage
})
</script>

<style scoped>
.panorama-canvas{position:relative;flex:1;min-width:0;min-height:0;overflow:hidden;background:#f8fbff}
.panorama-canvas :deep(.g6-shell){position:absolute;inset:0;width:100%;min-height:0;border:0;border-radius:0;box-shadow:none}
</style>
