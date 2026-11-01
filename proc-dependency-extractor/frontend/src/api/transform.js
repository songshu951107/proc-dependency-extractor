import request from './request'

// 脚本转换：列表 / 报告 / 全文 / 上传 / 下载地址
export function listScripts() {
  return request.get('/transform/scripts')
}

export function getReport(name) {
  return request.get(`/transform/scripts/${encodeURIComponent(name)}/report`)
}

export function getScriptFile(name, ver) {
  return request.get(`/transform/scripts/${encodeURIComponent(name)}/file`, {
    params: { ver },
  })
}

export function uploadScript(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/transform/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function downloadUrl(name, type) {
  return `/api/transform/scripts/${encodeURIComponent(name)}/download?type=${type}`
}
