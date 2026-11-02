import request from './request'

// 脚本转换：列表 / 报告 / 全文 / 上传
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

// 触发单脚本转换（PENDING→开始转换，STALE/FAILED→重新转换）
export function convertScript(name) {
  return request.post(`/transform/scripts/${encodeURIComponent(name)}/convert`)
}

// 删除源脚本与产物
export function deleteScript(name) {
  return request.delete(`/transform/scripts/${encodeURIComponent(name)}`)
}
