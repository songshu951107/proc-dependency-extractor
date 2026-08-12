<template>
  <div class="report-impact-view">
    <div class="filter-header">
      <ReportImpactFilter :loading="loading" @query="handleQuery" />
      <el-button type="warning" plain class="parse-entry-btn" @click="parseDialogVisible = true">
        报表解析
      </el-button>
    </div>

    <!-- 溯源模式：全屏图谱布局 -->
    <div v-if="lineageMode" class="lineage-container">
      <div v-if="lineageError" class="error-tip">{{ lineageError }}</div>
      <template v-else>
        <!-- 统计信息 -->
        <div class="stats-bar" v-if="lineageResult">
          <span>影响字段数 <b>{{ lineageResult.stats?.affectedFieldCount || 0 }}</b></span>
          <span>涉及 proc 数 <b>{{ lineageResult.stats?.affectedProcCount || 0 }}</b></span>
          <span>最大深度 <b>{{ lineageResult.stats?.maxDepthReached || 0 }}</b></span>
        </div>

        <!-- 筛选工具栏 -->
        <div class="graph-filter-bar" v-if="allNodesByDepth.length">
          <el-select
            v-model="filterSchemas"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="筛选 Schema"
            clearable
            size="small"
            style="width: 200px"
          >
            <el-option v-for="s in availableSchemas" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select
            v-model="filterTableKw"
            filterable
            clearable
            placeholder="输入或选择表名"
            size="small"
            style="width: 260px"
          >
            <el-option v-for="n in allNodesByDepth" :key="n.id" :label="n.label || n.id" :value="n.id" />
          </el-select>
          <el-switch v-model="hideUnmatched" active-text="隐藏未命中" size="small" />
          <el-button size="small" @click="clearFilter">清空</el-button>
          <span class="filter-tip" v-if="matchedNodeIds.size > 0">
            命中 {{ matchedNodeIds.size }} / 共 {{ allNodesByDepth.length }} 节点
          </span>
        </div>

        <el-row :gutter="10">
          <el-col :span="showDetail ? 18 : 24">
            <ImpactFlowGraph
              ref="graphRef"
              :initialNodes="graphInitialNodes"
              :initialEdges="graphInitialEdges"
              direction="UPSTREAM"
              :matchedNodeIds="matchedNodeIds"
              :preservedNodeIds="preservedNodeIds"
              :hideUnmatched="hideUnmatched"
              @nodeClick="handleNodeClick"
              @edgeClick="handleEdgeClick"
            />
          </el-col>
          <el-col :span="6" v-if="showDetail">
            <div class="detail-panel">
              <div class="detail-header">
                <span>{{ selectedNode ? '节点详情' : '边详情' }}</span>
                <el-button size="small" text @click="showDetail = false">关闭</el-button>
              </div>
              <el-descriptions v-if="selectedNode" :column="1" border size="small">
                <el-descriptions-item label="字段">{{ selectedNode.label }}</el-descriptions-item>
                <el-descriptions-item label="深度">{{ selectedNode.depth }}</el-descriptions-item>
                <el-descriptions-item label="所属表">{{ selectedNode.id?.split('.').slice(0,2).join('.') }}</el-descriptions-item>
              </el-descriptions>
              <el-descriptions v-else-if="selectedEdge" :column="1" border size="small">
                <el-descriptions-item label="来源字段">{{ selectedEdge.source }}</el-descriptions-item>
                <el-descriptions-item label="目标字段">{{ selectedEdge.target }}</el-descriptions-item>
                <el-descriptions-item label="存储过程">{{ selectedEdge.procName }}</el-descriptions-item>
                <el-descriptions-item label="来源表达式">{{ selectedEdge.sourceExpr }}</el-descriptions-item>
              </el-descriptions>
            </div>
          </el-col>
        </el-row>
      </template>
    </div>

    <!-- 非溯源模式：报表清单 + 字段映射详情 -->
    <el-row v-else-if="reports.length" :gutter="10">
      <el-col :span="8">
        <div class="section-title">报表清单</div>
        <ReportList :reports="reports" @select="handleSelectReport" />
      </el-col>
      <el-col :span="16">
        <div class="section-title">字段映射详情</div>
        <FieldMappingTable :bindings="currentBindings" />
      </el-col>
    </el-row>

    <!-- 报表解析弹窗 -->
    <el-dialog v-model="parseDialogVisible" title="报表解析" width="560px" @close="handleDialogClose">
      <el-tabs v-model="activeParseTab">
        <!-- 全部重新解析 -->
        <el-tab-pane label="全部重新解析" name="all">
          <div class="parse-tab-content">
            <p class="parse-tip">扫描默认 reports 目录（d:\HB\work_zuoye\reports）并重新解析所有报表文件。</p>
            <el-button type="primary" :loading="parseAllLoading" @click="handleParseAll">
              开始解析
            </el-button>
          </div>
        </el-tab-pane>

        <!-- 单文件上传 -->
        <el-tab-pane label="单文件解析" name="upload">
          <div class="parse-tab-content">
            <p class="parse-tip">选择本地 .cpt / .frm / .xml 文件，上传后自动解析。文件会保存到 reports 目录。</p>
            <el-upload
              ref="uploadRef"
              :auto-upload="false"
              :limit="1"
              :on-exceed="handleExceed"
              :on-change="handleFileChange"
              accept=".cpt,.frm,.xml"
              drag>
              <el-icon class="el-icon--upload"><upload-filled /></el-icon>
              <div class="el-upload__text">拖拽文件到此处，或<em>点击选择</em></div>
              <template #tip>
                <div class="el-upload__tip">仅支持 .cpt / .frm / .xml 格式</div>
              </template>
            </el-upload>
            <el-button type="primary" :loading="parseFileLoading" :disabled="!selectedFile"
                       style="margin-top: 12px" @click="handleParseUpload">
              上传并解析
            </el-button>
          </div>
        </el-tab-pane>

        <!-- 目录批量解析 -->
        <el-tab-pane label="批量解析" name="batch">
          <div class="parse-tab-content">
            <p class="parse-tip">输入目录路径，批量解析该目录下所有报表文件。</p>
            <el-input v-model="parseDirPath" placeholder="如 d:\HB\work_zuoye\reports" style="width: 100%" clearable />
            <el-button type="primary" :loading="parseBatchLoading" style="margin-top: 12px"
                       @click="handleParseBatch">
              开始批量解析
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="parseDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import ReportImpactFilter from '../components/report/ReportImpactFilter.vue'
import ReportList from '../components/report/ReportList.vue'
import FieldMappingTable from '../components/report/FieldMappingTable.vue'
import ImpactFlowGraph from '../components/impact/ImpactFlowGraph.vue'
import {
  findReportsByTable, findReportsByField, findByHeaderText,
  getReportDetail, parseReportUpload, parseReportBatch, reParseAllReports,
  traceReportLineage
} from '../api/report'

