<template>
  <el-dialog
    :model-value="modelValue"
    title="核对批次名称"
    width="620px"
    :close-on-click-modal="false"
    @close="cancel"
  >
    <el-alert
      type="warning"
      :closable="false"
      title="检测到相同上传数据使用了不同批次名称，请统一名称后继续。"
    />
    <el-table :data="conflicts" size="small" border max-height="240" class="conflict-table">
      <el-table-column prop="batchNo" label="已有批次名称" min-width="220" />
      <el-table-column prop="matchScopeText" label="内容匹配" width="120" />
      <el-table-column prop="dataTypeText" label="数据类型" width="130" />
      <el-table-column prop="bankCode" label="银行" min-width="140" />
    </el-table>
    <el-form label-width="100px">
      <el-form-item label="统一名称" required>
        <el-select
          v-model="resolvedName"
          filterable
          allow-create
          default-first-option
          style="width:100%"
          placeholder="选择已有名称或输入新名称"
        >
          <el-option v-for="name in conflictNames" :key="name" :label="name" :value="name" />
        </el-select>
        <div class="hint">可选择已有名称，也可直接输入编辑；本次上传内容作为数据校验基准。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" @click="confirm">使用该名称并继续</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props=defineProps<{modelValue:boolean;conflicts:any[];initialName:string}>()
const emit=defineEmits<{
  (event:'update:modelValue',value:boolean):void
  (event:'confirm',value:string):void
  (event:'cancel'):void
}>()
const resolvedName=ref('')
const conflictNames=computed(()=>[...new Set(props.conflicts.map(item=>String(item.batchNo||'').trim()).filter(Boolean))])
watch(()=>props.modelValue,visible=>{
  if(visible)resolvedName.value=props.initialName||conflictNames.value[0]||''
})
const cancel=()=>{
  emit('update:modelValue',false)
  emit('cancel')
}
const confirm=()=>{
  const name=resolvedName.value.trim()
  if(!name)return ElMessage.warning('请选择或输入批次名称')
  emit('update:modelValue',false)
  emit('confirm',name)
}
</script>

<style scoped>
.conflict-table{margin:14px 0}.hint{margin-top:6px;color:#64748b;font-size:12px;line-height:1.5}
</style>
