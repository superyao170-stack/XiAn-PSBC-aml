
<template>
  <div class="case-approval">
    <div class="page-header">
      <h2>案例审批</h2>
    </div>
    <el-card>
      <div class="search-form">
        <el-form :inline="true" :model="searchForm">
          <el-form-item label="案例ID">
            <el-input v-model="searchForm.caseId" placeholder="请输入案例ID" />
          </el-form-item>
          <el-form-item label="风险等级">
            <el-select v-model="searchForm.riskLevel" placeholder="请选择等级">
              <el-option label="高风险" value="HIGH" />
              <el-option label="严重风险" value="CRITICAL" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="resetForm">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      <el-table :data="cases" border class="adaptive-list-table" table-layout="fixed">
        <el-table-column prop="caseId" label="案例ID" width="150" />
        <el-table-column prop="caseName" label="案例名称" />
        <el-table-column prop="riskLevel" label="风险等级">
          <template #default="{ row }">
            <el-tag :type="getRiskTagType(row.riskLevel)">{{ getRiskText(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bankCode" label="银行" />
        <el-table-column prop="totalAmount" label="涉及金额" />
        <el-table-column prop="reviewer" label="复核人" />
        <el-table-column prop="createdAt" label="提交时间" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="82" fixed="right" align="right" header-align="right" class-name="operation-column">
          <template #default="{ row }">
            <div class="table-actions"><el-button size="small" @click="openApprovalModal(row)">审批</el-button></div>
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
    <el-dialog v-model="modalVisible" title="案例审批" width="700px">
      <div class="approval-content">
        <div class="approval-header">
          <div class="case-info">
            <div class="case-name">{{ selectedCase.caseName }}</div>
            <div class="case-id">{{ selectedCase.caseId }}</div>
          </div>
          <div class="case-tags">
            <el-tag :type="getRiskTagType(selectedCase.riskLevel)">{{ getRiskText(selectedCase.riskLevel) }}</el-tag>
          </div>
        </div>
        <div class="approval-section">
          <h4>审批流程</h4>
          <div class="process-steps">
            <div class="process-step completed">
              <el-icon><CircleCheck /></el-icon>
              <span>案例创建</span>
            </div>
            <div class="process-step completed">
              <el-icon><CircleCheck /></el-icon>
              <span>案例复核</span>
            </div>
            <div class="process-step current">
              <el-icon><Clock /></el-icon>
              <span>案例审批</span>
            </div>
          </div>
        </div>
        <div class="approval-section">
          <h4>复核意见</h4>
          <div class="review-opinion">
            <div class="opinion-header">
              <span class="reviewer">{{ selectedCase.reviewer }}</span>
              <span class="review-result">复核通过</span>
            </div>
            <p class="opinion-content">{{ reviewOpinion || '复核流程未填写意见' }}</p>
          </div>
        </div>
        <div class="approval-form">
          <h4>审批意见</h4>
          <el-form :model="approvalForm">
            <el-form-item label="审批结果" prop="approvalResult">
              <el-radio-group v-model="approvalForm.approvalResult">
                <el-radio value="APPROVED">通过</el-radio>
                <el-radio value="REJECTED">驳回</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="审批意见" prop="approvalOpinion">
              <el-input v-model="approvalForm.approvalOpinion" type="textarea" rows="4" placeholder="请输入审批意见" />
            </el-form-item>
          </el-form>
        </div>
      </div>
      <template #footer>
        <el-button @click="modalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { CircleCheck, Clock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { CfRiskCase } from '@/types'
import { approveCaseApi, getCasesApi, getCaseWorkflowApi } from '@/api/case'
import { dateTimeCell } from '@/utils/datetime'

const searchForm = reactive({ caseId: '', riskLevel: '' })
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const reviewOpinion = ref('')
const cases = ref<CfRiskCase[]>([])

const modalVisible = ref(false)
const selectedCase = ref<any>({ caseId: '', caseName: '', riskLevel: '', reviewer: '' })

const approvalForm = reactive({
  approvalResult: '',
  approvalOpinion: ''
})

const getRiskText = (level: string) => {
  const map: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '严重风险' }
  return map[level] || level
}

const getRiskTagType = (level: string) => {
  const map: Record<string, string> = { LOW: 'info', MEDIUM: 'warning', HIGH: 'danger', CRITICAL: 'danger' }
  return map[level] || 'info'
}

const openApprovalModal = async (row: any) => {
  selectedCase.value = row
  const workflow:any = await getCaseWorkflowApi(row.caseId)
  reviewOpinion.value = (workflow.data || []).find((item:any) => item.stepName === 'REVIEW')?.opinion || ''
  approvalForm.approvalResult = ''
  approvalForm.approvalOpinion = ''
  modalVisible.value = true
}

const loadCases = async () => {
  const response: any = await getCasesApi({
    pageNum: currentPage.value, pageSize: pageSize.value,
    caseId: searchForm.caseId || undefined, caseStatus: 'PENDING_APPROVAL', riskLevel: searchForm.riskLevel || undefined
  })
  cases.value = response.data?.records || []
  total.value = response.data?.total || 0
}
const handleSearch = loadCases
const resetForm = () => { Object.assign(searchForm, { caseId: '', riskLevel: '' }); loadCases() }

const handleSubmit = async () => {
  if (!approvalForm.approvalResult) {
    ElMessage.error('请选择审批结果')
    return
  }
  await approveCaseApi(selectedCase.value.caseId, {
    approver: localStorage.getItem('nickname') || localStorage.getItem('username') || 'approver',
    approvalResult: approvalForm.approvalResult,
    approvalOpinion: approvalForm.approvalOpinion
  })
  modalVisible.value = false
  ElMessage.success('审批提交成功')
  await loadCases()
}

watch([currentPage, pageSize], loadCases)
onMounted(loadCases)
</script>

<style scoped>
.case-approval {
  height: 100%;
}

.page-header {
  margin-bottom: 20px;
}

.search-form {
  margin-bottom: 20px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}

.approval-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.case-name {
  font-size: 20px;
  font-weight: bold;
}

.case-id {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.approval-section {
  margin-bottom: 20px;
}

.approval-section h4 {
  margin-bottom: 12px;
}

.process-steps {
  display: flex;
  justify-content: space-around;
}

.process-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 15px 20px;
  border-radius: 8px;
}

.process-step.completed {
  background: #f0f9eb;
  color: #67c23a;
}

.process-step.current {
  background: #ecf5ff;
  color: #409eff;
}

.review-opinion {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 15px;
}

.opinion-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.reviewer {
  font-weight: bold;
}

.review-result {
  color: #67c23a;
  font-weight: bold;
}

.opinion-content {
  color: #606266;
  font-size: 14px;
}

.approval-form {
  margin-top: 20px;
}

.approval-form h4 {
  margin-bottom: 12px;
}
</style>