const loading = ref(false)
const reports = ref([])
const currentBindings = ref([])
// 当前查询的目标表全名（小写 schema.table），用于过滤只显示命中表的数据集
const targetTable = ref('')
// 口径查询的全部匹配绑定，用于点选报表时按报表过滤
const allMatchedBindings = ref([])

// ---- 溯源模式状态 ----
const lineageMode = ref(false)
const lineageResult = ref(null)
const lineageError = ref('')
const graphRef = ref(null)
const selectedNode = ref(null)
const selectedEdge = ref(null)
const showDetail = ref(false)
// 图谱全量节点/边
const allNodesByDepth = ref([])
const allEdgesList = ref([])
const graphInitialNodes = ref([])
const graphInitialEdges = ref([])
const DEFAULT_VISIBLE_DEPTH = 3

// 图谱筛选状态
const filterSchemas = ref([])
const filterTableKw = ref('')
const hideUnmatched = ref(false)

// ---- 报表解析弹窗 ----
const parseDialogVisible = ref(false)
const activeParseTab = ref('all')
const parseAllLoading = ref(false)
const parseFileLoading = ref(false)
const parseBatchLoading = ref(false)
const parseDirPath = ref('')
const selectedFile = ref(null)
const uploadRef = ref(null)

// 解析成功后清空当前查询结果，避免展示过期数据
function clearResultsAfterParse() {
  reports.value = []
  currentBindings.value = []
  allMatchedBindings.value = []
  lineageMode.value = false
  lineageResult.value = null
  lineageError.value = ''
  allNodesByDepth.value = []
  allEdgesList.value = []
}

