import request from './request'

// 映射配置管理（transform_config.json）：表对 CRUD + 批量导入推导

// 全量表对配置
export function getConfig() {
  return request.get('/transform/config')
}

// 新增/编辑单个表对（old_table 主键；column_mapping 可空 = 仅表名替换）
export function saveConfigEntry(entry) {
  return request.post('/transform/config', entry)
}

// 删除表对
export function deleteConfigEntry(oldTable) {
  return request.delete(`/transform/config/${encodeURIComponent(oldTable)}`)
}

// 批量导入预览（全自动）：一次上传多个文件，后端自动识别 F/O 层、配对、推导 column_mapping
export function importPreview(files) {
  const fd = new FormData()
  files.forEach((f) => fd.append('files', f))
  return request.post('/transform/config/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 确认导入：预览确认后的表对批量落库
export function applyConfigEntries(entries) {
  return request.post('/transform/config/apply', entries)
}
