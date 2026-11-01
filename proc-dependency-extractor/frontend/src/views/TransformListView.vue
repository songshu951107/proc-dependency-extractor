<template>
  <div class="page">
    <div class="toolbar">
      <el-upload :show-file-list="false" accept=".py" :before-upload="beforeUpload"
                 :http-request="doUpload">
        <el-button type="primary">上传脚本</el-button>
      </el-upload>
      <el-button @click="fetchData" style="margin-left:12px">刷新</el-button>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="name" label="脚本名" min-width="260" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="tagType(row)" effect="dark">{{ tagText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="oldTable" label="旧表" min-width="200" />
      <el-table-column prop="newTable" label="新表" min-width="200" />
      <el-table-column prop="totalEdits" label="转换点" width="90" />
      <el-table-column label="残留" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.residueCount > 0" type="warning" effect="plain">
            {{ row.residueCount }}
          </el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column prop="pyCompile" label="py_compile" width="110" />
      <el-table-column prop="convertedAt" label="转换时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status === 'PENDING'"
                     @click="$router.push(`/transform/${encodeURIComponent(row.name)}`)">
            查看对比
          </el-button>
          <el-button link :disabled="row.status === 'PENDING'"
                     @click="doDownload(row)">下载</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listScripts, uploadScript } from '@/api/transform'
import request from '@/api/request'

const list = ref([])
const loading = ref(false)

const tagType = (row) => ({
  PENDING: 'info', CONVERTED: 'success',
  FAILED: 'danger', STALE: 'warning',
}[row.status] || 'info')
const tagText = (row) => ({
  PENDING: '待转换', CONVERTED: '已转换',
  FAILED: '转换失败', STALE: '源已更新，待重转',
}[row.status] || row.status)

async function fetchData() {
  loading.value = true
  try {
    const { data } = await listScripts()
    list.value = data.data || []
  } finally {
    loading.value = false
  }
}

function beforeUpload(file) {
  if (!file.name.endsWith('.py')) {
    ElMessage.error('仅支持 .py 脚本')
    return false
  }
  return true
}

async function doUpload({ file }) {
  // 同名替换确认（规格 §7.1）
  const exists = list.value.find((r) => r.name === file.name)
  if (exists && exists.status !== 'PENDING') {
    try {
      await ElMessageBox.confirm(
        `已存在同名脚本 ${file.name}，替换后需重新离线转换。继续？`, '替换确认',
        { type: 'warning' })
    } catch { return }
  }
  const { data } = await uploadScript(file)
  ElMessage.success(data.data)
  fetchData()
}

// 下载：项目现有手法为 axios blob（见 SchemaImpactView.vue exportSchemaBatch）。
// 因 request 拦截器注入 Authorization 头，window.open 无法携带鉴权，故走 blob 下载。
async function doDownload(row) {
  try {
    const blob = await request({
      url: `/transform/scripts/${encodeURIComponent(row.name)}/download`,
      method: 'get',
      params: { type: 'converted' },
      responseType: 'blob',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = row.name
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    // 拦截器已处理
  }
}

onMounted(fetchData)
</script>
