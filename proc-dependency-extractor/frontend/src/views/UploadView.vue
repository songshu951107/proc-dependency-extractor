<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-size: 16px; font-weight: bold;">导入存储过程脚本</span>
          <el-button type="primary" plain @click="goList">
            <el-icon><View /></el-icon>&nbsp;查看列表
          </el-button>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <!-- 方式1: 文件上传 -->
        <el-tab-pane label="上传 PY 文件" name="upload">
          <el-upload
            ref="uploadRef"
            drag
            multiple
            accept=".py"
            :auto-upload="false"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            :file-list="fileList"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">
              将 .py 文件拖到此处,或<em>点击选择</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                仅支持 .py 格式的存储过程脚本文件,可同时上传多个
              </div>
            </template>
          </el-upload>

          <div style="margin-top: 20px; text-align: center;">
            <el-button
              type="primary"
              :loading="uploading"
              :disabled="fileList.length === 0"
              @click="submitUpload"
            >
              <el-icon><Upload /></el-icon>
              开始解析并入库({{ fileList.length }} 个文件)
            </el-button>
            <el-button @click="clearFiles">清空</el-button>
          </div>
        </el-tab-pane>

        <!-- 方式2: 目录路径 -->
        <el-tab-pane label="按目录路径解析" name="dir">
          <el-form @submit.prevent>
            <el-form-item label="脚本目录">
              <el-input
                v-model="dirPath"
                placeholder="例如: d:/HB/work_zuoye/jiaoben"
                clearable
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="dirLoading"
                :disabled="!dirPath"
                @click="submitDir"
              >
                <el-icon><FolderOpened /></el-icon>
                开始解析
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            title="将扫描指定目录(含子目录)下所有 .py 文件进行解析"
            type="info"
            :closable="false"
          />
        </el-tab-pane>

        <!-- 方式3: 重新解析全部 -->
        <el-tab-pane label="重新解析全部" name="rebuild">
          <el-alert
            title="将根据已入库的存储过程记录,按其文件路径重新解析并覆盖入库"
            type="warning"
            :closable="false"
            show-icon
          />
          <div style="margin-top: 20px;">
            <el-button
              type="warning"
              :loading="rebuildLoading"
              @click="submitRebuild"
            >
              <el-icon><Refresh /></el-icon>
              重新解析全部
            </el-button>
          </div>

          <!-- 全量解析进度（仅与"重新解析全部"相关，未产生历史任务前不显示） -->
          <el-card v-if="hasTask" shadow="never" style="margin-top: 18px; border: 1px solid #ebeef5;">
            <template #header>
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span style="font-weight: bold;">全量解析进度</span>
                <el-tag :type="statusTagType">{{ statusText }}</el-tag>
              </div>
            </template>
            <el-progress
              :percentage="rebuildPercent"
              :stroke-width="14"
              :status="isRunning ? undefined : (lastTask.status === 'FAILED' ? 'exception' : 'success')"
            />
            <el-descriptions :column="4" border size="small" style="margin-top: 12px;">
              <el-descriptions-item label="总量">{{ lastTask.total }}</el-descriptions-item>
              <el-descriptions-item label="已完成">{{ lastTask.done }}</el-descriptions-item>
              <el-descriptions-item label="成功">
                <span style="color: #67c23a;">{{ lastTask.success }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="失败">
                <span style="color: #f56c6c;">{{ lastTask.fail }}</span>
              </el-descriptions-item>
            </el-descriptions>
            <div style="margin-top: 8px; color: #909399; font-size: 13px;">
              <template v-if="isRunning">
                正在解析：{{ lastTask.currentProc || '—' }}（{{ lastTask.done }}/{{ lastTask.total }}）
              </template>
              <template v-else-if="lastTask.endTime">
                完成时间：{{ lastTask.endTime }}
                <span v-if="lastTask.fail && lastTask.fail > 0" style="margin-left: 12px;">
                  含 <span style="color:#f56c6c;">{{ lastTask.fail }}</span> 条失败明细
                </span>
              </template>
            </div>
          </el-card>

          <!-- 解析失败清单（内嵌展示） -->
          <el-card shadow="never" style="margin-top: 18px; border: 1px solid #ebeef5;">
            <template #header>
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span style="font-weight: bold;">解析失败清单</span>
                <div>
                  <el-form :inline="true" style="display: inline-flex;">
                    <el-form-item style="margin-bottom: 0; margin-right: 8px;">
                      <el-switch v-model="failQuery.onlyUnresolved" active-text="仅看未处理" @change="loadParseFails(1)" />
                    </el-form-item>
                    <el-form-item style="margin-bottom: 0; margin-right: 8px;">
                      <el-input
                        v-model="failQuery.procName"
                        placeholder="按存储过程名搜索"
                        clearable
                        style="width: 200px"
                        @keyup.enter="loadParseFails(1)"
                      />
                    </el-form-item>
                    <el-form-item style="margin-bottom: 0;">
                      <el-button type="primary" size="small" @click="loadParseFails(1)">查询</el-button>
                    </el-form-item>
                  </el-form>
                </div>
              </div>
            </template>
            <el-table :data="failData" v-loading="failLoading" border stripe size="small" max-height="420">
              <el-table-column prop="procName" label="存储过程名" min-width="200" show-overflow-tooltip />
              <el-table-column prop="blockSeq" label="块号" width="64" align="center" />
              <el-table-column prop="blockType" label="操作" width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.blockType === 'INSERT_SELECT' ? 'success' : (row.blockType === 'CREATE_TEMP' ? 'warning' : 'info')">
                    {{ row.blockType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="failReason" label="错误原因" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span style="color: #f56c6c;">{{ row.failReason }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="sqlPreview" label="SQL 预览" min-width="260" show-overflow-tooltip>
                <template #default="{ row }">
                  <span style="font-family: Consolas, monospace; font-size: 12px;">{{ row.sqlPreview }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" width="160" />
              <el-table-column label="操作" width="300" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" type="info" link @click="openFailDetail(row)">
                    <el-icon><View /></el-icon>&nbsp;详情
                  </el-button>
                  <el-button size="small" type="primary" link :loading="row._reparsing" @click="handleFailReparse(row)">
                    重新解析
                  </el-button>
                  <el-button
                    v-if="row.resolved === 0"
                    size="small"
                    type="success"
                    link
                    @click="handleFailResolve(row)"
                  >
                    标记已处理
                  </el-button>
                  <el-tag v-else size="small" type="info">已处理</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <div style="margin-top: 12px; text-align: right;">
              <el-pagination
                v-model:current-page="failQuery.pageNum"
                v-model:page-size="failQuery.pageSize"
                :page-sizes="[10, 20, 50]"
                :total="failTotal"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="loadParseFails(1)"
                @current-change="loadParseFails()"
              />
            </div>
          </el-card>
        </el-tab-pane>

        <!-- 方式4: Neo4j 同步 -->
        <el-tab-pane label="Neo4j 同步" name="neo4j">
          <el-alert
            title="将全部存储过程同步到 Neo4j 血缘图（后台异步执行）。常用于 Neo4j 清空后重建或修复失败记录。"
            type="info"
            :closable="false"
            show-icon
          />
          <div style="margin-top: 20px;">
            <el-button
              type="primary"
              :loading="syncLoading"
              :disabled="syncRunning"
              @click="submitSyncAll"
            >
              <el-icon><Share /></el-icon>
              同步全部
            </el-button>
          </div>

          <!-- 同步进度（持久化展示） -->
          <el-card v-if="hasSyncTask" shadow="never" style="margin-top: 18px; border: 1px solid #ebeef5;">
            <template #header>
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span style="font-weight: bold;">Neo4j 同步进度</span>
                <el-tag :type="syncStatusTagType">{{ syncStatusText }}</el-tag>
              </div>
            </template>
            <el-progress
              :percentage="syncPercent"
              :stroke-width="14"
              :status="syncRunning ? undefined : (syncTask.status === 'FAILED' ? 'exception' : 'success')"
            />
            <el-descriptions :column="4" border size="small" style="margin-top: 12px;">
              <el-descriptions-item label="总量">{{ syncTask.total }}</el-descriptions-item>
              <el-descriptions-item label="已完成">{{ syncTask.done }}</el-descriptions-item>
              <el-descriptions-item label="成功">
                <span style="color: #67c23a;">{{ syncTask.success }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="失败">
                <span style="color: #f56c6c;">{{ syncTask.fail }}</span>
              </el-descriptions-item>
            </el-descriptions>
            <div style="margin-top: 8px; color: #909399; font-size: 13px;">
              <template v-if="syncRunning">
                正在同步：{{ syncTask.currentProc || '—' }}（{{ syncTask.done }}/{{ syncTask.total }}）
              </template>
              <template v-else-if="syncTask.endTime">
                完成时间：{{ syncTask.endTime }}
                <span v-if="syncTask.fail && syncTask.fail > 0" style="margin-left: 12px;">
                  含 <span style="color:#f56c6c;">{{ syncTask.fail }}</span> 条失败，见下方清单
                </span>
              </template>
            </div>
          </el-card>

          <!-- 同步报错/状态清单 -->
          <el-card shadow="never" style="margin-top: 18px; border: 1px solid #ebeef5;">
            <template #header>
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span style="font-weight: bold;">同步日志</span>
                <div>
                  <el-checkbox v-model="logOnlyLatest" style="margin-right: 12px;" @change="loadSyncLog(1)">只看最近一次同步</el-checkbox>
                  <el-select v-model="logStatus" placeholder="全部状态" style="width: 150px;" @change="loadSyncLog(1)">
                    <el-option label="全部状态" value="" />
                    <el-option label="失败" value="FAILED" />
                    <el-option label="待处理" value="PENDING" />
                    <el-option label="成功" value="SUCCESS" />
                  </el-select>
                </div>
              </div>
            </template>
            <el-table v-loading="logLoading" :data="logRows" border size="small" max-height="420">
              <el-table-column prop="id" label="#" width="60" align="center" />
              <el-table-column prop="procName" label="存储过程名" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="logStatusType(row.syncStatus)" size="small">{{ logStatusText(row.syncStatus) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="errorMessage" label="报错信息" min-width="260" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :title="row.errorMessage" style="color: #f56c6c;">{{ row.errorMessage || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="retryCount" label="重试" width="70" align="center" />
              <el-table-column prop="createTime" label="首次时间" width="170" />
              <el-table-column prop="updateTime" label="最近时间" width="170" />
            </el-table>
            <el-pagination
              style="margin-top: 12px; justify-content: flex-end;"
              background
              layout="total, prev, pager, next"
              :total="logTotal"
              :current-page="logPage"
              :page-size="logPageSize"
              @current-change="loadSyncLog"
            />
          </el-card>
        </el-tab-pane>
      </el-tabs>

      <!-- 失败详情弹窗：完整原因 + 完整 SQL（行号高亮） -->
      <el-dialog v-model="failDetailVisible" title="解析失败详情" width="820px" append-to-body>
        <template v-if="failDetail">
          <el-descriptions :column="2" border size="small" style="margin-bottom: 14px;">
            <el-descriptions-item label="存储过程名">{{ failDetail.procName }}</el-descriptions-item>
            <el-descriptions-item label="块号">#{{ failDetail.blockSeq }}</el-descriptions-item>
            <el-descriptions-item label="操作类型">
              <el-tag :type="failDetail.blockType === 'INSERT_SELECT' ? 'success' : (failDetail.blockType === 'CREATE_TEMP' ? 'warning' : 'info')">
                {{ failDetail.blockType }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="是否已处理">
              <el-tag :type="failDetail.resolved === 0 ? 'danger' : 'success'">
                {{ failDetail.resolved === 0 ? '未处理' : '已处理' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <div style="font-weight: bold; margin-bottom: 6px; color: #f56c6c;">错误原因</div>
          <pre style="white-space: pre-wrap; word-break: break-all; background: #fdf6f6; border: 1px solid #fbc4c4; border-radius: 4px; padding: 10px; margin: 0 0 14px; font-family: inherit; font-size: 13px; line-height: 1.6;">{{ failDetail.failReason }}</pre>

          <div style="font-weight: bold; margin-bottom: 6px;">完整 SQL</div>
          <div class="sql-view">
            <template v-for="ln in sqlLines" :key="ln.no">
              <div class="sql-row" :class="{ hl: ln.isHl }">
                <span class="ln">{{ ln.no }}</span>
                <span class="txt">{{ ln.text }}</span>
              </div>
            </template>
          </div>
          <div v-if="errorLine > 0" style="color: #f56c6c; font-size: 12px; margin-top: 6px;">
            ↑ 第 {{ errorLine }} 行（{{ errorToken || '该位置' }}）即上述错误所指的位置
          </div>
        </template>
      </el-dialog>

      <!-- 结果展示 -->
      <el-dialog v-model="resultVisible" title="解析结果" width="600px">
        <el-result
          :icon="resultIcon"
          :title="resultTitle"
          :sub-title="resultSubTitle"
        >
          <template #extra>
            <div v-if="result && result.failures && result.failures.length" style="text-align: left;">
              <el-divider content-position="left">失败详情</el-divider>
              <ul>
                <li v-for="(f, i) in result.failures" :key="i" style="color: #f56c6c;">{{ f }}</li>
              </ul>
            </div>
            <el-button type="primary" @click="resultVisible = false; goList()">查看列表</el-button>
          </template>
        </el-result>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Upload, UploadFilled, View, FolderOpened, Refresh, Share
} from '@element-plus/icons-vue'
import { uploadFiles, extractByDir, startRebuildAll, getRebuildTask, getRebuildLatest, listParseFails, resolveParseFail, reparseProc } from '../api/proc'
import { startResyncAllAsync, getResyncTask, getResyncLatest, getSyncLog } from '../api/impact'

const router = useRouter()
const activeTab = ref('upload')

// 上传相关
const uploadRef = ref()
const fileList = ref([])
const uploading = ref(false)

// 目录相关
const dirPath = ref('')
const dirLoading = ref(false)

// 结果对话框
const resultVisible = ref(false)
const result = ref(null)
const resultIcon = ref('success')
const resultTitle = ref('')
const resultSubTitle = ref('')

function handleFileChange(file, files) {
  // 仅保留 .py 文件
  fileList.value = files.filter((f) => f.name.endsWith('.py'))
}

function handleFileRemove(file, files) {
  fileList.value = files.filter((f) => f.name.endsWith('.py'))
}

function clearFiles() {
  fileList.value = []
  uploadRef.value && uploadRef.value.clearFiles()
}

async function submitUpload() {
  if (fileList.value.length === 0) {
    ElMessage.warning('请先选择 .py 文件')
    return
  }
  uploading.value = true
  try {
    const rawFiles = fileList.value.map((f) => f.raw)
    const res = await uploadFiles(rawFiles)
    showResult(res.data)
  } catch (e) {
    // 拦截器已处理提示
  } finally {
    uploading.value = false
  }
}

async function submitDir() {
  dirLoading.value = true
  try {
    const res = await extractByDir(dirPath.value)
    showResult(res.data)
  } catch (e) {
    // 拦截器已处理提示
  } finally {
    dirLoading.value = false
  }
}

// 全量解析异步状态（持久化展示）
const lastTask = ref(null)
const rebuildLoading = ref(false)
let rebuildTimer = null

const hasTask = computed(() => !!lastTask.value)
const isRunning = computed(() => hasTask.value && lastTask.value.status === 'RUNNING')
const rebuildPercent = computed(() => {
  const t = lastTask.value
  if (!t || !t.total) return 0
  return Math.floor(((t.done || 0) / t.total) * 100)
})
const statusText = computed(() => {
  const s = lastTask.value && lastTask.value.status
  if (s === 'RUNNING') return '进行中'
  if (s === 'FAILED') return '已中止'
  if (s === 'DONE') return '已完成'
  return '—'
})
const statusTagType = computed(() => {
  const s = lastTask.value && lastTask.value.status
  if (s === 'RUNNING') return 'warning'
  if (s === 'FAILED') return 'danger'
  return 'success'
})

function stopPolling() {
  if (rebuildTimer) {
    clearInterval(rebuildTimer)
    rebuildTimer = null
  }
  rebuildLoading.value = false
}

function applyTask(task) {
  if (task) {
    lastTask.value = task
  }
}

async function pollTask(taskId) {
  stopPolling()
  rebuildTimer = setInterval(async () => {
    try {
      const tRes = await getRebuildTask(taskId)
      const task = tRes.data
      if (!task) {
        stopPolling()
        return
      }
      applyTask(task)
      if (task.status === 'DONE' || task.status === 'FAILED') {
        stopPolling()
        refreshParseFails()
        if (task.status === 'DONE' && (task.failures || task.fail > 0)) {
          showResult({
            totalCount: task.total,
            successCount: task.success,
            failCount: task.fail,
            failures: parseFailures(task.failures)
          })
        }
      }
    } catch (e) {
      // 单次轮询失败不终止，交给下次
    }
  }, 1000)
}

function parseFailures(raw) {
  if (!raw) return []
  try { return JSON.parse(raw) } catch (e) { return [] }
}

function latestRunningTaskId() {
  const t = lastTask.value
  return t && t.status === 'RUNNING' ? t.taskId : null
}

async function submitRebuild() {
  rebuildLoading.value = true
  try {
    const res = await startRebuildAll()
    const taskId = res.data
    lastTask.value = { taskId, status: 'RUNNING', total: 0, done: 0, success: 0, fail: 0 }
    pollTask(taskId)
  } catch (e) {
    stopPolling()
  }
}

async function loadLatest() {
  try {
    const res = await getRebuildLatest()
    applyTask(res.data)
    const runningId = latestRunningTaskId()
    if (runningId) {
      pollTask(runningId)
    }
  } catch (e) {
    // 忽略：无历史任务或接口暂不可用
  }
}

onMounted(async () => {
  loadLatest()
  loadParseFails(1)
  // 先加载最近同步任务，再加载日志，确保"只看最近一次同步"能拿到 startTime
  await loadSyncLatest()
  await loadSyncLog(1)
})
onBeforeUnmount(() => {
  stopPolling()
  stopSyncPolling()
})

// ===== Neo4j 同步（异步全量同步 + 进度持久化 + 报错清单） =====
const syncTask = ref(null)
const syncLoading = ref(false)
let syncTimer = null

// 同步日志清单
const logRows = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logPageSize = ref(10)
const logStatus = ref('FAILED')
const logLoading = ref(false)

// 方案1：默认只显示"最近一次同步开始后"的日志，隐藏更早的历史失败
const logOnlyLatest = ref(true)

const hasSyncTask = computed(() => !!syncTask.value)
const syncRunning = computed(() => hasSyncTask.value && syncTask.value.status === 'RUNNING')
const syncPercent = computed(() => {
  const t = syncTask.value
  if (!t || !t.total) return 0
  return Math.floor(((t.done || 0) / t.total) * 100)
})
const syncStatusText = computed(() => {
  const s = syncTask.value && syncTask.value.status
  if (s === 'RUNNING') return '进行中'
  if (s === 'FAILED') return '已中止'
  if (s === 'DONE') return '已完成'
  return '—'
})
const syncStatusTagType = computed(() => {
  const s = syncTask.value && syncTask.value.status
  if (s === 'RUNNING') return 'warning'
  if (s === 'FAILED') return 'danger'
  return 'success'
})

function logStatusType(s) {
  if (s === 'FAILED') return 'danger'
  if (s === 'PENDING') return 'warning'
  return 'success'
}
function logStatusText(s) {
  if (s === 'FAILED') return '失败'
  if (s === 'PENDING') return '待处理'
  return '成功'
}

function stopSyncPolling() {
  if (syncTimer) {
    clearInterval(syncTimer)
    syncTimer = null
  }
  syncLoading.value = false
}

function pollSyncTask(taskId) {
  stopSyncPolling()
  syncTimer = setInterval(async () => {
    try {
      const tRes = await getResyncTask(taskId)
      const t = tRes.data
      if (!t) {
        stopSyncPolling()
        return
      }
      syncTask.value = t
      if (t.status === 'DONE' || t.status === 'FAILED') {
        stopSyncPolling()
        loadSyncLog(1)
      }
    } catch (e) {
      // 单次失败不终止，交给下次
    }
  }, 1000)
}

async function submitSyncAll() {
  syncLoading.value = true
  try {
    const res = await startResyncAllAsync()
    const taskId = res.data
    syncTask.value = { taskId, status: 'RUNNING', total: 0, done: 0, success: 0, fail: 0 }
    pollSyncTask(taskId)
  } catch (e) {
    stopSyncPolling()
  }
}

async function loadSyncLatest() {
  try {
    const res = await getResyncLatest()
    if (res.data) {
      syncTask.value = res.data
      if (res.data.status === 'RUNNING') {
        pollSyncTask(res.data.taskId)
      }
    }
  } catch (e) {
    // 忽略：无历史任务或接口暂不可用
  }
}

async function loadSyncLog(page) {
  logLoading.value = true
  try {
    const params = {
      status: logStatus.value,
      pageNum: page || logPage.value,
      pageSize: logPageSize.value
    }
    // 只看最近一次同步：携带该任务开始时间，过滤更早的历史失败
    if (logOnlyLatest.value && syncTask.value && syncTask.value.startTime) {
      params.since = syncTask.value.startTime
    }
    const res = await getSyncLog(params)
    logPage.value = (res.data && res.data.current) || logPage.value
    logTotal.value = (res.data && res.data.total) || 0
    logRows.value = (res.data && res.data.records) || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    logLoading.value = false
  }
}

function showResult(data) {
  result.value = data
  if (!data) {
    resultIcon.value = 'error'
    resultTitle.value = '无结果'
    resultSubTitle.value = ''
  } else if (data.failCount > 0 && data.successCount === 0) {
    resultIcon.value = 'error'
    resultTitle.value = '解析失败'
    resultSubTitle.value = `共 ${data.totalCount} 个,失败 ${data.failCount} 个`
  } else if (data.failCount > 0) {
    resultIcon.value = 'warning'
    resultTitle.value = '部分成功'
    resultSubTitle.value = `共 ${data.totalCount} 个,成功 ${data.successCount} 个,失败 ${data.failCount} 个`
  } else {
    resultIcon.value = 'success'
    resultTitle.value = '解析成功'
    resultSubTitle.value = `共解析 ${data.successCount} 个存储过程`
  }
  resultVisible.value = true
}

function goList() {
  router.push('/procs')
}

// ===== 解析失败清单（内嵌于"重新解析全部"标签页） =====
const failData = ref([])
const failTotal = ref(0)
const failLoading = ref(false)
const failQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  procName: '',
  onlyUnresolved: false
})

async function loadParseFails(page) {
  failLoading.value = true
  try {
    if (page) failQuery.pageNum = page
    const res = await listParseFails({
      pageNum: failQuery.pageNum,
      pageSize: failQuery.pageSize,
      procName: failQuery.procName || undefined,
      onlyUnresolved: failQuery.onlyUnresolved || undefined
    })
    failTotal.value = (res.data && res.data.total) || 0
    failData.value = (res.data && res.data.records) || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    failLoading.value = false
  }
}

function refreshParseFails() {
  failQuery.pageNum = 1
  loadParseFails(1)
}

async function handleFailReparse(row) {
  row._reparsing = true
  try {
    const res = await reparseProc(row.procName)
    ElMessage.success(`已重新解析 ${res.data && res.data.procName ? res.data.procName : row.procName}`)
    refreshParseFails()
  } catch (e) {
    // 拦截器已提示
  } finally {
    row._reparsing = false
  }
}

async function handleFailResolve(row) {
  try {
    await resolveParseFail(row.id)
    ElMessage.success('已标记处理')
    refreshParseFails()
  } catch (e) {
    // 拦截器已提示
  }
}

// ===== 失败详情弹窗（完整原因 + 完整 SQL 行号高亮） =====
const failDetailVisible = ref(false)
const failDetail = ref(null)

function openFailDetail(row) {
  failDetail.value = JSON.parse(JSON.stringify(row))
  failDetailVisible.value = true
}

const errorLine = computed(() => {
  const r = failDetail.value && failDetail.value.failReason
  if (!r) return 0
  const m = r.match(/第\s*(\d+)\s*行/)
  return m ? parseInt(m[1], 10) : 0
})
const errorToken = computed(() => {
  const r = failDetail.value && failDetail.value.failReason
  if (!r) return ''
  const m = r.match(/“([^”]+)”/)
  return m ? m[1] : ''
})
const sqlLines = computed(() => {
  const sql = failDetail.value && failDetail.value.sqlPreview
  if (!sql) return []
  const target = errorLine.value
  return sql.split('\n').map((t, i) => ({ no: i + 1, text: t, isHl: i + 1 === target }))
})
</script>

<style scoped>
.el-upload-dragger {
  width: 100%;
}
.sql-view {
  background: #0f172a;
  border-radius: 4px;
  max-height: 420px;
  overflow: auto;
  font-family: Consolas, Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  padding: 8px 0;
}
.sql-row {
  display: flex;
  white-space: pre;
}
.sql-row.hl {
  background: rgba(245, 108, 108, 0.18);
}
.ln {
  min-width: 42px;
  text-align: right;
  padding-right: 12px;
  color: #64748b;
  user-select: none;
  flex-shrink: 0;
}
.txt {
  color: #e2e8f0;
}
</style>
