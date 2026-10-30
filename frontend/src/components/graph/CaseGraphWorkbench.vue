<template>
  <section ref="workspace" class="case-graph-workspace" :class="{ 'detail-collapsed': detailCollapsed }">
    <header class="case-graph-toolbar">
      <div class="graph-scope"><strong>实体关系</strong><span>{{ nodes.length }} 个节点</span><span>{{ edges.length }} 条关系</span></div>
      <el-autocomplete v-model="searchText" :fetch-suggestions="searchEntities" :prefix-icon="Search" placeholder="搜索本案实体" aria-label="搜索本案实体" clearable highlight-first-item @select="searchSelect">
        <template #default="{ item }"><div class="case-search-result"><i :style="{ background: typeMeta[graphNodeType(item.node)]?.color }"></i><span>{{ item.value }}</span></div></template>
      </el-autocomplete>
      <div class="graph-actions"><button :class="{ active: focusOnly }" :aria-pressed="focusOnly" :disabled="!selectedNode" @click="focusOnly = !focusOnly">只看选中路径</button><button title="重新布局" aria-label="重新布局" @click="canvas?.resetLayout()"><el-icon><Refresh /></el-icon></button><button title="适应画布" aria-label="适应画布" @click="canvas?.fitView()"><el-icon><Aim /></el-icon></button><button title="全屏图谱" aria-label="全屏图谱" @click="toggleFullscreen"><el-icon><FullScreen /></el-icon></button><el-button type="primary" size="small" :disabled="!nodes.length" @click="canvas?.exportImage()">导出图谱</el-button></div>
    </header>
    <div class="case-graph-legend"><slot name="legend" /></div>
    <div class="case-graph-body">
      <PanoramaGraphCanvas ref="canvas" :nodes="displayedNodes" :edges="displayedEdges" :type-meta="typeMeta" :selected-id="selectedId" :focused-edge-key="selectedEdge ? graphEdgeKey(selectedEdge) : ''" :clustered="false" show-relation-labels @select="selectNode" @select-edge="selectEdge" />
      <aside class="case-node-inspector">
        <header class="case-inspector-heading"><strong>{{ selectedEdge ? '关系详情' : '实体详情' }}</strong><button :title="detailCollapsed ? '展开实体详情' : '收起实体详情'" :aria-label="detailCollapsed ? '展开实体详情' : '收起实体详情'" @click="detailCollapsed = !detailCollapsed"><el-icon><component :is="detailCollapsed ? ArrowLeft : ArrowRight" /></el-icon></button></header>
        <div v-if="!detailCollapsed" ref="inspectorContent" class="case-inspector-content">
          <template v-if="selectedNode || selectedEdge">
            <div class="case-selected-detail"><slot name="details" /></div>
            <template v-if="selectedNode">
              <div class="case-section-heading"><strong>直接关联</strong><span>{{ relationships.length }} 条</span></div>
              <button v-for="item in relationships" :key="item.key" class="case-relation-item" :class="{ active: selectedEdge && graphEdgeKey(selectedEdge) === item.key }" @click="selectEdge(item.edge)"><span class="case-relation-icon"><el-icon><Connection /></el-icon></span><span><b :title="item.name">{{ item.name }}</b><small>{{ graphEdgeName(item.edge) }}</small></span><el-icon><ArrowRight /></el-icon></button>
              <div v-if="pathNodes.length > 1" class="case-key-path"><strong>关键关系路径</strong><div><template v-for="(node, index) in pathNodes" :key="graphNodeId(node)"><el-icon v-if="index"><ArrowRight /></el-icon><button :title="graphNodeName(node)" @click="canvas?.focusNode(graphNodeId(node))">{{ graphNodeName(node) }}</button></template></div></div>
            </template>
          </template>
          <el-empty v-else description="选择一个实体" :image-size="48" />
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Aim, ArrowLeft, ArrowRight, Connection, FullScreen, Refresh, Search } from '@element-plus/icons-vue'
import PanoramaGraphCanvas from './PanoramaGraphCanvas.vue'
import { graphEdgeKey, graphEdgeName, graphNeighborhood, graphNodeId, graphNodeName, graphNodeType } from '@/utils/panoramaGraph'
const props = defineProps<{ nodes: any[]; edges: any[]; typeMeta: Record<string, { name: string; color: string; icon: string }>; selectedNode?: any; selectedEdge?: any }>()
const emit = defineEmits<{ select: [node: any | null]; selectEdge: [edge: any] }>()
const canvas = ref<InstanceType<typeof PanoramaGraphCanvas>>(), workspace = ref<HTMLElement>(), inspectorContent = ref<HTMLElement>()
const detailCollapsed = ref(true), focusOnly = ref(false), searchText = ref('')
const selectedId = computed(() => props.selectedNode ? graphNodeId(props.selectedNode) : '')
const nodeMap = computed(() => new Map(props.nodes.map(node => [graphNodeId(node), node])))
const neighborhood = computed(() => graphNeighborhood(selectedId.value, props.nodes, props.edges))
const displayedNodes = computed(() => focusOnly.value && selectedId.value ? props.nodes.filter(node => neighborhood.value.nodeIds.has(graphNodeId(node))) : props.nodes)
const displayedEdges = computed(() => { const ids = new Set(displayedNodes.value.map(graphNodeId)); return props.edges.filter(edge => ids.has(String(edge.source)) && ids.has(String(edge.target))) })
const relationships = computed(() => props.edges.filter(edge => String(edge.source) === selectedId.value || String(edge.target) === selectedId.value).map(edge => { const other = String(edge.source) === selectedId.value ? String(edge.target) : String(edge.source); return { edge, key: graphEdgeKey(edge), name: graphNodeName(nodeMap.value.get(other) || {}) } }))
const pathNodes = computed(() => neighborhood.value.path.map(id => nodeMap.value.get(id)).filter(Boolean))
function resetInspectorScroll() { nextTick(() => { if (inspectorContent.value) inspectorContent.value.scrollTop = 0 }) }
function selectNode(node: any | null) { emit('select', node); detailCollapsed.value = !node; if (!node) focusOnly.value = false; resetInspectorScroll() }
function selectEdge(edge: any) { emit('selectEdge', edge); detailCollapsed.value = false; resetInspectorScroll() }
function searchEntities(query: string, callback: (items: any[]) => void) { const keyword = query.trim().toLowerCase(); callback(props.nodes.filter(node => !keyword || graphNodeName(node).toLowerCase().includes(keyword)).sort((a, b) => Number(graphNodeName(b).toLowerCase() === keyword) - Number(graphNodeName(a).toLowerCase() === keyword)).slice(0, 30).map(node => ({ node, value: graphNodeName(node) }))) }
async function searchSelect(item: any) { focusOnly.value = false; await nextTick(); await canvas.value?.focusNode(graphNodeId(item.node)) }
async function toggleFullscreen() { try { if (document.fullscreenElement) await document.exitFullscreen(); else await workspace.value?.requestFullscreen() } catch { ElMessage.info('当前浏览器不支持全屏，可使用浏览器全屏功能') } }
watch(selectedId, id => { if (!id) { focusOnly.value = false; detailCollapsed.value = true } })
watch(() => props.nodes.map(graphNodeId).join('|'), () => { if (selectedId.value && !nodeMap.value.has(selectedId.value)) selectNode(null) })
defineExpose({ fitView: () => canvas.value?.fitView(), focusNode: (id: string) => canvas.value?.focusNode(id) })
</script>

