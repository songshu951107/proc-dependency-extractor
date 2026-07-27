import request from './request'

// 查询某存储过程完整血缘图
export function getLineage(procName) {
  return request({
    url: `/lineage/${procName}`,
    method: 'get'
  })
}

// 递归上游血缘
export function traceUpstream(tableName, maxDepth) {
  return request({
    url: `/lineage/table/${tableName}/upstream`,
    method: 'get',
    params: maxDepth ? { maxDepth } : {}
  })
}

// 递归下游血缘
export function traceDownstream(tableName, maxDepth) {
  return request({
    url: `/lineage/table/${tableName}/downstream`,
    method: 'get',
    params: maxDepth ? { maxDepth } : {}
  })
}
