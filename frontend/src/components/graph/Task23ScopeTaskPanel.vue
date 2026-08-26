<template>
  <div class="scope-task-panel">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never" class="io-card">
          <template #header><div class="card-title"><span>分析输入</span><el-tag effect="plain">{{ title }}</el-tag></div></template>
          <el-form label-position="top">
            <el-form-item label="场景范围">
              <el-select v-model="scope.scenarioCode" clearable filterable placeholder="全部场景">
                <el-option v-for="item in scenarios" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
            <el-form-item label="案件创建时间">
              <el-date-picker v-model="dateRange" type="datetimerange"
                              value-format="YYYY-MM-DDTHH:mm:ssZ"
                              start-placeholder="开始时间" end-placeholder="结束时间" />
            </el-form-item>
            <el-form-item label="案件集合">
              <el-select v-model="scope.caseIds" multiple collapse-tags collapse-tags-tooltip
                         clearable filterable placeholder="请选择至少两个案件">
                <el-option v-for="item in cases" :key="item.caseId" :value="item.caseId"
                           :label="`${item.caseName || '未命名案件'}｜${item.caseId}`" />
              </el-select>
            </el-form-item>
            <el-form-item label="最大案件数">
              <el-input-number v-model="scope.limit" :min="2" :max="200" controls-position="right" />
            </el-form-item>
            <el-divider content-position="left">运行参数</el-divider>
            <el-form-item v-for="field in parameterFields" :key="field.key" :label="field.label">
              <el-input-number v-model="parameters[field.key]" :min="field.min" :max="field.max"
                               :step="field.step" controls-position="right" />
            </el-form-item>
            <el-form-item v-if="runType === 'CASCADE_INFERENCE'" label="同范围上游运行">
              <el-select v-model="cascadeRunIds" multiple collapse-tags collapse-tags-tooltip
                         placeholder="至少选择两个不同模块的成功运行">
                <el-option v-for="item in cascadeCandidates" :key="item.runId" :value="item.runId"
                           :label="`${runTypeText(item.runType)}｜${shortId(item.runId)}`" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-alert v-if="context.scopeId && Number(context.caseCount || 0) < 2"
                    type="warning" :closable="false" show-icon
                    title="跨案分析至少需要两个案件，请补选案件或扩大分析范围。" />
          <div class="input-actions">
            <el-button :loading="loading" @click="loadContext">刷新输入</el-button>
            <el-button type="primary" :disabled="!canRun" :loading="running" @click="execute">
              运行{{ title }}
            </el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never" class="io-card">
          <template #header><div class="card-title"><span>输入快照</span><small>{{ context.scopeId ? '已加载' : '尚未加载' }}</small></div></template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="银行">{{ context.bankCode || '当前银行' }}</el-descriptions-item>
            <el-descriptions-item label="案件">{{ context.caseCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="规范事件">{{ context.eventCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="图谱节点">{{ context.graphNodeCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="图谱关系">{{ context.graphEdgeCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="事理说明">{{ context.matterCount || 0 }}</el-descriptions-item>
          </el-descriptions>
          <div class="source-line">
            <span>输入来源</span>
            <el-tag v-for="source in sources" :key="source" size="small" effect="plain">{{ source }}</el-tag>
          </div>
        </el-card>

        <el-card shadow="never" class="io-card output-card">
          <template #header>
            <div class="card-title">
              <span>分析输出</span>
              <el-tag :type="statusType(output?.status)">{{ statusText(output?.status) }}</el-tag>
            </div>
          </template>
          <el-empty v-if="!output" description="运行后在此显示输出、证据和分析资产" />
          <template v-else>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="运行ID">{{ output.runId }}</el-descriptions-item>
              <el-descriptions-item label="输入快照">{{ output.inputSnapshotId }}</el-descriptions-item>
              <el-descriptions-item v-for="item in outputStats" :key="item.key" :label="item.label">
                {{ item.value }}
              </el-descriptions-item>
            </el-descriptions>
            <h4>推理证据</h4>
            <el-table :data="detail?.evidence || []" border max-height="240" empty-text="本次运行没有阈值以上证据">
              <el-table-column prop="subject_id" label="对象" min-width="180" show-overflow-tooltip />
              <el-table-column prop="score" label="分数" width="90" />
              <el-table-column prop="decision" label="判断" width="110" />
              <el-table-column label="原因码" min-width="170">
                <template #default="{ row }">{{ (row.reason_codes || []).join?.('、') || row.reason_codes || '-' }}</template>
              </el-table-column>
            </el-table>
            <h4>分析资产</h4>
            <el-table :data="artifacts" border max-height="240" empty-text="本次运行没有输出分析资产">
              <el-table-column prop="artifactType" label="资产类型" width="180" />
              <el-table-column prop="artifactKey" label="资产键" min-width="180" show-overflow-tooltip />
              <el-table-column prop="reviewStatus" label="审核状态" width="110" />
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <template v-if="row.reviewStatus === 'PENDING'">
                    <el-button link type="success" @click="review(row, 'APPROVED')">通过</el-button>
                    <el-button link type="danger" @click="review(row, 'REJECTED')">拒绝</el-button>
                  </template>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
            <el-collapse class="raw-output">
              <el-collapse-item title="查看完整可复现输出JSON">
                <pre>{{ JSON.stringify(output.result, null, 2) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCasesApi } from '@/api/case'
import {
  createTask23ScopeRunApi, getResearchRunApi, getResearchRunArtifactsApi,
  getResearchRunsApi, getTask23ScopeContextApi, reviewResearchArtifactApi,
} from '@/api/researchAnalytics'
import { readAnalysisScope, saveAnalysisScope } from '@/utils/analysisScope'

type NumberField = { key: string; label: string; min: number; max: number; step: number }
const props = defineProps<{
  runType: string
  title: string
  sources: string[]
}>()
const route = useRoute()
const initialScope = readAnalysisScope(route.query as Record<string,unknown>)

const cases = ref<any[]>([])
const context = ref<any>({})
const output = ref<any>(null)
const detail = ref<any>(null)
const artifacts = ref<any[]>([])
const scopeRuns = ref<any[]>([])
const dateRange = ref<string[]>(initialScope.startTime && initialScope.endTime
  ? [initialScope.startTime, initialScope.endTime] : [])
const cascadeRunIds = ref<string[]>([])
const loading = ref(false)
const running = ref(false)
const scope = reactive({ scenarioCode: initialScope.scenarioCode, caseIds: initialScope.caseIds, limit: Math.max(8, initialScope.caseIds.length) })
const parameters = reactive<Record<string, number>>({})

const fieldDefinitions: Record<string, NumberField[]> = {
  EVENT_CHAIN: [
    { key: 'minConfidence', label: '最低事件置信度', min: 0, max: 1, step: 0.05 },
    { key: 'maxGapSeconds', label: '最大事件间隔（秒）', min: 60, max: 7776000, step: 3600 },
  ],
  BEHAVIOR_MATRIX: [
    { key: 'minConfidence', label: '最低事件置信度', min: 0, max: 1, step: 0.05 },
    { key: 'windowSeconds', label: '伴生窗口（秒）', min: 60, max: 2592000, step: 3600 },
  ],
  RISK_DIFFUSION: [
    { key: 'damping', label: '传播衰减系数', min: 0, max: 1, step: 0.05 },
    { key: 'maxHops', label: '最大传播跳数', min: 1, max: 8, step: 1 },
    { key: 'decisionThreshold', label: '风险判断阈值', min: 0, max: 1, step: 0.05 },
  ],
  INCREMENTAL_LEARNING: [
    { key: 'similarityThreshold', label: '模式相似度阈值', min: 0, max: 1, step: 0.05 },
    { key: 'supportDecay', label: '支持度衰减', min: 0, max: 1, step: 0.05 },
  ],
  META_PATH_DETECTION: [
    { key: 'minHops', label: '最小路径跳数', min: 1, max: 8, step: 1 },
    { key: 'maxHops', label: '最大路径跳数', min: 1, max: 8, step: 1 },
    { key: 'deviationThreshold', label: '偏离判断阈值', min: 0, max: 1, step: 0.05 },
  ],
  TEMPORAL_ANOMALY: [
    { key: 'windowSeconds', label: '时序窗口（秒）', min: 60, max: 2592000, step: 3600 },
    { key: 'burstMinEvents', label: '突发最少事件数', min: 2, max: 100, step: 1 },
    { key: 'decisionThreshold', label: '异常判断阈值', min: 0, max: 1, step: 0.05 },
  ],
  LOCAL_HYPERGRAPH: [
    { key: 'decisionThreshold', label: '超图风险阈值', min: 0, max: 1, step: 0.05 },
  ],
  CASCADE_INFERENCE: [
    { key: 'minModules', label: '最少确认模块数', min: 2, max: 3, step: 1 },
    { key: 'decisionThreshold', label: '级联判断阈值', min: 0, max: 1, step: 0.05 },
  ],
}
const defaults: Record<string, Record<string, number>> = {
  EVENT_CHAIN: { minConfidence: .35, maxGapSeconds: 2592000 },
  BEHAVIOR_MATRIX: { minConfidence: .35, windowSeconds: 604800 },
  RISK_DIFFUSION: { damping: .85, maxHops: 4, decisionThreshold: .5 },
  INCREMENTAL_LEARNING: { similarityThreshold: .8, supportDecay: .95 },
  META_PATH_DETECTION: { minHops: 2, maxHops: 5, deviationThreshold: .5 },
  TEMPORAL_ANOMALY: { windowSeconds: 3600, burstMinEvents: 3, decisionThreshold: .55 },
  LOCAL_HYPERGRAPH: { decisionThreshold: .5 },
  CASCADE_INFERENCE: { minModules: 2, decisionThreshold: .65 },
}
Object.assign(parameters, defaults[props.runType] || {})

const parameterFields = computed(() => fieldDefinitions[props.runType] || [])
const scenarios = computed(() => [...new Set(cases.value.map(item => item.scenarioCode).filter(Boolean))].sort() as string[])
const cascadeCandidates = computed(() => scopeRuns.value.filter(item =>
  item.status === 'SUCCEEDED' &&
  ['META_PATH_DETECTION', 'TEMPORAL_ANOMALY', 'LOCAL_HYPERGRAPH'].includes(item.runType)
))
const canRun = computed(() => context.value.scopeId && Number(context.value.caseCount || 0) >= 2 &&
  (props.runType !== 'CASCADE_INFERENCE' || cascadeRunIds.value.length >= 2))
const outputStats = computed(() => {
  const result = output.value?.result || {}
  const values = Object.entries(result)
    .filter(([, value]) => Array.isArray(value))
    .slice(0, 8)
    .map(([key, value]) => ({ key, label: outputLabel(key), value: (value as unknown[]).length }))
  values.unshift({ key: 'evidence', label: '证据数', value: detail.value?.evidence?.length || 0 })
  values.unshift({ key: 'artifacts', label: '资产数', value: artifacts.value.length })
  return values
})

const payload = () => ({
  scenarioCode: scope.scenarioCode || undefined,
  startTime: dateRange.value?.[0] || undefined,
  endTime: dateRange.value?.[1] || undefined,
  caseIds: scope.caseIds,
  limit: Math.max(scope.limit, scope.caseIds.length),
})
const assertSuccess = (response: any) => {
  if (response?.code !== 200) throw new Error(response?.message || '请求失败')
  return response.data
}
const loadCases = async () => {
  const response: any = await getCasesApi({ pageNum: 1, pageSize: 200 })
  cases.value = assertSuccess(response)?.records || []
}
const loadScopeRuns = async () => {
  if (!context.value.scopeId) return
  scopeRuns.value = assertSuccess(await getResearchRunsApi({
    sourceRef: context.value.scopeId, limit: 200,
  })) || []
}
const loadContext = async () => {
  loading.value = true
  try {
    context.value = assertSuccess(await getTask23ScopeContextApi(payload()))
    saveAnalysisScope({
      caseIds: context.value.resolvedCaseIds || scope.caseIds,
      scenarioCode: scope.scenarioCode,
      startTime: dateRange.value?.[0] || '',
      endTime: dateRange.value?.[1] || '',
    })
    output.value = null
    detail.value = null
    artifacts.value = []
    cascadeRunIds.value = []
    await loadScopeRuns()
  } catch (error: any) {
    ElMessage.error(error?.message || '加载分析输入失败')
  } finally {
    loading.value = false
  }
}
const execute = async () => {
  running.value = true
  try {
    const runParameters: Record<string, unknown> = { ...parameters }
    if (props.runType === 'CASCADE_INFERENCE') runParameters.upstreamRunIds = cascadeRunIds.value
    output.value = assertSuccess(await createTask23ScopeRunApi({
      ...payload(), runType: props.runType, parameters: runParameters,
    }))
    const runId = output.value.runId
    const [detailResponse, artifactResponse] = await Promise.all([
      getResearchRunApi(runId), getResearchRunArtifactsApi(runId),
    ])
    detail.value = assertSuccess(detailResponse)
    artifacts.value = assertSuccess(artifactResponse) || []
    await loadScopeRuns()
    ElMessage.success(`${props.title}运行成功`)
  } catch (error: any) {
    ElMessage.error(error?.message || `${props.title}运行失败`)
  } finally {
    running.value = false
  }
}
const review = async (row: any, status: 'APPROVED' | 'REJECTED') => {
  try {
    assertSuccess(await reviewResearchArtifactApi(row.artifactId, status))
    artifacts.value = assertSuccess(await getResearchRunArtifactsApi(output.value.runId)) || []
    ElMessage.success(status === 'APPROVED' ? '候选已通过审核' : '候选已拒绝')
  } catch (error: any) {
    ElMessage.error(error?.message || '审核失败')
  }
}
const outputLabel = (key: string) => ({
  templates: '事件链模板', behaviorPairs: '行为关系', relations: '伴生关系',
  highRiskPaths: '高风险路径', interventions: '干预建议',
  templateUpdates: '模板更新', candidates: '学习候选',
  detectedPaths: '异常元路径', hubScores: '风险枢纽',
  anomalies: '时序异常', hyperedgeScores: '超边评分',
  nodeScores: '节点评分', decisions: '级联判断',
  knowledgeCandidates: '知识候选', evidence: '结果证据',
} as Record<string, string>)[key] || key
const runTypeText = (type: string) => ({
  META_PATH_DETECTION: '元路径偏离', TEMPORAL_ANOMALY: '时序异常',
  LOCAL_HYPERGRAPH: '本地超图',
} as Record<string, string>)[type] || type
const statusText = (status?: string) => ({
  SUCCEEDED: '成功', RUNNING: '运行中', FAILED: '失败',
} as Record<string, string>)[status || ''] || '未运行'
const statusType = (status?: string) => status === 'SUCCEEDED' ? 'success' :
  status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
const shortId = (value: string) => value?.length > 18 ? `${value.slice(0, 10)}…${value.slice(-6)}` : value

onMounted(async () => {
  try {
    await loadCases()
    if (cases.value.length) await loadContext()
  } catch (error: any) {
    ElMessage.error(error?.message || '初始化分析任务失败')
  }
})
</script>

<style scoped>
.scope-task-panel{padding:0}.io-card{margin-bottom:10px}.io-card :deep(.el-card__header){padding:10px 14px}.io-card :deep(.el-card__body){padding:12px 14px}.io-card :deep(.el-form-item){margin-bottom:12px}.card-title{display:flex;align-items:center;justify-content:space-between;gap:12px}.card-title small{color:#64748b;font-family:Consolas,monospace}.el-form :deep(.el-select),.el-form :deep(.el-date-editor){width:100%}.input-actions{display:flex;justify-content:flex-end;gap:8px}.source-line{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:10px;color:#64748b}.output-card{min-height:360px}h4{margin:14px 0 6px}.raw-output{margin-top:10px}pre{max-height:320px;overflow:auto;padding:10px;background:#f8fafc;border-radius:6px;font-size:12px}@media(max-width:1000px){.el-col{max-width:100%;flex:0 0 100%}}
</style>
