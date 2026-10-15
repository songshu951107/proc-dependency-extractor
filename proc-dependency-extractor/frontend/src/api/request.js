import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '../utils/auth'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 60000 })

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 相同文案合并为一条，避免并发请求叠加出多条相同提示
const notify = (message) => ElMessage({ type: 'error', message, grouping: true })

// 401 并发时只清理/跳转一次，避免重复 clearAuth 与冗余路由跳转
let handling401 = false
function handleUnauthorized() {
  if (handling401) return
  handling401 = true
  clearAuth()
  if (router.currentRoute.value.path !== '/login') router.replace('/login')
  notify('登录已过期，请重新登录')
  setTimeout(() => { handling401 = false }, 1000)
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code && res.code !== 200) {
      if (res.code === 401) {
        handleUnauthorized()
      } else if (res.code === 403) {
        notify('无权限')
      } else {
        notify(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res
  },
  (error) => {
    notify(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request