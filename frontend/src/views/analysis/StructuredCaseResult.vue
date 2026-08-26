<template>
  <div v-loading="loading" class="result-redirect">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCasesApi } from '@/api/case'

const route=useRoute(),router=useRouter()
const loading=ref(true),errorText=ref('')

onMounted(async()=>{
  const jobId=String(route.params.jobId||'')
  try{
    const response:any=await getCasesApi({pageNum:1,pageSize:200,jobId})
    const first=response.data?.records?.[0]
    if(!first?.caseId){
      errorText.value='该任务尚未生成可查看的案例'
      return
    }
    await router.replace({path:`/case/detail/${encodeURIComponent(first.caseId)}`,query:{jobId}})
  }catch(error:any){
    errorText.value=error?.message||'结构化案例结果加载失败'
  }finally{loading.value=false}
})
</script>

<style scoped>
.result-redirect{min-height:420px}
</style>
