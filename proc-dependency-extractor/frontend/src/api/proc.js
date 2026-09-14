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

// 异步重新解析全部（返回 taskId）
export function startRebuildAll() {
  return request.post('/proc/extract/all/async')
}

// 查询异步全量解析任务进度
export function getRebuildTask(taskId) {
  return request.get(`/proc/extract/all/task/${taskId}`)
}

// 查询最近一次全量解析任务进度（页面常驻展示用）
export function getRebuildLatest() {
  return request.get('/proc/extract/all/task/latest')
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

// 分页查询解析失败清单
export function listParseFails(params) {
  return request.get('/proc/parse-fails', { params })
}

// 标记一条解析失败为已处理
export function resolveParseFail(id) {
  return request.post(`/proc/parse-fail/${id}/resolve`)
}

// 重新解析单个存储过程
export function reparseProc(procName) {
  return request.post(`/proc/${procName}/reparse`)
}
