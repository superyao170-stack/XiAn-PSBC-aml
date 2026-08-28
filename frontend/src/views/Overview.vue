
<template>
  <div class="overview">
    <div class="overview-header">
      <div class="overview-title">
        <h2>平台总览</h2>
        <el-radio-group v-model="sceneCode" @change="changeScene">
          <el-radio-button value="AML">反洗钱</el-radio-button>
          <el-radio-button value="ANTI_FRAUD">反欺诈</el-radio-button>
        </el-radio-group>
      </div>
      <div class="header-actions">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="loadOverview" />
        <el-button type="primary" @click="loadOverview">刷新数据</el-button>
      </div>
    </div>
    <el-card shadow="never" class="quick-entry">
      <span>开始工作</span>
      <el-button type="primary" @click="router.push('/analysis/upload')">新建案例上传</el-button>
      <el-button @click="router.push('/case/processing/report')">新增案例处理</el-button>
      <el-button @click="router.push('/case/processing/approval')">待复核审批</el-button>
      <el-button @click="router.push('/graph/visualize')">全景图谱</el-button>
    </el-card>
    <div class="stats-grid">
      <el-card class="stat-card">
        <div class="stat-icon case">
          <el-icon><FolderOpened /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ caseStats.total }}</div>
          <div class="stat-label">风险案例总数</div>
        </div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-icon review">
          <el-icon><View /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ caseStats.pendingReport }}</div>
          <div class="stat-label">待生成报告</div>
        </div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-icon approval">
          <el-icon><CircleCheck /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ caseStats.pendingExtraction }}</div>
          <div class="stat-label">待框架抽取</div>
        </div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-icon high-risk">
          <el-icon><Warning /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ caseStats.pendingSimilarity }}</div>
          <div class="stat-label">待相似匹配</div>
        </div>
      </el-card>
    </div>
    <div class="charts-grid">
      <el-card class="chart-card">
        <template #header><div><strong>案例状态分布</strong><div class="chart-note">当前日期范围内有效案例，数字表示案例数</div></div></template>
        <div ref="statusChart" class="chart"></div>
      </el-card>
      <el-card class="chart-card">
        <template #header><div><strong>风险等级分布</strong><div class="chart-note">当前日期范围内有效案例，柱顶数字表示案例数</div></div></template>
        <div ref="riskChart" class="chart"></div>
      </el-card>
    </div>
    <div class="bottom-grid">
      <el-card class="table-card">
        <template #header><strong>最近案例（当前日期范围）</strong></template>
        <el-table :data="recentCases" border height="420" class="adaptive-list-table" table-layout="fixed">
          <el-table-column label="案例ID" min-width="120" show-overflow-tooltip>
            <template #default="{row}">{{ row.caseSequenceId || row.sourceCaseNo || '待补充' }}</template>
          </el-table-column>
          <el-table-column prop="caseName" label="案例名称" min-width="200" show-overflow-tooltip />
          <el-table-column prop="riskLevel" label="风险程度" width="105">
            <template #default="{ row }">
              <el-tag :type="getRiskTagType(row.riskLevel)">{{ getRiskText(row.riskLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="caseStatus" label="状态" width="105"><template #default="{row}">{{ statusText(row.caseStatus) }}</template></el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="170" :formatter="dateTimeCell" />
          <el-table-column label="操作" width="92" fixed="right" align="right" header-align="right" class-name="operation-column">
            <template #default="{row}">
              <div class="table-actions"><el-button size="small" type="primary" link @click="router.push(`/case/detail/${row.caseId}`)">查看详情</el-button></div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import * as echarts from 'echarts'
import { FolderOpened, View, CircleCheck, Warning } from '@element-plus/icons-vue'
import { getOverviewApi } from '@/api/overview'
import { useRouter } from 'vue-router'
import { dateTimeCell } from '@/utils/datetime'

const router = useRouter()

const dateRange = ref([])
const sceneCode = ref<'AML'|'ANTI_FRAUD'>((localStorage.getItem('overviewScene') as 'AML'|'ANTI_FRAUD') || 'AML')
const sceneName = ref(sceneCode.value === 'AML' ? '反洗钱' : '反欺诈')

const caseStats = ref({
  total: 0,
  pendingReport: 0,
  pendingExtraction: 0,
  pendingSimilarity: 0
})

const recentCases = ref<any[]>([])
let statusChartInstance: echarts.ECharts | null = null
let riskChartInstance: echarts.ECharts | null = null
const statusText = (value:string) => ({
  DRAFT: '待提交复核',
  IN_REVIEW: '复核中',
  PENDING_REPORT: '待生成报告',
  PENDING_EXTRACTION: '待框架抽取',
  PENDING_SIMILARITY: '待相似匹配',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  CLOSED: '已结案',
  REOPENED: '已重开',
  FAILED: '处理失败'
} as Record<string,string>)[value] || value
const getRiskText = (value:string) => ({ LOW:'低风险', MEDIUM:'中风险', HIGH:'高风险', CRITICAL:'严重风险' } as Record<string,string>)[value] || '未定级'

const getRiskTagType = (level: string) => {
  const map: Record<string, string> = {
    LOW: 'info',
    MEDIUM: 'warning',
    HIGH: 'danger',
    CRITICAL: 'danger'
  }
  return map[level] || 'info'
}

const renderCharts = (statusData: any[], riskData: any[]) => {
  statusChartInstance ||= echarts.init(document.querySelector('.chart-card:first-child .chart') as HTMLElement)
  statusChartInstance.setOption({
    tooltip: { trigger: 'item', formatter: (p:any) => `${p.name}<br/>案例数：${p.value}<br/>占比：${p.percent}%` },
    legend: { bottom: 0, formatter: (name:string) => statusText(name) },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: statusData.map(item => ({ ...item, name: statusText(item.name) })),
      label: { show: false },
      labelLayout: { hideOverlap: true }
    }]
  })

  riskChartInstance ||= echarts.init(document.querySelector('.chart-card:last-child .chart') as HTMLElement)
  riskChartInstance.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: '5%', right: '4%', top: 54, bottom: 32, containLabel: true },
    xAxis: { type: 'category', data: ['低风险', '中风险', '高风险', '严重风险'] },
    yAxis: { type: 'value', name: '案例数', minInterval: 1 },
    series: [{
      type: 'bar',
      data: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map(level =>
        riskData.find(item => item.name === level)?.value || 0),
      label: { show: true, position: 'top', formatter: '{c}' },
      itemStyle: {
        color: (params:any) => ['#909399', '#e6a23c', '#f56c6c', '#c45656'][params.dataIndex]
      }
    }]
  })
}

