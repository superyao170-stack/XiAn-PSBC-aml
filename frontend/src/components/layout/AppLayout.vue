
<template>
  <el-container class="app-container">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="sidebar">
      <div class="logo" @click="$router.push('/overview')">
        <span v-if="!isCollapse" class="logo-text">DataGraph</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        router
        background-color="#283646"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
        class="sidebar-menu"
      >
        <SidebarMenuItem
          v-for="menu in filteredMenus"
          :key="menu.id"
          :menu="menu"
          :get-icon="getIcon"
        />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="isCollapse = !isCollapse">
            <component :is="isCollapse ? Expand : Fold" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
              <router-link :to="item.path">{{ item.name }}</router-link>
            </el-breadcrumb-item>
          </el-breadcrumb>
          <el-button v-if="route.name === 'CaseDetail' || route.name === 'StructuredCaseResult'" class="breadcrumb-back case-header-button"
            size="small" type="info" @click="router.push(route.name === 'CaseDetail' ? '/case/list' : '/analysis/upload')">返回列表</el-button>
          <div v-if="route.name === 'CaseDetail' || route.name === 'StructuredCaseResult'" id="case-header-actions" class="case-header-actions"></div>
        </div>
        <div class="header-right">
          <span class="role-tag">{{ roleText }}</span>
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-icon><User /></el-icon>
              <span>{{ nickname }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getCaseApi } from '@/api/case'
import { ElMessage } from 'element-plus'
import SidebarMenuItem from '@/components/layout/SidebarMenuItem.vue'
import {
  Setting,
  FolderOpened,
  Connection,
  User,
  Fold,
  Expand
} from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const isCollapse = ref(false)
const roleCode = ref(localStorage.getItem('roleCode') || '')
const caseBreadcrumbId = ref('')

const roleText = computed(() => {
  const map: Record<string, string> = {
    sadmin: '超级管理员',
    badmin: '银行管理员',
    madmin: '监管管理员'
  }
  return map[roleCode.value] || '未知角色'
})
const nickname = computed(() => {
  const stored = localStorage.getItem('nickname') || userStore.nickname || ''
  const damaged = /[?]{2,}|\uFFFD/.test(stored)
  return damaged ? (userStore.username || localStorage.getItem('username') || roleText.value) : stored
})

const iconMap: Record<string, any> = {
  'el-icon-setting': Setting,
  'el-icon-folder-opened': FolderOpened,
  'el-icon-connection': Connection
}

const getIcon = (icon: string) => {
  return iconMap[icon] || Setting
}

const activeMenu = computed(() => {
  if (route.name === 'StructuredCaseResult') return '/analysis/upload'
  return route.path
})

const breadcrumbs = computed(() => {
  const paths = route.path.split('/').filter(p => p)
  return paths.map((path, index) => {
    const rawPath = '/' + paths.slice(0, index + 1).join('/')
    return {
      path: rawPath === '/case' || rawPath === '/case/detail' ? '/case/list' : rawPath,
      name: route.name === 'CaseDetail' && index === paths.length - 1
        ? (caseBreadcrumbId.value || '案例ID')
        : getPathName(path)
    }
  })
})

watch(
  () => [route.name, route.params.caseId],
  async ([name, caseId]) => {
    caseBreadcrumbId.value = ''
    if (name !== 'CaseDetail' || !caseId) return
    try {
      const response:any = await getCaseApi(String(caseId))
      const value = Number(response.data?.id)
      caseBreadcrumbId.value = Number.isInteger(value) && value > 0 ? String(value) : '案例ID'
    } catch {
      caseBreadcrumbId.value = '案例ID'
    }
  },
  { immediate: true }
)

const getPathName = (path: string) => {
  const map: Record<string, string> = {
    overview: '平台总览',
    analysis: '案例上传',
    upload: '案例上传',
    result: '结果详情',
    case: '案例管理',
    list: '案例列表',
    detail: '案例详情',
    processing: '新增案例处理',
    report: '可疑报告',
    framework: '框架抽取',
    similarity: '相似案例',
    approval: '复核审批',
    graph: '线索分析',
    visualize: '全景图谱',
    'hidden-risk': '隐蔽风险挖掘',
    'association-clues': '关联线索分析',
    system: '系统管理',
    roles: '角色管理',
    menus: '菜单管理',
    'case-graph': '案例图谱'
  }
  return map[path] || path
}

const filteredMenus = computed(() => {
  const menus = roleCode.value === 'sadmin'
    ? defaultMenus
    : defaultMenus.filter(menu => menu.path !== '/system')
  return menus.map(normalizeBusinessMenu).sort(compareMenus)
})

