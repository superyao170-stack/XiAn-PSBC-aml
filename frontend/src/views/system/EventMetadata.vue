<template>
  <div class="event-metadata-page">
    <div class="page-header">
      <div>
        <h2>事件元数据管理</h2>
        <p>{{ metadata.description || '统一维护事件种类、识别规则与业务分类。' }}</p>
      </div>
      <div class="header-actions">
        <el-tag type="info">版本 {{ metadata.version || '—' }}</el-tag>
        <el-button type="primary" @click="openCreate">新增事件</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="scenarioCode" class="scenario-select" aria-label="场景筛选">
          <el-option label="反洗钱" value="AML" />
          <el-option label="反欺诈" value="ANTI_FRAUD" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="搜索事件编号、名称、分类或规则" />
        <el-select v-model="category" clearable placeholder="全部分类">
          <el-option v-for="item in categories" :key="item" :label="item" :value="item" />
        </el-select>
        <span>共 {{ filteredRows.length }} 种事件</span>
      </div>
      <el-table v-loading="loading" :data="pagedRows" border table-layout="fixed" empty-text="暂无事件元数据">
        <el-table-column prop="id" label="事件编号" width="130" />
        <el-table-column prop="name" label="事件种类" min-width="200" />
        <el-table-column prop="category" label="业务分类" width="150" />
        <el-table-column prop="rule" label="识别规则" min-width="440" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right" align="right" header-align="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pagination"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        :total="filteredRows.length"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑事件定义' : '新增事件定义'" width="720px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="所属场景"><el-tag>{{ scenarioText }}</el-tag></el-form-item>
        <el-form-item label="事件编号" prop="id"><el-input v-model="form.id" maxlength="64" /></el-form-item>
        <el-form-item label="事件名称" prop="name"><el-input v-model="form.name" maxlength="120" /></el-form-item>
        <el-form-item label="业务分类" prop="category"><el-input v-model="form.category" maxlength="120" /></el-form-item>
        <el-form-item label="识别规则" prop="rule"><el-input v-model="form.rule" type="textarea" :rows="8" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并写回知识库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createEventMetadataApi,
  deleteEventMetadataApi,
  getEventMetadataApi,
  updateEventMetadataApi,
  type EventMetadataDefinition
} from '@/api/system'

type ScenarioCode = 'AML' | 'ANTI_FRAUD'

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const category = ref('')
const scenarioCode = ref<ScenarioCode>('AML')
const page = ref(1)
const pageSize = ref(20)
const rows = ref<EventMetadataDefinition[]>([])
const metadata = ref<Record<string, string>>({})
const dialogVisible = ref(false)
const editingId = ref('')
const formRef = ref<FormInstance>()
const form = reactive<EventMetadataDefinition>({ id: '', name: '', category: '', rule: '' })
const rules: FormRules<EventMetadataDefinition> = {
  id: [
    { required: true, message: '请输入事件编号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, message: '只能包含字母、数字、点、下划线和短横线', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请输入事件名称', trigger: 'blur' }],
  category: [{ required: true, message: '请输入业务分类', trigger: 'blur' }],
  rule: [{ required: true, message: '请输入识别规则', trigger: 'blur' }]
}

const scenarioText = computed(() => scenarioCode.value === 'AML' ? '反洗钱' : '反欺诈')
const categories = computed(() => [...new Set(rows.value.map(item => item.category).filter(Boolean))].sort())
const filteredRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return rows.value.filter(item => {
    if (category.value && item.category !== category.value) return false
    if (!query) return true
    return [item.id, item.name, item.category, item.rule]
      .some(value => String(value || '').toLowerCase().includes(query))
  })
})
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

watch([keyword, category, pageSize], () => { page.value = 1 })
watch(scenarioCode, async () => {
  keyword.value = ''
  category.value = ''
  page.value = 1
  await load()
})

async function load() {
  loading.value = true
  try {
    const response: any = await getEventMetadataApi(scenarioCode.value)
    metadata.value = response.data?.metadata || {}
    rows.value = response.data?.eventTypes || []
  } catch (error: any) {
    rows.value = []
    metadata.value = {}
    ElMessage.error(error?.message || '事件元数据加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = ''
  Object.assign(form, { id: '', name: '', category: category.value || '', rule: '' })
  dialogVisible.value = true
}

function openEdit(row: EventMetadataDefinition) {
  editingId.value = row.id
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    const payload = {
      id: form.id.trim(), name: form.name.trim(), category: form.category.trim(), rule: form.rule.trim()
    }
    if (editingId.value) await updateEventMetadataApi(scenarioCode.value, editingId.value, payload)
    else await createEventMetadataApi(scenarioCode.value, payload)
    dialogVisible.value = false
    ElMessage.success(`已写回${scenarioText.value}风险知识库`)
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: EventMetadataDefinition) {
  try {
    await ElMessageBox.confirm(
      `确定从${scenarioText.value}风险知识库删除“${row.name}（${row.id}）”吗？`,
      '删除事件定义',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  await deleteEventMetadataApi(scenarioCode.value, row.id)
  ElMessage.success(`已从${scenarioText.value}风险知识库删除`)
  if (pagedRows.value.length === 1 && page.value > 1) page.value -= 1
  await load()
}

onMounted(load)
</script>

<style scoped>
.event-metadata-page { height: 100%; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.page-header h2 { margin: 0 0 8px; }
.page-header p { margin: 0; color: #64748b; line-height: 1.6; }
.header-actions { display: flex; flex: none; align-items: center; gap: 12px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.toolbar .el-input { width: 340px; }
.toolbar .el-select { width: 180px; }
.toolbar .scenario-select { width: 130px; }
.toolbar span { margin-left: auto; color: #64748b; }
.table-actions { display: flex; justify-content: flex-end; }
.pagination { justify-content: flex-end; margin-top: 16px; }
</style>
