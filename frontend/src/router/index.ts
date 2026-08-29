
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue')
    },
    {
      path: '/',
      component: () => import('@/components/layout/AppLayout.vue'),
      redirect: '/overview',
      children: [
        { path: 'overview', name: 'Overview', component: () => import('@/views/Overview.vue') },
        { path: 'analysis/upload', name: 'CaseUpload', component: () => import('@/views/analysis/PipelineWorkbench.vue') },
        { path: 'analysis/aml', redirect: '/analysis/upload' },
        { path: 'analysis/fraud', redirect: '/analysis/upload' },
        { path: 'analysis/structured', redirect: '/analysis/upload' },
        { path: 'analysis/structured/result/:jobId', name: 'StructuredCaseResult', component: () => import('@/views/analysis/StructuredCaseResult.vue'), meta: { pipeline: 'STRUCTURED' } },
        { path: 'case/list', name: 'CaseList', component: () => import('@/views/case/CaseList.vue') },
        { path: 'case/detail/:caseId', name: 'CaseDetail', component: () => import('@/views/case/CaseDetail.vue') },
        { path: 'case/graph', name: 'CaseGraph', component: () => import('@/views/case/CaseGraph.vue') },
        { path: 'case/processing/report', name: 'CaseReportProcessing', component: () => import('@/views/case/CaseProcessing.vue'), meta: { processingPage: 'report' } },
        { path: 'case/processing/framework', name: 'CaseFrameworkProcessing', component: () => import('@/views/case/CaseProcessing.vue'), meta: { processingPage: 'framework' } },
        { path: 'case/processing/similarity', name: 'CaseSimilarityProcessing', component: () => import('@/views/case/CaseProcessing.vue'), meta: { processingPage: 'similarity' } },
        { path: 'case/processing/approval', name: 'CaseApprovalProcessing', component: () => import('@/views/case/CaseProcessing.vue'), meta: { processingPage: 'approval' } },
        { path: 'case/review', redirect: '/case/processing/approval' },
        { path: 'case/approval', redirect: '/case/processing/approval' },
        { path: 'graph/visualize', name: 'GraphVisualize', component: () => import('@/views/graph/Visualize.vue') },
        { path: 'graph/association-clues', name: 'AssociationClueWorkbench', component: () => import('@/views/graph/AssociationClueWorkbench.vue') },
        { path: 'graph/hidden-risk', name: 'HiddenRiskWorkbench', component: () => import('@/views/graph/HiddenRiskWorkbench.vue') },
        { path: 'system/roles', name: 'Roles', component: () => import('@/views/system/Roles.vue') },
        { path: 'system/menus', name: 'Menus', component: () => import('@/views/system/Menus.vue') },
        { path: 'system/event-metadata', name: 'EventMetadata', component: () => import('@/views/system/EventMetadata.vue') },
        { path: 'system/indicators', name: 'IndicatorManagement', component: () => import('@/views/system/IndicatorManagement.vue') }
      ]
    }
  ]
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')

  if (to.path === '/login') {
    if (token) {
      next('/overview')
    } else {
      next()
    }
  } else {
    if (!token) {
      next('/login')
    } else {
      const menus = readMenus()
      const allowedRoots: Record<string, string[]> = {
        sadmin: ['overview','analysis','case','graph','system'],
        badmin: ['overview','analysis','case','graph'],
        madmin: ['analysis','case','graph']
      }
      const roleCode = localStorage.getItem('roleCode') || ''
      const menuPaths = flattenMenuPaths(menus)
      const root = to.path.split('/')[1]
      const routeAllowed = menuPaths.size > 0
        ? menuPaths.has(to.path) || [...menuPaths].some(path => to.path.startsWith(`${path}/`))
        : (allowedRoots[roleCode] || []).includes(root)
      routeAllowed ? next() : next('/overview')
    }
  }
})

function readMenus(): any[] {
  try {
    const value = JSON.parse(localStorage.getItem('menus') || '[]')
    return Array.isArray(value) ? value : []
  } catch {
    return []
  }
}

function flattenMenuPaths(menus: any[]): Set<string> {
  const paths = new Set<string>()
  const visit = (items: any[]) => items.forEach(item => {
    if (item?.path) paths.add(item.path)
    if (Array.isArray(item?.children)) visit(item.children)
  })
  visit(menus)
  return paths
}

export default router