async function handleParseAll() {
  parseAllLoading.value = true
  try {
    const res = await reParseAllReports()
    ElMessage.success(`解析完成，共 ${res.data?.length || 0} 个报表`)
    clearResultsAfterParse()
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.message || ''))
  } finally {
    parseAllLoading.value = false
  }
}

function handleFileChange(file) {
  selectedFile.value = file.raw
}

function handleExceed(files) {
  // limit=1 时替换已有文件
  uploadRef.value.clearFiles()
  const file = files[0]
  uploadRef.value.handleStart(file)
  selectedFile.value = file
}

async function handleParseUpload() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  parseFileLoading.value = true
  try {
    const res = await parseReportUpload(selectedFile.value)
    ElMessage.success('解析成功: ' + (res.data?.name || selectedFile.value.name))
    clearResultsAfterParse()
    parseDialogVisible.value = false
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.message || ''))
  } finally {
    parseFileLoading.value = false
  }
}

async function handleParseBatch() {
  if (!parseDirPath.value.trim()) {
    ElMessage.warning('请输入目录路径')
    return
  }
  parseBatchLoading.value = true
  try {
    const res = await parseReportBatch(parseDirPath.value.trim())
    ElMessage.success(`批量解析完成，共 ${res.data?.length || 0} 个报表`)
    clearResultsAfterParse()
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.message || ''))
  } finally {
    parseBatchLoading.value = false
  }
}

function handleDialogClose() {
  // 关闭弹窗时重置上传组件状态
  selectedFile.value = null
  if (uploadRef.value) uploadRef.value.clearFiles()
  parseDirPath.value = ''
}

// ---- 影响分析查询 ----
async function handleQuery(form) {
  loading.value = true
  // 重置所有结果状态
  reports.value = []
  currentBindings.value = []
  allMatchedBindings.value = []
  lineageMode.value = false
  lineageResult.value = null
  lineageError.value = ''
  allNodesByDepth.value = []
  allEdgesList.value = []
  filterSchemas.value = []
  filterTableKw.value = ''
  hideUnmatched.value = false
  showDetail.value = false

  targetTable.value = (form.queryType === 'table' || form.queryType === 'field') && form.schema && form.table
    ? (form.schema + '.' + form.table).toLowerCase()
    : ''

  try {
    if (form.queryType === 'table') {
      const res = await findReportsByTable(form.schema, form.table)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'field') {
      const res = await findReportsByField(form.schema, form.table, form.field)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'caliber') {
      // 口径查询：按列头汉字反查所有相关报表及字段映射
      const res = await findByHeaderText(form.headerText)
      reports.value = res.data?.reports || []
      allMatchedBindings.value = res.data?.bindings || []
      currentBindings.value = allMatchedBindings.value
    } else if (form.queryType === 'trace') {
      // 溯源查询：报表+表头列 → 上游字段级链路图谱
      lineageMode.value = true
      const res = await traceReportLineage(form.reportFile, form.headerText)
      if (res.code !== 200 && res.code !== 0) {
        lineageError.value = res.message || '查询失败'
      } else {
        lineageResult.value = res.data
        buildLineageGraph(res.data)
      }
    }
  } catch (e) {
    if (lineageMode.value) {
      lineageError.value = e.message || '查询失败'
    } else {
      ElMessage.error('查询失败: ' + (e.message || ''))
    }
  } finally {
    loading.value = false
  }
}

