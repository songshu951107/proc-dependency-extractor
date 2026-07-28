import request from './request'

// 查询 SQL 块详情
export function getSqlBlocks(procName) {
  return request({
    url: `/lineage/${procName}/sql-blocks`,
    method: 'get'
  })
}
