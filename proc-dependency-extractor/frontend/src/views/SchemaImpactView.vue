<template>
  <div style="padding: 16px;">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
      <el-select v-model="schema" placeholder="选择 Schema" filterable style="width: 200px;"
                 :disabled="running" @change="onSchemaChange">
        <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button type="primary" :loading="running" :disabled="!schema || running" @click="startAnalysis">
        批量分析
      </el-button>
      <el-button :disabled="!result || running" @click="doExport">导出 Excel</el-button>
      <el-progress v-if="task" :percentage="percent()" :status="taskStatus()"
                   :stroke-width="12" style="width: 240px;" />
    </div>

    <el-empty v-if="!result && !running" description="请选择 Schema 并点击「批量分析」开始" />

    <el-table v-if="result" :data="result.rows" border height="560">
      <el-table-column prop="table" label="表名" min-width="280" show-overflow-tooltip />
      <el-table-column label="被影响表数" width="120" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedTableCount" @click="openDetail(row, 'tables')">
            {{ row.affectedTableCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="受影响存储过程数" width="160" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedProcCount" @click="openDetail(row, 'procs')">
            {{ row.affectedProcCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="涉及报表数" width="120" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedReportCount" @click="openDetail(row, 'reports')">
            {{ row.affectedReportCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="140">
        <template #default="{ row }">
          <span v-if="row.error" style="color: #f56c6c;">失败: {{ row.error }}</span>
          <span v-else style="color: #67c23a;">OK</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailVisible" :title="detailTitle()" width="760px" top="8vh">
      <el-table :data="detailRows" border max-height="460">
        <template v-if="detailKind === 'tables'">
          <el-table-column prop="name" label="受影响表" min-width="280" />
          <el-table-column prop="procName" label="到达的存储过程" min-width="240" />
        </template>
        <template v-else-if="detailKind === 'procs'">
          <el-table-column prop="name" label="受影响存储过程" min-width="360" />
        </template>
        <template v-else-if="detailKind === 'reports'">
          <el-table-column prop="file" label="报表文件" min-width="220" />
          <el-table-column prop="name" label="报表名称" min-width="160" />
          <el-table-column prop="datasetName" label="数据集" min-width="160" />
          <el-table-column prop="connection" label="连接" min-width="140" />
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getSchemas,
  startSchemaBatchTask,
  getSchemaBatchProgress,
  getSchemaBatchResult,
  exportSchemaBatch
} from '@/api/impact'

const schemas = ref([])
const schema = ref('')
const running = ref(false)
const task = ref(null)
const result = ref(null)
const detailVisible = ref(false)
const detailKind = ref('tables')
const detailRows = ref([])
let pollTimer = null

function percent() {
  return task.value ? task.value.percent : 0
}
function taskStatus() {
  if (!task.value) return 'success'
  return task.value.status === 'failed' ? 'exception' : 'success'
}
function detailTitle() {
  const t = detailKind.value === 'tables' ? '被影响表明细'
    : detailKind.value === 'procs' ? '受影响存储过程明细' : '涉及报表明细'
  return t
}

async function loadSchemas() {
  const res = await getSchemas()
  schemas.value = res.data || []
}
loadSchemas()

function onSchemaChange() {
  stopPoll()
  result.value = null
  task.value = null
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  running.value = false
}

async function startAnalysis() {
  stopPoll()
  result.value = null
  running.value = true
  try {
    const res = await startSchemaBatchTask(schema.value)
    const taskId = res.data
    task.value = { taskId, status: 'running', total: 0, done: 0, failed: 0, percent: 0 }
    poll(taskId)
  } catch (e) {
    stopPoll()
  }
}

function poll(taskId) {
  pollTimer = setInterval(async () => {
    try {
      const res = await getSchemaBatchProgress(schema.value, taskId)
      const t = res.data
      if (!t) {
        stopPoll()
        return
      }
      task.value = t
      if (t.status === 'done') {
        stopPoll()
        await loadResult(taskId)
      } else if (t.status === 'failed') {
        stopPoll()
        ElMessage.error(t.error || '分析失败')
      }
    } catch (e) {
      // 单次失败交给下次
    }
  }, 2000)
}

async function loadResult(taskId) {
  const res = await getSchemaBatchResult(schema.value, taskId)
  result.value = res.data
}

function openDetail(row, kind) {
  detailKind.value = kind
  detailVisible.value = true
  if (kind === 'tables') {
    detailRows.value = row.affectedTables || []
  } else if (kind === 'procs') {
    detailRows.value = (row.affectedProcs || []).map((p) => ({ name: p }))
  } else {
    detailRows.value = row.affectedReports || []
  }
}

async function doExport() {
  if (!task.value || !result.value) {
    ElMessage.warning('请先完成分析')
    return
  }
  try {
    const blob = await exportSchemaBatch(schema.value, task.value.taskId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `schema_impact_${schema.value}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    // 拦截器已提示
  }
}

onBeforeUnmount(stopPoll)
</script>