// ---- 溯源图谱构建（复用 ImpactAnalysis 的 buildWithoutTemp 逻辑）----
function buildLineageGraph(data) {
  const sourceTable = data?.sourceTable
  const sourceLayer = data?.sourceLayer
  const impactedByDepth = data?.impactedByDepth || {}
  const { nodes, edges } = buildWithoutTemp(sourceTable, sourceLayer, impactedByDepth)
  allNodesByDepth.value = nodes
  allEdgesList.value = edges
  // 默认展示 depth <= 3 的节点
  const visibleIds = nodes.filter(n => n.depth <= DEFAULT_VISIBLE_DEPTH).map(n => n.id)
  graphInitialNodes.value = nodes.filter(n => visibleIds.includes(n.id))
  graphInitialEdges.value = edges.filter(e => visibleIds.includes(e.source) && visibleIds.includes(e.target))
}

// 构建不含临时表的节点和边（穿透临时表）
function buildWithoutTemp(sourceTable, sourceLayer, impactedByDepth) {
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push({ name: t.name, temp: t.temp })
      }
    }
  }
  const tableInfoMap = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.name] = {
        layer: t.layer, temp: t.temp, scriptFile: t.scriptFile,
        shortName: t.shortName, columns: t.columns, matchedColumns: t.matchedColumns
      }
    }
  }
  const depthMap = { [sourceTable]: 0 }
  const impactSourceMap = {}
  const queue = [sourceTable]
  while (queue.length) {
    const current = queue.shift()
    const currentDepth = depthMap[current]
    const neighbors = adjacency[current] || []
    for (const neighbor of neighbors) {
      if (!neighbor.temp && !(neighbor.name in depthMap)) {
        depthMap[neighbor.name] = currentDepth + 1
        impactSourceMap[neighbor.name] = current
        queue.push(neighbor.name)
      } else if (neighbor.temp) {
        const tempQueue = [neighbor.name]
        const visited = new Set([neighbor.name])
        while (tempQueue.length) {
          const tempNode = tempQueue.shift()
          const tempNeighbors = adjacency[tempNode] || []
          for (const tn of tempNeighbors) {
            if (visited.has(tn.name)) continue
            visited.add(tn.name)
            if (!tn.temp && !(tn.name in depthMap)) {
              depthMap[tn.name] = currentDepth + 1
              impactSourceMap[tn.name] = current
              queue.push(tn.name)
            } else if (tn.temp) {
              tempQueue.push(tn.name)
            }
          }
        }
      }
    }
  }
  // 构建节点
  const nodes = []
  const sourceInfo = tableInfoMap[sourceTable] || {}
  nodes.push({
    id: sourceTable, label: sourceInfo.shortName || sourceTable.split('.')[1] || sourceTable,
    layer: sourceLayer || sourceInfo.layer, depth: 0, isTemp: false,
    scriptFile: sourceInfo.scriptFile, columns: sourceInfo.columns, matchedColumns: sourceInfo.matchedColumns
  })
  for (const name in depthMap) {
    if (name === sourceTable) continue
    const info = tableInfoMap[name] || {}
    nodes.push({
      id: name, label: info.shortName || name.split('.')[1] || name,
      layer: info.layer, depth: depthMap[name], isTemp: info.temp,
      scriptFile: info.scriptFile, columns: info.columns, matchedColumns: info.matchedColumns
    })
  }
  // 构建边
  const edges = []
  for (const name in impactSourceMap) {
    edges.push({ source: impactSourceMap[name], target: name })
  }
  return { nodes, edges }
}

// ---- 图谱交互 ----
function handleNodeClick(node) {
  selectedNode.value = node
  selectedEdge.value = null
  showDetail.value = true
}
function handleEdgeClick(edge) {
  selectedEdge.value = edge
  selectedNode.value = null
  showDetail.value = true
}

