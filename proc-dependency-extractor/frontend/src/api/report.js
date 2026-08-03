import request from './request'

export function parseReport(path) {
  return request({ url: '/report/parse', method: 'post', data: { path } })
}

export function parseReportBatch(path) {
  return request({ url: '/report/parse/batch', method: 'post', data: { path } })
}

export function reParseAllReports() {
  return request({ url: '/report/parse/all', method: 'post' })
}

export function getReportList(params) {
  return request({ url: '/report/list', method: 'get', params })
}

export function getReportDetail(file) {
  return request({ url: `/report/${encodeURIComponent(file)}`, method: 'get' })
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

export function traceFieldSource(reportFile, datasetName, columnName) {
  return request({ url: '/report-impact/field-source', method: 'get',
    params: { reportFile, datasetName, columnName } })
}

export function analyzeCrossImpact(schema, table, field) {
  return request({ url: '/report-impact/cross-impact', method: 'get',
    params: { schema, table, field } })
}
