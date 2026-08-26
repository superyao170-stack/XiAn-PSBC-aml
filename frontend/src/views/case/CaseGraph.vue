<template>
  <div><div class="page-header"><h2>案例图谱</h2><el-button @click="loadCases">刷新</el-button></div>
    <el-card><el-select v-model="caseId" filterable clearable placeholder="选择案例" style="width:420px" @change="loadGraph">
      <el-option v-for="c in cases" :key="c.caseId" :label="`${c.caseId} · ${c.patternName || c.scenarioCode || ''}`" :value="c.caseId" />
    </el-select>
    <div v-if="graph" class="summary">TuGraph / {{ graph.database }}：{{ graph.nodes?.length || 0 }} 个节点，{{ graph.edges?.length || 0 }} 条关系</div>
    <el-table v-if="graph" :data="graph.nodes" border style="margin-top:16px"><el-table-column prop="id" label="节点ID" /><el-table-column prop="labels" label="标签" /><el-table-column prop="properties" label="属性" /></el-table>
    <el-table v-if="graph" :data="graph.edges" border style="margin-top:16px"><el-table-column prop="type" label="关系" /><el-table-column prop="source" label="起点" /><el-table-column prop="target" label="终点" /></el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getCasesApi, getCaseGraphApi } from '@/api/case'
const cases=ref<any[]>([]), caseId=ref(''), graph=ref<any>(null)
const loadCases=async()=>{const r:any=await getCasesApi({pageNum:1,pageSize:200});cases.value=r.data?.records||[]}
const loadGraph=async()=>{if(!caseId.value){graph.value=null;return};const r:any=await getCaseGraphApi(caseId.value);graph.value=r.data}
onMounted(loadCases)
</script>
<style scoped>.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.summary{margin-top:16px;color:#606266}</style>
