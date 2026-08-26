<template>
  <div>
    <div class="page-header"><h2>菜单管理</h2><el-button type="primary" @click="open()">新增菜单</el-button></div>
    <el-card v-loading="loading">
      <el-table :data="menus" row-key="id" default-expand-all class="adaptive-list-table" table-layout="fixed">
        <el-table-column prop="menuName" label="菜单名称" />
        <el-table-column prop="path" label="路径" />
        <el-table-column prop="permission" label="权限标识" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column label="操作" width="196" fixed="right" align="right" header-align="right" class-name="operation-column">
          <template #default="{ row }">
            <div class="table-actions"><el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button link @click="open(undefined, row.id)">添加子菜单</el-button>
            <el-button link type="danger" @click="remove(row.id)">删除</el-button></div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="visible" :title="form.id ? '编辑菜单' : '新增菜单'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="菜单名称" prop="menuName"><el-input v-model="form.menuName" /></el-form-item>
        <el-form-item label="菜单路径" prop="path"><el-input v-model="form.path" /></el-form-item>
        <el-form-item label="组件路径"><el-input v-model="form.component" /></el-form-item>
        <el-form-item label="图标"><el-input v-model="form.icon" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="菜单" value="MENU" /><el-option label="按钮" value="BUTTON" /></el-select></el-form-item>
        <el-form-item label="权限标识"><el-input v-model="form.permission" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createMenuApi, deleteMenuApi, getMenusApi, updateMenuApi } from '@/api/system'
const loading=ref(false), visible=ref(false), formRef=ref()
const menus=ref<any[]>([])
const form=reactive<any>({id:null,parentId:0,menuName:'',path:'',component:'',icon:'',sortOrder:0,type:'MENU',permission:''})
const rules={menuName:[{required:true,message:'请输入菜单名称'}],path:[{required:true,message:'请输入菜单路径'}]}
function tree(rows:any[]){const map=new Map(rows.map(x=>[x.id,{...x,children:[]}])) as Map<any,any>; const roots:any[]=[]; map.forEach(x=>{const p=map.get(x.parentId); p?p.children.push(x):roots.push(x)}); return roots}
async function load(){loading.value=true;try{const r:any=await getMenusApi();menus.value=tree(r.data||[])}finally{loading.value=false}}
function open(row?:any,parentId=0){Object.assign(form,{id:null,parentId,menuName:'',path:'',component:'',icon:'',sortOrder:0,type:'MENU',permission:''},row||{});delete form.children;visible.value=true}
async function save(){await formRef.value?.validate();const data={...form};delete data.id;if(form.id)await updateMenuApi(form.id,data);else await createMenuApi(data);visible.value=false;ElMessage.success('保存成功');load()}
async function remove(id:number){await ElMessageBox.confirm('确认删除该菜单？','提示');await deleteMenuApi(id);ElMessage.success('删除成功');load()}
onMounted(load)
</script>
<style scoped>.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}</style>
