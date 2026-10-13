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

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code && res.code !== 200) {
      if (res.code === 401) {
        clearAuth()
        if (router.currentRoute.value.path !== '/login') router.replace('/login')
        ElMessage.error('登录已过期，请重新登录')
      } else if (res.code === 403) {
        ElMessage.error('无权限')
      } else {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request