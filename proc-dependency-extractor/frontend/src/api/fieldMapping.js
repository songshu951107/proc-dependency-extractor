import request from './request'

// 查询字段映射
export function getFieldMapping(procName, tableName, recursive = false) {
  return request({
    url: `/lineage/${procName}/field-mapping`,
    method: 'get',
    params: { tableName, recursive }
  })
}
