import request from './request'

// 查询脚本内容（通过 procName）
export function getScript(procName) {
  return request({
    url: `/proc/${procName}/script`,
    method: 'get'
  })
}

// 查询脚本内容（通过 scriptFile，如 dwa_c_cus_pri_cust_loan_sum_crms.py）
// 后端按 procName 查找，去掉 .py 后缀
export function getScriptByFile(scriptFile) {
  const procName = scriptFile.replace(/\.py$/i, '')
  return request({
    url: `/proc/${procName}/script`,
    method: 'get'
  })
}
