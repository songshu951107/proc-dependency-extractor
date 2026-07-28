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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
