<template>
  <div class="audit-page">
    <div class="page-header">
      <div>
        <h2>审计日志</h2>
        <p>追踪登录、接口操作与失败原因，便于安全审查和故障定位。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-change="load">
        <el-tab-pane label="操作日志" name="operations" />
        <el-tab-pane label="登录日志" name="logins" />
      </el-tabs>
      <div class="toolbar">
        <el-input v-model="filters.username" clearable placeholder="用户名" @keyup.enter="load" />
        <el-input v-if="activeTab === 'operations'" v-model="filters.module" clearable placeholder="模块，如 CaseController" @keyup.enter="load" />
        <el-select v-model="filters.success" clearable placeholder="全部结果" @change="load">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe table-layout="fixed" empty-text="暂无日志">
        <template v-if="activeTab === 'operations'">
          <el-table-column prop="createdAt" label="时间" width="176" />
          <el-table-column prop="username" label="用户" width="110" />
          <el-table-column prop="operationType" label="方法" width="76" />
          <el-table-column prop="operationModule" label="模块" width="180" show-overflow-tooltip />
          <el-table-column prop="operationDesc" label="操作" min-width="280" show-overflow-tooltip />
          <el-table-column prop="executionTimeMs" label="耗时(ms)" width="96" align="right" />
          <el-table-column prop="ipAddress" label="IP" width="130" />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template>
          </el-table-column>
        </template>
        <template v-else>
          <el-table-column prop="createdAt" label="时间" width="176" />
          <el-table-column prop="username" label="用户" width="120" />
          <el-table-column prop="loginType" label="方式" width="90" />
          <el-table-column prop="ipAddress" label="IP" width="140" />
          <el-table-column prop="userAgent" label="客户端" min-width="260" show-overflow-tooltip />
          <el-table-column prop="errorMessage" label="失败原因" min-width="220" show-overflow-tooltip />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template>
          </el-table-column>
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        class="pagination"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        :total="total"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getLoginAuditLogsApi, getOperationAuditLogsApi } from '@/api/system'

const activeTab = ref<'operations' | 'logins'>('operations')
const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const filters = reactive<{ username: string; module: string; success: boolean | undefined }>({ username: '', module: '', success: undefined })

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      username: filters.username || undefined,
      success: filters.success
    }
    if (activeTab.value === 'operations') params.module = filters.module || undefined
    const response: any = activeTab.value === 'operations'
      ? await getOperationAuditLogsApi(params)
      : await getLoginAuditLogsApi(params)
    rows.value = response.data?.records || []
    total.value = Number(response.data?.total || 0)
  } catch (error: any) {
    rows.value = []
    total.value = 0
    ElMessage.error(error?.message || '审计日志加载失败')
  } finally {
    loading.value = false
  }
}

function onSizeChange() {
  pageNum.value = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.audit-page { height: 100%; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.page-header h2 { margin: 0 0 8px; }
.page-header p { margin: 0; color: #64748b; line-height: 1.6; }
.toolbar { display: flex; align-items: center; gap: 12px; margin: 4px 0 16px; }
.toolbar .el-input { width: 220px; }
.toolbar .el-select { width: 140px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
</style>
