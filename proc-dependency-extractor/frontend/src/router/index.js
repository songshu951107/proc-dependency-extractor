import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken, getUser, setUser } from '../utils/auth'
import { getCurrentUser } from '../api/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
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
    path: '/dict',
    name: 'DataDict',
    component: () => import('../views/DataDictView.vue'),
    meta: { title: '数据字典' }
  },
  {
    path: '/reportlib',
    name: 'ReportLib',
    component: () => import('../views/ReportLibView.vue'),
    meta: { title: '报表库' }
  },
  {
    path: '/schema-impact',
    name: 'SchemaImpact',
    component: () => import('../views/SchemaImpactView.vue'),
    meta: { title: '集市批量影响' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  if (to.meta.public) return next()
  if (!getToken()) return next('/login')
  if (!getUser()) {
    try {
      const res = await getCurrentUser()
      setUser(res.data)
    } catch (e) {
      return next('/login')
    }
  }
  const user = getUser()
  const allowed = (user?.menus || []).some(m =>
    m.path === to.path || (m.children || []).some(c => c.path === to.path))
  if (!allowed && to.path !== '/') return next('/')
  next()
})

export default router