// ---- 图谱筛选 ----
const availableSchemas = computed(() => {
  const set = new Set()
  allNodesByDepth.value.forEach(n => {
    if (n.layer && n.layer !== 'UNKNOWN') set.add(n.layer)
  })
  return Array.from(set)
})

const matchedNodeIds = computed(() => {
  if (!filterSchemas.value.length && !filterTableKw.value) return new Set()
  const sourceTable = lineageResult.value?.sourceTable
  const schemaSet = new Set(filterSchemas.value)
  const kw = filterTableKw.value ? filterTableKw.value.toUpperCase() : ''
  const matched = new Set()
  allNodesByDepth.value.forEach(n => {
    if (n.id === sourceTable) { matched.add(n.id); return }
    let schemaOk = true
    if (schemaSet.size > 0) schemaOk = schemaSet.has(n.layer)
    let tableOk = true
    if (kw) {
      const label = (n.label || n.id || '').toUpperCase()
      const id = (n.id || '').toUpperCase()
      tableOk = label.includes(kw) || id.includes(kw)
    }
    if (schemaOk && tableOk) matched.add(n.id)
  })
  return matched
})

const preservedNodeIds = computed(() => {
  if (!hideUnmatched.value) return null
  const matched = matchedNodeIds.value
  if (matched.size === 0) return null
  const parentMap = new Map()
  allEdgesList.value.forEach(e => {
    if (!parentMap.has(e.target)) parentMap.set(e.target, [])
    parentMap.get(e.target).push(e.source)
  })
  const preserved = new Set(matched)
  const queue = Array.from(matched)
  while (queue.length) {
    const node = queue.shift()
    const parents = parentMap.get(node) || []
    for (const p of parents) {
      if (!preserved.has(p)) { preserved.add(p); queue.push(p) }
    }
  }
  return preserved
})

function clearFilter() {
  filterSchemas.value = []
  filterTableKw.value = ''
  hideUnmatched.value = false
}

async function handleSelectReport(report) {
  // 口径查询：从已匹配的绑定中过滤该报表
  if (allMatchedBindings.value.length) {
    currentBindings.value = allMatchedBindings.value.filter(b => b.reportFile === report.file)
    return
  }
  try {
    const res = await getReportDetail(report.file)
    const all = res.data?.bindings || []
    // 只保留命中所选表的数据集绑定，过滤掉该报表其它无关数据集
    currentBindings.value = targetTable.value
      ? all.filter(b => b.sourceTable === targetTable.value)
      : all
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}
</script>

<style scoped>
.report-impact-view { padding: 10px; }
.filter-header {
  position: relative;
  margin-bottom: 10px;
}
.parse-entry-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
}
.section-title { font-weight: bold; margin-bottom: 8px; padding: 4px 8px; background: #f5f7fa; }
.parse-tab-content { padding: 10px 0; }
.parse-tip { color: #909399; margin: 0 0 12px; font-size: 13px; line-height: 1.5; }
.lineage-container { background: #fff; padding: 10px; border-radius: 4px; }
.stats-bar {
  display: flex; gap: 24px; padding: 8px 12px;
  background: #f5f7fa; border-radius: 4px; margin-bottom: 10px;
  font-size: 14px;
}
.stats-bar b { color: #1890ff; margin-left: 4px; }
.graph-filter-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px; background: #fff;
  border: 1px solid #e8e8e8; border-radius: 4px;
  margin-bottom: 8px; flex-wrap: wrap;
}
.filter-tip { font-size: 12px; color: #1890ff; }
.detail-panel {
  border: 1px solid #e8e8e8; border-radius: 4px;
  background: #fff; height: 500px; overflow-y: auto;
}
.detail-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 12px; background: #f5f7fa;
  border-bottom: 1px solid #e8e8e8; font-weight: bold;
}
.error-tip {
  padding: 40px; text-align: center; color: #f56c6c; font-size: 14px;
}
</style>
