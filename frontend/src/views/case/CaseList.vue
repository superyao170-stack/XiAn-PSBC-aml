
<template>
  <div class="case-list">
    <div class="page-header">
      <h2>案例列表</h2>
    </div>
    <el-card>
      <div class="search-form">
        <el-form class="compact-search-form" :model="searchForm">
          <div class="search-fields">
            <el-form-item label="案例ID">
              <el-input v-model="searchForm.caseId" clearable placeholder="案例ID" />
            </el-form-item>
            <el-form-item label="案例场景">
              <el-select v-model="searchForm.scenarioCode" clearable placeholder="全部场景">
                <el-option v-for="item in scenarioOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="案例状态">
              <el-select v-model="searchForm.caseStatus" clearable placeholder="全部状态">
                <el-option label="待生成报告" value="PENDING_REPORT" />
                <el-option label="待框架抽取" value="PENDING_EXTRACTION" />
                <el-option label="待相似匹配" value="PENDING_SIMILARITY" />
                <el-option label="待复核审批" value="PENDING_APPROVAL" />
                <el-option label="已审核通过" value="APPROVED" />
              </el-select>
            </el-form-item>
            <el-form-item label="案例来源"><el-select v-model="searchForm.recognitionMode" clearable placeholder="全部来源"><el-option label="历史案例" value="HISTORICAL"/><el-option label="新增案例" value="NEW"/></el-select></el-form-item>
            <el-form-item label="风险等级">
              <el-select v-model="searchForm.riskLevel" clearable placeholder="全部等级">
                <el-option label="低风险" value="LOW" />
                <el-option label="中风险" value="MEDIUM" />
                <el-option label="高风险" value="HIGH" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item class="search-actions">
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="resetForm">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      <el-alert v-if="jobIdFilter" title="当前仅显示所选识别任务生成的案例" type="info" show-icon closable @close="clearJobFilter" />
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="load-error" />
      <div class="scope-actions">
        <span>已选择 {{ selectedCases.length }} 个案例</span>
        <small v-if="selectedCases.length === 1">跨案分析还需至少选择一个案例</small>
      </div>
      <el-table v-loading="loading" :data="cases" table-layout="fixed" :fit="true" class="case-table" @selection-change="selectedCases = $event">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="id" label="案例ID" min-width="74" show-overflow-tooltip />
        <el-table-column prop="caseName" label="案例名称" min-width="90" show-overflow-tooltip />
        <el-table-column label="案例场景" min-width="100">
          <template #default="{ row }">
            <el-tag :type="scenarioTagType(row.scenarioCode)">{{ scenarioText(row.scenarioCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="案例来源" min-width="90"><template #default="{row}">{{ row.recognitionMode==='HISTORICAL'?'历史案例':'新增案例' }}</template></el-table-column>
        <el-table-column prop="bankCode" label="所属银行" min-width="96" show-overflow-tooltip />
        <el-table-column prop="riskLevel" label="风险程度" min-width="82" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag :type="getRiskTagType(row.riskLevel)">{{ getRiskText(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="案例状态" min-width="92" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="two-line" :title="`${getStatusText(row.caseStatus)}\n${formatDateTime(row.createdAt)}`"><el-tag size="small" :type="getStatusTagType(row.caseStatus)">{{ getStatusText(row.caseStatus) }}</el-tag><span class="created-at">{{ formatDateTime(row.createdAt) }}</span></div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right" header-align="right" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
            <el-button link size="small" type="primary" @click="openCase(row,'overview')">详情</el-button>
            <el-button link size="small" type="danger" :loading="deletingCaseId===row.caseId" @click="deleteCase(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { CfRiskCase } from '@/types'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteCaseApi, getCasesApi } from '@/api/case'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute(), router = useRouter()
const jobIdFilter = ref(String(route.query.jobId || ''))

const searchForm = reactive({
  caseId: '',
  scenarioCode: '',
  caseStatus: '',
  riskLevel: '',
  recognitionMode: ''
})

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const cases = ref<CfRiskCase[]>([])
const selectedCases = ref<CfRiskCase[]>([])
const loading = ref(false)
const deletingCaseId = ref('')
const loadError = ref('')
const scenarioOptions = ref<{label:string;value:string}[]>([
  { label: '反洗钱', value: 'AML' },
  { label: '反欺诈', value: 'ANTI_FRAUD' }
])
const getRiskText = (level: string) => {
  const map: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' }
  return map[level] || '未定级'
}

const getRiskTagType = (level: string) => {
  const map: Record<string, string> = { LOW: 'info', MEDIUM: 'warning', HIGH: 'danger' }
  return map[level] || 'info'
}

const getStatusText = (status: string) => {
  const map: Record<string, string> = { PENDING_REPORT:'待生成报告',PENDING_EXTRACTION:'待框架抽取',PENDING_SIMILARITY:'待相似匹配',PENDING_APPROVAL:'待复核审批',APPROVED:'已审核通过',FAILED:'处理失败' }
  return map[status] || status
}

const getStatusTagType = (status: string) => {
  const map: Record<string, string> = { PENDING_REPORT:'info',PENDING_EXTRACTION:'warning',PENDING_SIMILARITY:'primary',PENDING_APPROVAL:'danger',APPROVED:'success',FAILED:'danger' }
  return map[status] || 'info'
}
const scenarioText = (value:string) => scenarioOptions.value.find(item=>item.value===value)?.label || value || '未设置'
const scenarioTagType = (value:string) => value === 'ANTI_FRAUD' ? 'danger' : value === 'AML' ? 'primary' : 'info'
const openCase = (row:CfRiskCase,tab:'overview'|'matters'|'graph') =>
  router.push({path:`/case/detail/${encodeURIComponent(row.caseId)}`,query:{tab}})
const deleteCase = async (row:CfRiskCase) => {
  try {
    await ElMessageBox.confirm(
      `确定删除案例“${row.caseName || row.caseId}”吗？案例将从列表及全景图谱中移除。`,
      '删除案例',
      { type:'warning', confirmButtonText:'删除', cancelButtonText:'取消', confirmButtonClass:'el-button--danger' }
    )
  } catch { return }
  deletingCaseId.value = row.caseId
  try {
    await deleteCaseApi(row.caseId)
    ElMessage.success('案例已删除')
    if (cases.value.length === 1 && currentPage.value > 1) currentPage.value -= 1
    else await loadCases()
  } finally { deletingCaseId.value = '' }
}

const loadCases = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const response: any = await getCasesApi({
      pageNum: currentPage.value, pageSize: pageSize.value, caseId: searchForm.caseId || undefined,
      scenarioCode: searchForm.scenarioCode || undefined,
      caseStatus: searchForm.caseStatus || undefined, riskLevel: searchForm.riskLevel || undefined,
      recognitionMode:searchForm.recognitionMode||undefined, jobId: jobIdFilter.value || undefined
    })
    cases.value = response.data?.records || []
    selectedCases.value = []
    total.value = response.data?.total || 0
  } catch (error:any) {
    cases.value = []
    selectedCases.value = []
    total.value = 0
    loadError.value = error?.message || '案例列表加载失败，请稍后重试'
  } finally { loading.value = false }
}
const handleSearch = () => {
  currentPage.value = 1
  loadCases()
}
const resetForm = () => {
  Object.assign(searchForm, { caseId: '', scenarioCode: '', caseStatus: '', riskLevel: '', recognitionMode:'' })
  handleSearch()
}

const clearJobFilter = async () => { jobIdFilter.value=''; await router.replace('/case/list'); currentPage.value=1; await loadCases() }

watch([currentPage, pageSize], loadCases)
onMounted(loadCases)
</script>

<style scoped>
.case-list {
  height: 100%;
}
.metadata-hint { margin-top:6px; color:#909399; font-size:12px; line-height:1.4; }
.load-error { margin:10px 0; }

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.search-form {
  margin-bottom: 20px;
}
.compact-search-form { display:flex; align-items:center; justify-content:space-between; gap:20px; flex-wrap:nowrap; }
.search-fields { display:flex; align-items:center; gap:14px; min-width:0; }
.compact-search-form :deep(.el-form-item) { margin:0; }
.search-fields :deep(.el-input), .search-fields :deep(.el-select) { width:112px; }
.search-actions { margin-left:auto !important; flex:0 0 auto; }
.case-table { width:100%; }
.scope-actions{display:flex;align-items:center;gap:10px;margin:10px 0;padding:9px 12px;border:1px solid #dbeafe;border-radius:7px;background:#f8fbff}.scope-actions span{color:#334155}.scope-actions small{color:#64748b}
.case-table :deep(.el-table__cell) { padding:6px 2px; }
.case-table :deep(.cell) { padding:0 5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.table-actions { display:flex; align-items:center; justify-content:flex-end; min-width:0; }
.two-line { min-width:0; display:flex; flex-direction:column; align-items:flex-start; gap:3px; line-height:1.2; overflow:hidden; }
.created-at { display:block; max-width:100%; color:#64748b; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-size:11px; }
.case-table :deep(.el-dropdown) { margin-left:2px; vertical-align:middle; }
.product-summary{width:100%;border:0;background:transparent;text-align:left;color:#334155;cursor:pointer;padding:2px 0;display:flex;flex-direction:column;gap:3px}.product-summary:hover{color:#2563eb}.product-summary b{color:#0f766e}.product-summary small{color:#64748b}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
