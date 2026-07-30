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

export function getSyncStatus() {
  return request({ url: '/impact/sync-status', method: 'get' })
}