const loadOverview = async () => {
  const response: any = await getOverviewApi({
    startDate: dateRange.value?.[0],
    endDate: dateRange.value?.[1],
    scenarioCode: sceneCode.value
  })
  const data = response.data || {}
  const statusDistribution = data.statusDistribution || []
  const statusCount = (status: string) =>
    Number(statusDistribution.find((item: any) => item.name === status)?.value || 0)
  caseStats.value = {
    total: data.caseCount || 0,
    pendingReport: statusCount('PENDING_REPORT'),
    pendingExtraction: statusCount('PENDING_EXTRACTION'),
    pendingSimilarity: statusCount('PENDING_SIMILARITY')
  }
  recentCases.value = data.recentCases || []
  renderCharts(statusDistribution, data.riskDistribution || [])
}

const changeScene = () => {
  sceneName.value = sceneCode.value === 'AML' ? '反洗钱' : '反欺诈'
  localStorage.setItem('overviewScene', sceneCode.value)
  loadOverview()
}

onMounted(loadOverview)
</script>

<style scoped>
.overview {
  height: 100%;
}

.overview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.overview-title { display:flex; align-items:center; gap:20px; }
.overview-title h2 { margin:0; }

.header-actions {
  display: flex;
  gap: 10px;
}
.quick-entry{margin-bottom:16px}.quick-entry :deep(.el-card__body){display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:12px 16px}.quick-entry span{margin-right:5px;color:#334155;font-weight:600}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
}

.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
  font-size: 24px;
  color: #fff;
}

.stat-icon.case { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
.stat-icon.review { background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); }
.stat-icon.approval { background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%); }
.stat-icon.high-risk { background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%); }

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card { min-height: 410px; }
.chart { height: 320px; }

.bottom-grid { min-height: 480px; }

.table-card { min-height: 470px; }
.chart-note{margin-top:4px;color:#8492a6;font-size:12px;font-weight:400}
</style>