const businessMenuOrder: Record<string, number> = {
  '/overview': 1,
  '/analysis': 2,
  '/case': 3,
  '/graph': 4,
  '/system': 99,
  '/analysis/upload': 1,
  '/case/list': 1,
  '/case/processing': 2,
  '/case/processing/report': 1,
  '/case/processing/framework': 2,
  '/case/processing/similarity': 3,
  '/case/processing/approval': 4,
  '/graph/visualize': 1,
  '/graph/hidden-risk': 2,
  '/graph/association-clues': 3,
  '/system/roles': 1,
  '/system/menus': 2
}
const compareMenus = (a: any, b: any) =>
  (businessMenuOrder[a.path] ?? a.sortOrder ?? 100) -
  (businessMenuOrder[b.path] ?? b.sortOrder ?? 100)

const normalizeBusinessMenu = (menu: any): any => {
  const names: Record<string, string> = {
    '/overview': '平台总览',
    '/analysis': '案例上传',
    '/analysis/upload': '案例上传',
    '/case': '案例管理',
    '/graph': '线索分析',
    '/graph/visualize': '全景图谱',
    '/graph/hidden-risk': '隐蔽风险挖掘',
    '/graph/association-clues': '关联线索分析',
    '/system': '系统管理',
    '/system/roles': '角色管理',
    '/system/menus': '菜单管理',
    '/system/event-metadata': '事件元数据管理'
  }
  return {
    ...menu,
    menuName: names[menu.path] || menu.menuName,
    children: Array.isArray(menu.children)
      ? menu.children.map(normalizeBusinessMenu).sort(compareMenus)
      : []
  }
}

const defaultMenus = [
  { id: 1, parentId: 0, menuName: '平台总览', path: '/overview', icon: 'el-icon-setting', children: [] },
  { id: 26, parentId: 0, menuName: '案例上传', path: '/analysis/upload', icon: 'el-icon-connection', children: [] },
  { id: 11, parentId: 0, menuName: '案例管理', path: '/case', icon: 'el-icon-folder-opened', children: [
    { id: 12, parentId: 11, menuName: '案例列表', path: '/case/list', children: [] },
    { id: 13, parentId: 11, menuName: '新增案例处理', path: '/case/processing', children: [
      { id: 131, parentId: 13, menuName: '可疑报告', path: '/case/processing/report', children: [] },
      { id: 132, parentId: 13, menuName: '框架抽取', path: '/case/processing/framework', children: [] },
      { id: 133, parentId: 13, menuName: '相似案例', path: '/case/processing/similarity', children: [] },
      { id: 134, parentId: 13, menuName: '复核审批', path: '/case/processing/approval', children: [] }
    ]}
  ]},
  { id: 15, parentId: 0, menuName: '线索分析', path: '/graph', icon: 'el-icon-connection', children: [
    { id: 16, parentId: 15, menuName: '全景图谱', path: '/graph/visualize', children: [] },
    { id: 17, parentId: 15, menuName: '隐蔽风险挖掘', path: '/graph/hidden-risk', children: [] },
    { id: 18, parentId: 15, menuName: '关联线索分析', path: '/graph/association-clues', children: [] }
  ]},
  { id: 90, parentId: 0, menuName: '系统管理', path: '/system', icon: 'el-icon-setting', children: [
    { id: 91, parentId: 90, menuName: '角色管理', path: '/system/roles', children: [] },
    { id: 92, parentId: 90, menuName: '菜单管理', path: '/system/menus', children: [] },
    { id: 93, parentId: 90, menuName: '事件元数据管理', path: '/system/event-metadata', children: [] }
  ]}
]

const handleCommand = (command: string) => {
  if (command === 'logout') {
    userStore.logout()
    ElMessage.success('退出成功')
    router.push('/login')
  }
}

onMounted(() => {
  if (!userStore.token && localStorage.getItem('token')) {
    userStore.token = localStorage.getItem('token') || ''
  }
})
</script>

<style scoped>
.app-container {
  height: 100vh;
}

.sidebar {
  background: #283646;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  cursor: pointer;
}

.logo-text {
  margin-left: 8px;
}

.sidebar-menu {
  border-right: none;
  height: calc(100vh - 60px);
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-menu:not(.el-menu--collapse) {
  width: 220px;
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: #34495e !important;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: #409eff !important;
}

.header {
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
}

.breadcrumb-back {
  margin-left: 12px;
}
.case-header-actions{display:flex;align-items:center;gap:8px;margin-left:8px}
.case-header-button{min-width:88px}

.collapse-btn {
  font-size: 20px;
  margin-right: 20px;
  cursor: pointer;
  color: #606266;
}

.header-right {
  display: flex;
  align-items: center;
}

.role-tag {
  padding: 4px 12px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 4px;
  font-size: 12px;
  margin-right: 15px;
}

.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  color: #606266;
}

.user-info span {
  margin-left: 8px;
}

.main-content {
  background: #f5f7fa;
  padding: 20px;
}
</style>
