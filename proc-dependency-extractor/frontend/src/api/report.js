import request from './request'

export function parseReport(path) {
  return request({ url: '/report/parse', method: 'post', data: { path } })
}

export function parseReportUpload(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/report/parse/upload',
    method: 'post',
    data: formData,
    // 让浏览器自动设置 multipart/form-data boundary，不能手动设
    headers: { 'Content-Type': undefined }
  })
}

export function parseReportBatch(path) {
  return request({ url: '/report/parse/batch', method: 'post', data: { path } })
}

/** 异步启动报表全量重新解析，返回 taskId */
export function startReParseAll() {
  return request({ url: '/report/parse/all/start', method: 'post' })
}

/** 查询报表全量重新解析任务进度 */
export function getReParseAllProgress(taskId) {
  return request({ url: '/report/parse/all/progress', method: 'get', params: { taskId } })
}

// 获取配置的报表脚本目录（extractor.report.dir），用于动态展示默认目录
export function getReportDir() {
  return request({ url: '/report/config/dir', method: 'get' })
}

export function getReportList(params) {
  return request({ url: '/report/list', method: 'get', params })
}

export function getReportDetail(file) {
  return request({ url: `/report/${encodeURIComponent(file)}`, method: 'get' })
}

export function getReportDatasets(file) {
  return request({ url: `/report/${encodeURIComponent(file)}/datasets`, method: 'get' })
}

export function deleteReport(file) {
  return request({ url: `/report/${encodeURIComponent(file)}`, method: 'delete' })
}

export function findReportsByTable(schema, table) {
  return request({ url: '/report-impact/by-table', method: 'get', params: { schema, table } })
}

export function findReportsByField(schema, table, field) {
  return request({ url: '/report-impact/by-field', method: 'get', params: { schema, table, field } })
}

export function findByHeaderText(headerText) {
  return request({ url: '/report-impact/by-header', method: 'get',
    params: { headerText } })
}

export function getReportFields(schema, table) {
  return request({ url: '/report-impact/fields', method: 'get',
    params: { schema, table } })
}

/** 场景4：报表列溯源 - 获取指定报表的全部表头列汉字 */
export function getReportHeaders(reportFile) {
  return request({ url: '/report-impact/report-headers', method: 'get',
    params: { reportFile } })
}

/** 场景4：报表列溯源 - 根据报表+表头列追溯上游字段级链路 */
export function traceReportLineage(reportFile, headerText) {
  return request({ url: '/report-impact/trace-lineage', method: 'get',
    params: { reportFile, headerText } })
}
