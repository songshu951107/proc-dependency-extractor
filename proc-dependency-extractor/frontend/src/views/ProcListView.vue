<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-size: 16px; font-weight: bold;">存储过程列表</span>
          <div>
            <el-badge :value="failCount" :hidden="failCount === 0" type="danger" style="margin-right: 12px;">
              <el-button type="warning" @click="openDrawer">
                <el-icon><Warning /></el-icon>&nbsp;解析失败
              </el-button>
            </el-badge>
            <el-button type="primary" @click="goUpload">
              <el-icon><Upload /></el-icon>&nbsp;导入脚本
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索 -->
      <el-form :inline="true" style="margin-bottom: 10px;">
        <el-form-item label="存储过程名">
          <el-input
            v-model="query.procName"
            placeholder="支持模糊查询"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>&nbsp;查询
          </el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        border
        stripe
        style="width: 100%"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="procName" label="存储过程名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="procDesc" label="功能说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="updateTarget" label="更新对象" min-width="200" show-overflow-tooltip />
        <el-table-column prop="tableCount" label="依赖表数" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="success">{{ row.tableCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tempTableCount" label="临时表数" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="warning">{{ row.tempTableCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="extractTime" label="提取时间" width="170" />
        <el-table-column label="操作" width="280" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="viewDetail(row)">
              <el-icon><View /></el-icon>&nbsp;依赖详情
            </el-button>
            <el-button size="small" type="success" link @click="viewLineage(row)">
              <el-icon><Connection /></el-icon>&nbsp;查看血缘
            </el-button>
            <el-button size="small" type="danger" link @click="handleDelete(row)">
              <el-icon><Delete /></el-icon>&nbsp;删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="margin-top: 15px; text-align: right;">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>
    </el-card>

    <!-- 解析失败清单抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      title="解析失败清单"
      size="72%"
      destroy-on-close
    >
      <div style="margin-bottom: 12px;">
        <el-form :inline="true">
          <el-form-item>
            <el-switch v-model="failQuery.onlyUnresolved" active-text="仅看未处理" @change="handleFailSearch" />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="failQuery.procName"
              placeholder="按存储过程名模糊搜索"
              clearable
              style="width: 240px"
              @keyup.enter="handleFailSearch"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleFailSearch">
              <el-icon><Search /></el-icon>&nbsp;查询
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="failData" v-loading="failLoading" border stripe max-height="560" style="width: 100%">
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
        <el-table-column prop="sqlPreview" label="SQL 预览" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-family: Consolas, monospace; font-size: 12px;">{{ row.sqlPreview }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column label="操作" width="240" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="info" link @click="openFailDetail(row)">
              <el-icon><View /></el-icon>&nbsp;详情
            </el-button>
            <el-button size="small" type="primary" link :loading="row._reparsing" @click="handleReparse(row)">
              重新解析
            </el-button>
            <el-button
              v-if="row.resolved === 0"
              size="small"
              type="success"
              link
              @click="handleResolve(row)"
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
          @size-change="fetchFailData"
          @current-change="fetchFailData"
        />
      </div>
    </el-drawer>

    <!-- 失败详情弹窗：完整原因 + 完整 SQL -->
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Search, View, Delete, Connection, Warning } from '@element-plus/icons-vue'
import { listProcs, deleteProc, listParseFails, resolveParseFail, reparseProc } from '../api/proc'

const router = useRouter()
const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  procName: ''
})

async function fetchData() {
  loading.value = true
  try {
    const res = await listProcs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      procName: query.procName
    })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    // 拦截器已处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchData()
}

function handleReset() {
  query.procName = ''
  query.pageNum = 1
  fetchData()
}

function viewDetail(row) {
  router.push(`/procs/${row.procName}`)
}

function viewLineage(row) {
  router.push(`/procs/${row.procName}/lineage`)
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除存储过程【${row.procName}】及其依赖关系吗?`,
      '删除确认',
      { type: 'warning' }
    )
    await deleteProc(row.procName)
    ElMessage.success('删除成功')
    fetchData()
    loadFailCount()
  } catch (e) {
    // 取消或失败
  }
}

function goUpload() {
  router.push('/')
}

// ===== 解析失败清单 =====
const drawerVisible = ref(false)
const failLoading = ref(false)
const failData = ref([])
const failTotal = ref(0)
const failCount = ref(0)
const failQuery = reactive({
  pageNum: 1,
  pageSize: 20,
  procName: '',
  onlyUnresolved: false
})

async function loadFailCount() {
  try {
    const res = await listParseFails({ pageNum: 1, pageSize: 1, onlyUnresolved: true })
    failCount.value = res.data.total || 0
  } catch (e) {
    failCount.value = 0
  }
}

function openDrawer() {
  drawerVisible.value = true
  failQuery.pageNum = 1
  fetchFailData()
}

async function fetchFailData() {
  failLoading.value = true
  try {
    const res = await listParseFails({
      pageNum: failQuery.pageNum,
      pageSize: failQuery.pageSize,
      procName: failQuery.procName || undefined,
      onlyUnresolved: failQuery.onlyUnresolved || undefined
    })
    failData.value = (res.data.records || []).map((r) => ({ ...r, _reparsing: false }))
    failTotal.value = res.data.total || 0
  } catch (e) {
    // 拦截器已处理
  } finally {
    failLoading.value = false
  }
}

function handleFailSearch() {
  failQuery.pageNum = 1
  fetchFailData()
}

async function handleResolve(row) {
  try {
    await resolveParseFail(row.id)
    ElMessage.success(`已标记 ${row.procName} 块${row.blockSeq} 为已处理`)
    fetchFailData()
    loadFailCount()
  } catch (e) {
    // 拦截器已处理
  }
}

// ===== 失败详情弹窗 =====
const failDetailVisible = ref(false)
const failDetail = ref(null)

function openFailDetail(row) {
  failDetail.value = JSON.parse(JSON.stringify(row))
  failDetailVisible.value = true
}

// 从失败原因中解析出错行号与标记，用于 SQL 高亮
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

async function handleReparse(row) {
  try {
    await ElMessageBox.confirm(
      `确认重新解析存储过程【${row.procName}】吗？将重读其源文件并覆盖现有依赖与血缘。`,
      '重新解析确认',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  row._reparsing = true
  try {
    await reparseProc(row.procName)
    ElMessage.success(`【${row.procName}】重新解析完成`)
    fetchFailData()
    loadFailCount()
    fetchData()
  } catch (e) {
    // 拦截器已弹出错误
  } finally {
    row._reparsing = false
  }
}

onMounted(() => {
  fetchData()
  loadFailCount()
})
</script>

<style scoped>
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
  background: rgba(249, 115, 22, 0.22);
  box-shadow: inset 2px 0 0 #f97316;
}
.sql-row .ln {
  flex: 0 0 44px;
  text-align: right;
  padding-right: 10px;
  color: #64748b;
  user-select: none;
  border-right: 1px solid #334155;
}
.sql-row .txt {
  flex: 1;
  color: #e2e8f0;
  padding-left: 10px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>