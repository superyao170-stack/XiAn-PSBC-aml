<template>
  <div class="menu-page">
    <div class="page-header"><h2>菜单管理</h2><el-button type="primary" @click="open()">新增菜单</el-button></div>
    <el-card v-loading="loading">
      <el-table :data="menus" row-key="id" default-expand-all class="adaptive-list-table" table-layout="fixed">
        <el-table-column prop="menuName" label="菜单名称" min-width="180" />
        <el-table-column prop="path" label="路径" min-width="190" show-overflow-tooltip />
        <el-table-column prop="permission" label="权限标识" min-width="190" show-overflow-tooltip />
        <el-table-column label="类型" width="90"><template #default="{row}">{{ typeText(row.type) }}</template></el-table-column>
        <el-table-column label="显示" width="74"><template #default="{row}"><el-tag :type="row.visible?'success':'info'">{{ row.visible?'是':'否' }}</el-tag></template></el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="70" />
        <el-table-column label="操作" width="210" fixed="right" align="right" header-align="right" class-name="operation-column">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="open(row)">编辑</el-button>
              <el-button v-if="row.type!=='BUTTON'" link @click="open(undefined, row.id)">添加子菜单</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑菜单' : '新增菜单'" width="580px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="上级菜单" prop="parentId">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            node-key="id"
            :props="{label:'menuName',children:'children'}"
            check-strictly
            default-expand-all
          />
        </el-form-item>
        <el-form-item label="菜单名称" prop="menuName"><el-input v-model="form.menuName" maxlength="64" /></el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type">
            <el-option label="目录" value="DIRECTORY" />
            <el-option label="菜单" value="MENU" />
            <el-option label="按钮" value="BUTTON" />
          </el-select>
        </el-form-item>
        <el-form-item label="菜单路径" prop="path"><el-input v-model="form.path" :placeholder="form.type==='BUTTON'?'按钮可留空':'例如 /system/menus'" /></el-form-item>
        <el-form-item label="组件路径"><el-input v-model="form.component" placeholder="例如 views/system/Menus.vue" /></el-form-item>
        <el-form-item label="权限标识"><el-input v-model="form.permission" placeholder="例如 system:menu:view" /></el-form-item>
        <el-form-item label="图标"><el-input v-model="form.icon" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="9999" /></el-form-item>
        <el-form-item label="是否显示"><el-switch v-model="form.visible" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createMenuApi, deleteMenuApi, getMenusApi, updateMenuApi } from '@/api/system'
import type { SysMenu } from '@/types'

type MenuForm = Omit<SysMenu, 'children' | 'id'> & { id: number | null }

const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const formRef = ref<FormInstance>()
const flatMenus = ref<SysMenu[]>([])
const menus = computed(() => buildTree(flatMenus.value))
const form = reactive<MenuForm>(emptyForm())
const rules: FormRules<MenuForm> = {
  menuName: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择菜单类型', trigger: 'change' }],
  path: [{ validator: (_rule, value, callback) => {
    if (form.type !== 'BUTTON' && !String(value || '').trim()) callback(new Error('目录和菜单必须填写路径'))
    else if (value && !String(value).startsWith('/')) callback(new Error('菜单路径必须以 / 开头'))
    else callback()
  }, trigger: 'blur' }]
}

const parentOptions = computed(() => {
  const blocked = new Set<number>()
  if (form.id != null) collectDescendants(form.id, flatMenus.value, blocked)
  if (form.id != null) blocked.add(form.id)
  const candidates: SysMenu[] = [rootOption(), ...flatMenus.value.filter(item => item.type !== 'BUTTON' && !blocked.has(item.id))]
  return buildTree(candidates)
})

function emptyForm(): MenuForm {
  return { id: null, parentId: 0, menuName: '', path: '', component: '', icon: '', sortOrder: 0, type: 'MENU', permission: '', visible: true }
}

function rootOption(): SysMenu {
  return { ...emptyForm(), id: 0, menuName: '根目录' } as SysMenu
}

function buildTree(rows: SysMenu[]) {
  const map = new Map<number, SysMenu>(rows.map(item => [item.id, { ...item, children: [] }]))
  const roots: SysMenu[] = []
  map.forEach(item => {
    const parent = map.get(item.parentId)
    if (parent && item.id !== 0) parent.children!.push(item)
    else roots.push(item)
  })
  const sort = (items: SysMenu[]) => items
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id)
    .map(item => {
      if (item.children?.length) item.children = sort(item.children)
      else delete item.children
      return item
    })
  return sort(roots)
}

function collectDescendants(id: number, rows: SysMenu[], result: Set<number>) {
  rows.filter(item => item.parentId === id).forEach(item => {
    if (result.has(item.id)) return
    result.add(item.id)
    collectDescendants(item.id, rows, result)
  })
}

async function load() {
  loading.value = true
  try {
    const response: any = await getMenusApi()
    flatMenus.value = response.data || []
  } finally {
    loading.value = false
  }
}

function open(row?: SysMenu, parentId = 0) {
  Object.assign(form, emptyForm(), row ? { ...row, children: undefined } : { parentId })
  visible.value = true
}

async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    const { id, ...data } = form
    if (id) await updateMenuApi(id, data)
    else await createMenuApi(data)
    visible.value = false
    ElMessage.success(id ? '菜单修改成功' : '菜单新增成功')
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: SysMenu) {
  const hasChildren = flatMenus.value.some(item => item.parentId === row.id)
  try {
    await ElMessageBox.confirm(
      hasChildren ? '该菜单包含子菜单，删除后所有子菜单也会一并删除。是否继续？' : `确认删除菜单“${row.menuName}”？`,
      '删除菜单',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  await deleteMenuApi(row.id)
  ElMessage.success('菜单删除成功')
  await load()
}

const typeText = (type: string) => ({ DIRECTORY: '目录', MENU: '菜单', BUTTON: '按钮' } as Record<string, string>)[type] || type
onMounted(load)
</script>

<style scoped>
.menu-page { height: 100%; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-header h2 { margin: 0; }
.table-actions { display: flex; justify-content: flex-end; }
.el-tree-select, .el-select { width: 100%; }
</style>
