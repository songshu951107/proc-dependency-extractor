import request from './request'

// 上传 py 文件解析入库
export function uploadFiles(files) {
  const formData = new FormData()
  files.forEach((file) => {
    formData.append('files', file)
  })
  return request.post('/proc/extract/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 按目录路径批量解析
export function extractByDir(path) {
  return request.post('/proc/extract/batch', { path })
}

// 重新解析全部
export function reExtractAll() {
  return request.post('/proc/extract/all')
}

// 分页查询存储过程列表
export function listProcs(params) {
  return request.get('/proc/list', { params })
}

// 查询某存储过程的依赖表
export function listDependencies(procName) {
  return request.get(`/proc/${procName}/tables`)
}

// 删除存储过程
export function deleteProc(procName) {
  return request.delete(`/proc/${procName}`)
}
