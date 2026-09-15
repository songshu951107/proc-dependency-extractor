import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Upload',
    component: () => import('../views/UploadView.vue'),
    meta: { title: '导入脚本' }
  },
  {
    path: '/procs',
    name: 'Procs',
    component: () => import('../views/ProcListView.vue'),
    meta: { title: '存储过程列表' }
  },
  {
    path: '/procs/:procName',
    name: 'ProcDetail',
    component: () => import('../views/ProcDetailView.vue'),
    meta: { title: '依赖表详情' }
  },
  {
    path: '/procs/:procName/lineage',
    name: 'Lineage',
    component: () => import('../views/LineageView.vue'),
    meta: { title: '血缘图' }
  },
  {
    path: '/procs/:procName/lineage/list',
    name: 'LineageList',
    component: () => import('../views/LineageView.vue'),
    meta: { title: '血缘列表' }
  },
  {
    path: '/impact',
    name: 'ImpactAnalysis',
    component: () => import('../views/ImpactAnalysis.vue'),
    meta: { title: '影响分析' }
  },
  {
    path: '/report-impact',
    name: 'ReportImpact',
    component: () => import('../views/ReportImpactView.vue'),
    meta: { title: '报表影响分析' }
  },
  {
    path: '/schema-impact',
    name: 'SchemaImpact',
    component: () => import('../views/SchemaImpactView.vue'),
    meta: { title: '集市批量影响' }
  },
  {
    path: '/nl2sql',
    name: 'Nl2Sql',
    component: () => import('../views/Nl2SqlView.vue'),
    meta: { title: '智能查数' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
