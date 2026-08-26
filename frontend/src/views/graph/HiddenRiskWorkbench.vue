<template>
  <div class="workbench-page">
    <div class="page-header">
      <h2>隐蔽风险挖掘</h2>
      <div><el-button @click="router.push({path:'/graph/visualize',query:route.query})">全景图谱</el-button><el-button @click="router.push({path:'/graph/association-clues',query:route.query})">返回关联线索分析</el-button></div>
    </div>
    <el-tabs v-model="activeTab" class="task-tabs">
      <el-tab-pane v-for="item in tasks" :key="item.type" :label="item.title" :name="item.type" lazy>
        <Task23ScopeTaskPanel :run-type="item.type" :title="item.title" :sources="item.sources" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Task23ScopeTaskPanel from '@/components/graph/Task23ScopeTaskPanel.vue'
const activeTab = ref('META_PATH_DETECTION')
const route=useRoute(),router=useRouter()
const tasks = [
  { type: 'META_PATH_DETECTION', title: '元路径偏离检测', sources: ['银行事件图谱', '银行事理图谱'] },
  { type: 'TEMPORAL_ANOMALY', title: '多尺度时序异常', sources: ['银行事件图谱'] },
  { type: 'LOCAL_HYPERGRAPH', title: '本地超图推理', sources: ['银行事理图谱', '事理说明'] },
  { type: 'CASCADE_INFERENCE', title: '多模块级联推理', sources: ['同范围上游运行', '推理证据'] },
]
</script>

<style scoped>
.workbench-page{padding:12px}.page-header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:6px}.page-header h2{margin:0;color:#17233d}.task-tabs{margin-top:4px}
</style>