<style scoped>
.case-graph-workspace{height:100%;min-height:0;display:flex;flex-direction:column;border:1px solid #e0e7ef;border-radius:11px;background:#fff;overflow:hidden;color:#263d57}.case-graph-toolbar{display:flex;align-items:center;gap:12px;min-height:44px;padding:0 12px;border-bottom:1px solid #e9edf3;flex:none}.graph-scope{display:flex;gap:7px;align-items:center;white-space:nowrap}.graph-scope strong{font-size:13px}.graph-scope>span{font-size:11px;color:#7d8fa4;background:#f3f6f9;padding:3px 8px;border-radius:12px}.case-graph-toolbar :deep(.el-autocomplete){width:210px;min-width:110px}.case-graph-toolbar :deep(.el-input__wrapper){min-height:30px;font-size:12px}.graph-actions{display:flex;gap:6px;align-items:center;margin-left:auto}.graph-actions>button:not(.el-button){height:28px;min-width:28px;padding:0 7px;border:1px solid #e2e9f2;border-radius:5px;background:white;color:#73859b;display:flex;align-items:center;justify-content:center;font-size:11px;white-space:nowrap;cursor:pointer}.graph-actions>button:not(.el-button).active{color:#3478e5;background:#f0f6ff;border-color:#d9e7ff}.graph-actions>button:not(.el-button):disabled{opacity:.4;cursor:not-allowed}.graph-actions :deep(.el-button){margin:0;height:28px;font-size:12px}.case-graph-legend{padding:5px 11px;border-bottom:1px solid #edf1f5;flex:none}.case-graph-body{min-height:0;flex:1;display:grid;grid-template-columns:minmax(0,1fr) 264px}.detail-collapsed .case-graph-body{grid-template-columns:minmax(0,1fr) 38px}.case-node-inspector{min-width:0;min-height:0;border-left:1px solid #e5ebf2;display:flex;flex-direction:column;background:white}.case-inspector-heading{height:34px;padding:0 12px;border-bottom:1px solid #edf1f5;display:flex;align-items:center;flex:none}.case-inspector-heading strong{font-size:12px}.case-inspector-heading button{width:24px;height:24px;display:grid;place-items:center;margin-left:auto;border:1px solid #e2e9f2;border-radius:5px;background:white;color:#7d8fa4;cursor:pointer}.detail-collapsed .case-inspector-heading{height:100%;padding:10px 0;flex-direction:column-reverse;justify-content:flex-end;gap:12px;border-bottom:0}.detail-collapsed .case-inspector-heading button{margin:0}.detail-collapsed .case-inspector-heading strong{writing-mode:vertical-rl;letter-spacing:1px;font-size:11px}.case-inspector-content{flex:1;min-height:0;overflow:auto;padding:11px 12px 14px}.case-selected-detail{border:1px solid #e3eaf2;border-radius:9px;background:#fcfdff;padding:10px}.case-section-heading{display:flex;align-items:center;margin:15px 0 4px;font-size:12px}.case-section-heading>span{margin-left:auto;font-size:11px;color:#91a0b3}.case-relation-item{width:100%;display:flex;align-items:center;gap:8px;padding:9px 0;border:0;border-bottom:1px solid #edf1f6;background:white;text-align:left;cursor:pointer;color:#7d8fa4}.case-relation-item:hover,.case-relation-item.active{background:#f5f9ff}.case-relation-icon{width:25px;height:25px;display:grid;place-items:center;background:#eff5ff;border-radius:7px;color:#3478e5;flex:none}.case-relation-item>span:nth-child(2){min-width:0;flex:1}.case-relation-item b{display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:11px;color:#455e7a}.case-relation-item small{display:block;font-size:10px;color:#8c9bb0;margin-top:3px}.case-relation-item>.el-icon{font-size:10px}.case-key-path{margin-top:14px;padding:10px;border-radius:7px;background:#f6f8fc}.case-key-path>strong{display:block;margin-bottom:8px;font-size:11px;color:#7488a1}.case-key-path>div{display:flex;flex-wrap:wrap;gap:5px;align-items:center;color:#8c9cb0}.case-key-path button{max-width:90px;padding:4px 6px;border:1px solid #e3e9f1;border-radius:5px;background:white;color:#7388a2;font-size:10px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;cursor:pointer}.case-graph-workspace:fullscreen{height:100vh;width:100vw;border-radius:0}.case-graph-workspace button:focus-visible{outline:2px solid #3478e5;outline-offset:2px}@media(max-width:1200px){.case-graph-body{grid-template-columns:minmax(0,1fr) 240px}.case-graph-toolbar{gap:8px}.graph-scope strong{display:none}.case-graph-toolbar :deep(.el-autocomplete){width:170px}}@media(max-width:800px){.case-graph-toolbar{flex-wrap:wrap;padding:6px 8px;gap:6px}.graph-scope>span{font-size:10px;padding:3px 5px}.case-graph-body{grid-template-columns:minmax(0,1fr) 220px}.graph-actions{gap:4px}.case-graph-toolbar :deep(.el-autocomplete){width:160px}}
</style>
