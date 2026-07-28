import request from './request'

// 查询脚本内容
export function getScript(procName) {
  return request({
    url: `/proc/${procName}/script`,
    method: 'get'
  })
}
