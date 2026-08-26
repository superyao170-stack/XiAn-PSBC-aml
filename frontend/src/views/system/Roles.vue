
<template>
  <div class="roles-page">
    <div class="page-header">
      <h2>角色管理</h2>
      <el-button type="primary" @click="openAddModal">
        <el-icon><Plus /></el-icon>新增角色
      </el-button>
    </div>
    <el-card>
      <el-table :data="roles" border class="adaptive-list-table" table-layout="fixed">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="roleCode" label="角色编码" />
        <el-table-column prop="roleName" label="角色名称" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="关联用户" width="100">
          <template #default="{ row }">{{ userCount(row.roleCode) }}</template>
        </el-table-column>
        <el-table-column prop="menus" label="业务菜单" min-width="220">
          <template #default="{ row }"><el-tag v-for="menu in row.menus" :key="menu" size="small" type="success">{{ menuLabel(menu) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="permissions" label="权限" width="300">
          <template #default="{ row }">
            <el-tag v-for="p in row.permissions" :key="p" size="small">{{ p }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="116" fixed="right" align="right" header-align="right" class-name="operation-column">
          <template #default="{ row }">
            <div class="table-actions"><el-button size="small" @click="openEditModal(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="deleteRole(row)">删除</el-button></div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="modalVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" placeholder="请输入角色编码" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" placeholder="请输入角色描述" />
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="form.permissions" multiple placeholder="请选择权限">
            <el-option label="系统管理" value="system:*" />
            <el-option label="数据接入" value="data:*" />
            <el-option label="案例管理" value="case:*" />
            <el-option label="线索分析" value="graph:*" />
            <el-option label="监管协同" value="regulator:*" />
            <el-option label="策略配置" value="policy:*" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务菜单">
          <el-select v-model="form.menus" multiple placeholder="请选择登录后可见业务域">
            <el-option v-for="menu in rootMenus" :key="menu.value" :label="menu.label" :value="menu.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { SysRole } from '@/types'
import { createRoleApi, deleteRoleApi, getMenusApi, getRolesApi, getUsersApi, updateRoleApi } from '@/api/system'

const roles = ref<SysRole[]>([])
const users = ref<any[]>([])
const rootMenus = ref<{label:string,value:string}[]>([])

const modalVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const form = reactive({
  id: null,
  roleCode: '',
  roleName: '',
  description: '',
  permissions: [] as string[],
  menus: [] as string[]
})

const rules = {
  roleCode: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}

const openAddModal = () => {
  isEdit.value = false
  form.id = null
  form.roleCode = ''
  form.roleName = ''
  form.description = ''
  form.permissions = []
  form.menus = []
  modalVisible.value = true
}

const openEditModal = (row: any) => {
  isEdit.value = true
  form.id = row.id
  form.roleCode = row.roleCode
  form.roleName = row.roleName
  form.description = row.description
  form.permissions = row.permissions
  form.menus = row.menus || []
  modalVisible.value = true
}

const loadRoles = async () => {
  const [roleResponse, userResponse, menuResponse]: any = await Promise.all([
    getRolesApi(), getUsersApi({pageNum:1,pageSize:1000}), getMenusApi()
  ])
  roles.value = roleResponse.data || []
  users.value = userResponse.data?.records || []
  rootMenus.value = (menuResponse.data || []).filter((menu:any)=>menu.parentId===0).map((menu:any)=>({
    label:menu.menuName,value:menu.path.replace(/^\//,'')
  }))
}
const userCount=(roleCode:string)=>users.value.filter(user=>user.roleCode===roleCode).length
const menuLabel=(value:string)=>rootMenus.value.find(menu=>menu.value===value)?.label||value

const deleteRole = async (row: SysRole) => {
  if (userCount(row.roleCode)>0) {
    ElMessage.warning(`该角色仍关联 ${userCount(row.roleCode)} 个用户，请先调整用户角色`)
    return
  }
  await deleteRoleApi(row.id)
  ElMessage.success('角色删除成功')
  await loadRoles()
}

const handleSubmit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (isEdit.value && form.id) await updateRoleApi(form.id, form as any)
  else await createRoleApi(form as any)
  modalVisible.value = false
  ElMessage.success(isEdit.value ? '角色编辑成功' : '角色新增成功')
  await loadRoles()
}

onMounted(loadRoles)
</script>

<style scoped>
.roles-page {
  height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
