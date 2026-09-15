import request from './request'

export function getSchemas() {
  return request({ url: '/impact/schemas', method: 'get' })
}

export function getTables(schema) {
  return request({ url: '/impact/tables', params: { schema }, method: 'get' })
}

export function getFields(tableFullName) {
  return request({ url: '/impact/fields', params: { tableFullName }, method: 'get' })
}

export function analyze(data) {
  return request({ url: '/impact/analyze', method: 'post', data })
}

export function expand(data) {
  return request({ url: '/impact/expand', method: 'post', data })
}

export function getPaths(params) {
  return request({ url: '/impact/paths', method: 'get', params })
}

export function resync(procName) {
  return request({ url: '/impact/resync', method: 'post', params: { procName } })
}

export function resyncAll() {
  return request({ url: '/impact/resync/all', method: 'post' })
}

// 异步后台全量同步 Neo4j（返回 taskId，前端轮询进度）
export function startResyncAllAsync() {
  return request({ url: '/impact/resync/all/async', method: 'post' })
}

// 查询异步全量同步任务进度
export function getResyncTask(taskId) {
  return request({ url: `/impact/resync/all/task/${taskId}`, method: 'get' })
}

// 查询最近一次全量同步任务进度
export function getResyncLatest() {
  return request({ url: '/impact/resync/all/task/latest', method: 'get' })
}

// 分页查询 Neo4j 同步日志（报错清单）
export function getSyncLog(params) {
  return request({ url: '/impact/sync-log', method: 'get', params })
}

export function getSyncStatus() {
  return request({ url: '/impact/sync-status', method: 'get' })
}

// 批量 schema 下游影响（异步任务 + 进度轮询）
export function startSchemaBatchTask(schema) {
  return request({ url: `/impact/schema/${schema}/task`, method: 'post' })
}
export function getSchemaBatchProgress(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/progress`, method: 'get' })
}
export function getSchemaBatchResult(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/result`, method: 'get' })
}
// responseType blob：拦截器对 blob 不做 code 校验，正常返回 Blob
export function exportSchemaBatch(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/export`, method: 'get', responseType: 'blob' })
}
