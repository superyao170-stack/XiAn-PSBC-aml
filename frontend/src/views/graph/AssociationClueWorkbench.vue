<template>
  <div class="workbench-page">
    <div class="page-header">
      <h2>关联线索分析</h2>
      <div><el-button @click="router.push({path:'/graph/visualize',query:route.query})">全景图谱</el-button><el-button type="primary" @click="router.push({path:'/graph/hidden-risk',query:route.query})">继续隐蔽风险挖掘</el-button></div>
    </div>
    <el-tabs v-model="activeTab" class="task-tabs">
      <el-tab-pane label="跨案直接关联" name="DIRECT_ASSOCIATION" lazy>
        <ClueAnalysis embedded />
      </el-tab-pane>
      <el-tab-pane v-for="item in tasks" :key="item.type" :label="item.title" :name="item.type" lazy>
        <Task23ScopeTaskPanel :run-type="item.type" :title="item.title" :sources="item.sources" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ClueAnalysis from './ClueAnalysis.vue'
import Task23ScopeTaskPanel from '@/components/graph/Task23ScopeTaskPanel.vue'
const activeTab = ref('DIRECT_ASSOCIATION')
const route=useRoute(),router=useRouter()
const tasks = [
  { type: 'EVENT_CHAIN', title: '事件链与主题链簇', sources: ['银行事件图谱', '事理说明'] },
  { type: 'BEHAVIOR_MATRIX', title: '行为伴生矩阵', sources: ['银行事件图谱'] },
  { type: 'RISK_DIFFUSION', title: '风险传导与干预', sources: ['银行事件图谱', '银行事理图谱'] },
  { type: 'INCREMENTAL_LEARNING', title: '事理模式增量学习', sources: ['银行事件图谱', '行为模式'] },
]
</script>

<style scoped>
.workbench-page{padding:12px}.page-header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:6px}.page-header h2{margin:0;color:#17233d}.task-tabs{margin-top:4px}
</style>
