
<template>
  <div class="case-review">
    <div class="page-header">
      <h2>案例复核</h2>
    </div>
    <el-card>
      <div class="search-form">
        <el-form :inline="true" :model="searchForm">
          <el-form-item label="案例ID">
            <el-input v-model="searchForm.caseId" placeholder="请输入案例ID" />
          </el-form-item>
          <el-form-item label="风险等级">
            <el-select v-model="searchForm.riskLevel" placeholder="请选择等级">
              <el-option label="低风险" value="LOW" />
              <el-option label="中风险" value="MEDIUM" />
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
        <el-table-column prop="createdAt" label="创建时间" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="82" fixed="right" align="right" header-align="right" class-name="operation-column">
          <template #default="{ row }">
            <div class="table-actions"><el-button size="small" @click="openReviewModal(row)">复核</el-button></div>
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
    <el-dialog v-model="modalVisible" title="案例复核" width="700px">
      <div class="review-content">
        <div class="review-header">
          <div class="case-info">
            <div class="case-name">{{ selectedCase.caseName }}</div>
            <div class="case-id">{{ selectedCase.caseId }}</div>
          </div>
          <div class="case-tags">
            <el-tag :type="getRiskTagType(selectedCase.riskLevel)">{{ getRiskText(selectedCase.riskLevel) }}</el-tag>
          </div>
        </div>
        <div class="review-section">
          <h4>复核内容</h4>
          <div class="review-items">
            <div class="review-item" v-for="(item, index) in reviewItems" :key="index">
              <div class="item-header">
                <span class="item-title">{{ item.title }}</span>
                <el-tag :type="item.result === 'PASS' ? 'success' : item.result === 'WARN' ? 'warning' : 'danger'">
                  {{ item.result === 'PASS' ? '通过' : item.result === 'WARN' ? '警告' : '失败' }}
                </el-tag>
              </div>
              <p class="item-desc">{{ item.description }}</p>
            </div>
          </div>
        </div>
        <div class="review-opinion">
          <h4>复核意见</h4>
          <el-form :model="reviewForm">
            <el-form-item label="复核结果" prop="reviewResult">
              <el-radio-group v-model="reviewForm.reviewResult">
                <el-radio value="PASS">通过</el-radio>
                <el-radio value="REJECT">驳回</el-radio>
                <el-radio value="ESCALATE">升级</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="复核意见" prop="reviewOpinion">
              <el-input v-model="reviewForm.reviewOpinion" type="textarea" rows="4" placeholder="请输入复核意见" />
            </el-form-item>
          </el-form>
        </div>
      </div>
      <template #footer>
        <el-button @click="modalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">提交复核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CfRiskCase } from '@/types'
import { getCasesApi, reviewCaseApi } from '@/api/case'
import { dateTimeCell } from '@/utils/datetime'

const searchForm = reactive({ caseId: '', riskLevel: '' })
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const cases = ref<CfRiskCase[]>([])

const modalVisible = ref(false)
const selectedCase = ref<any>({ caseId: '', caseName: '', riskLevel: '' })

const reviewItems = ref<any[]>([])

const reviewForm = reactive({
  reviewResult: '',
  reviewOpinion: ''
})

const getRiskText = (level: string) => {
  const map: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '严重风险' }
  return map[level] || level
}

const getRiskTagType = (level: string) => {
  const map: Record<string, string> = { LOW: 'info', MEDIUM: 'warning', HIGH: 'danger', CRITICAL: 'danger' }
  return map[level] || 'info'
}

const openReviewModal = (row: any) => {
  selectedCase.value = row
  reviewItems.value = [
    { title: '案例状态', result: row.caseStatus === 'IN_REVIEW' ? 'PASS' : 'FAIL', description: `当前状态：${row.caseStatus || '-'}` },
    { title: '风险评分', result: row.riskScore == null ? 'WARN' : 'PASS', description: `风险等级 ${row.riskLevel || '-'}，评分 ${row.riskScore ?? '未提供'}` },
    { title: '交易事实', result: (row.transactionCount || 0) > 0 ? 'PASS' : 'WARN', description: `交易 ${row.transactionCount || 0} 笔，金额 ${row.totalAmount || 0}` },
    { title: '图谱快照', result: row.graphSnapshotId ? 'PASS' : 'WARN', description: row.graphSnapshotId ? `快照 ${row.graphSnapshotId}` : '尚未生成图谱快照' }
  ]
  reviewForm.reviewResult = ''
  reviewForm.reviewOpinion = ''
  modalVisible.value = true
}

const loadCases = async () => {
  const response: any = await getCasesApi({
    pageNum: currentPage.value, pageSize: pageSize.value,
    caseId: searchForm.caseId || undefined, caseStatus: 'IN_REVIEW', riskLevel: searchForm.riskLevel || undefined
  })
  cases.value = response.data?.records || []
  total.value = response.data?.total || 0
}
const handleSearch = loadCases
const resetForm = () => { Object.assign(searchForm, { caseId: '', riskLevel: '' }); loadCases() }

const handleSubmit = async () => {
  if (!reviewForm.reviewResult) {
    ElMessage.error('请选择复核结果')
    return
  }
  await reviewCaseApi(selectedCase.value.caseId, {
    reviewer: localStorage.getItem('nickname') || localStorage.getItem('username') || 'reviewer',
    reviewResult: reviewForm.reviewResult === 'REJECT' ? 'REJECTED' : 'PASSED',
    reviewOpinion: reviewForm.reviewOpinion
  })
  modalVisible.value = false
  ElMessage.success('复核提交成功')
  await loadCases()
}

watch([currentPage, pageSize], loadCases)
onMounted(loadCases)
</script>

<style scoped>
.case-review {
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

.review-header {
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

.review-section {
  margin-bottom: 20px;
}

.review-section h4 {
  margin-bottom: 12px;
}

.review-items {
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.review-item {
  padding: 15px;
  border-bottom: 1px solid #ebeef5;
}

.review-item:last-child {
  border-bottom: none;
}

.item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.item-title {
  font-weight: bold;
}

.item-desc {
  font-size: 14px;
  color: #606266;
}

.review-opinion {
  margin-top: 20px;
}

.review-opinion h4 {
  margin-bottom: 12px;
}
</style>
