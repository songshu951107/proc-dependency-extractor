<template>
  <div class="impact-analysis">
    <ImpactFilter :loading="loading" @analyze="handleAnalyze" />

    <el-alert
      v-if="neo4jError"
      title="Neo4j 服务不可用"
      type="error"
      :description="neo4jError"
      show-icon
      style="margin-bottom: 10px"
    >
      <el-button size="small" @click="handleAnalyze(lastRequest)">重试</el-button>
    </el-alert>

    <ImpactStats :stats="displayStats" :upstream="isUpstream" @statClick="openStatsDetail" />

    <el-alert
      v-if="result && !impactRows.length"
      title="未分析到受影响对象"
      type="info"
      :closable="false"
      style="margin-bottom: 10px"
    />

    <ImpactResultTabs
      v-if="impactRows.length"
      :rows="impactRows"
      :isFieldLevel="isFieldLevel"
      :upstream="isUpstream"
      :exportFileName="exportFileName"
      @viewScript="handleRowViewScript"
      @viewPath="pathDialogVisible = true; pathDialogRow = $event"
    />

    <div class="propagation-section" v-if="isUpstream && result?.rowFilters?.length">
      <div class="section-header">
        <span class="section-title">关联 / 过滤条件（行血缘）</span>
        <span class="report-tip">目标表行集受以下条件控制；条件字段不参与列值计算</span>
      </div>
      <el-table :data="result.rowFilters" border size="small" style="width: 100%">
        <el-table-column prop="fieldName" label="条件字段" min-width="140" />
        <el-table-column label="角色" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'join_condition' ? 'warning' : 'danger'" size="small">
              {{ row.role === 'join_condition' ? 'ON 条件' : 'WHERE 条件' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clause" label="条件原文" min-width="260" show-overflow-tooltip />
        <el-table-column prop="procName" label="存储过程" min-width="160" show-overflow-tooltip />
        <el-table-column prop="sqlBlockSeq" label="SQL块" width="80" align="center" />
      </el-table>
    </div>

    <div class="propagation-section" v-if="result?.reportImpacts?.length">
      <div class="section-header">
        <span class="section-title">报表影响（跨链分析）</span>
        <span class="report-tip">沿 READS 血缘追溯上下游表，查这些表被哪些报表引用</span>
      </div>
      <el-table :data="reportImpactRows" border size="small" style="width: 100%">
        <el-table-column prop="depth" label="深度" width="70" align="center" />
        <el-table-column prop="table" label="血缘链路上的表" min-width="220" show-overflow-tooltip />
        <el-table-column prop="reportFile" label="报表文件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="reportName" label="报表名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="datasetName" label="数据集" width="90" />
        <el-table-column prop="connection" label="数据库" width="100" />
      </el-table>
    </div>

    <ImpactPathDialog
      :visible="pathDialogVisible"
      :row="pathDialogRow"
      :nodes="pathDialogNodes"
      :edges="pathDialogEdges"
      :sourceId="sourceNodeId"
      @update:visible="pathDialogVisible = $event"
      @viewScript="handlePathEdgeViewScript"
    />

    <ScriptSnippetModal
      v-if="scriptModalVisible"
      :visible="scriptModalVisible"
      :scriptFile="scriptModalData.scriptFile"
      :impactSource="scriptModalData.impactSource"
      :targetTable="scriptModalData.name"
      @close="scriptModalVisible = false"
    />

    <ImpactStatsDetailDialog
      :visible="statsDialogVisible"
      :type="statsDialogType"
      :rows="impactRows"
      :reportRows="reportImpactRows"
      :isFieldLevel="isFieldLevel"
      :upstream="isUpstream"
      @update:visible="statsDialogVisible = $event"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ImpactFilter from '@/components/impact/ImpactFilter.vue'
import ImpactStats from '@/components/impact/ImpactStats.vue'
import ImpactResultTabs from '@/components/impact/ImpactResultTabs.vue'
import ImpactPathDialog from '@/components/impact/ImpactPathDialog.vue'
import ImpactStatsDetailDialog from '@/components/impact/ImpactStatsDetailDialog.vue'
import ScriptSnippetModal from '@/components/impact/ScriptSnippetModal.vue'
import { analyze } from '@/api/impact'
import { buildFieldImpactRows, buildFieldSourceRows, buildTableImpactData } from '@/components/impact/impactListUtils'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const result = ref(null)
const neo4jError = ref('')
const lastRequest = ref(null)
const scriptModalVisible = ref(false)
const scriptModalData = ref({ name: '', scriptFile: '', impactSource: '' })

const impactRows = ref([])
const pathDialogVisible = ref(false)
const pathDialogRow = ref(null)
const pathDialogNodes = ref([])
const pathDialogEdges = ref([])
const sourceNodeId = ref('')
const isUpstream = ref(false)
const exportFileName = ref('影响分析')
const statsDialogVisible = ref(false)
const statsDialogType = ref('tables')

// 统计卡点击 → 弹出对应维度明细（表/字段/proc/报表）
function openStatsDetail(type) {
  statsDialogType.value = type
  statsDialogVisible.value = true
}

const isFieldLevel = computed(() => {
  return lastRequest.value?.fieldName && lastRequest.value.fieldName !== ''
})

// 统计卡与表格同口径：从清单行（单一事实来源）推导，避免后端 stats
// （原始 BFS 深度、节点计数）与表格（临时表穿透层级、行去重）数字不一致。
// 表级分析无字段维度，affectedFieldCount 置 null 由统计卡隐藏；报表数沿用后端。
const displayStats = computed(() => {
  const backend = result.value?.stats || {}
  const rows = impactRows.value || []
  const tables = new Set()
  const fields = new Set()
  const procs = new Set()
  let maxLevel = 0
  for (const r of rows) {
    if (!r.temp) tables.add(r.table)
    if (r.targetId) fields.add(r.targetId)
    if (r.procName && r.procName !== '-') procs.add(r.procName)
    if (typeof r.level === 'number' && r.level > maxLevel) maxLevel = r.level
  }
  return {
    ...backend,
    affectedTableCount: tables.size,
    affectedFieldCount: isFieldLevel.value ? fields.size : null,
    affectedProcCount: procs.size,
    maxDepthReached: maxLevel
  }
})

// 报表影响行：将 reportImpacts 嵌套结构展平为表格行（每个报表一行）
const reportImpactRows = computed(() => {
  const list = result.value?.reportImpacts || []
  const rows = []
  for (const ri of list) {
    for (const ref of ri.reports || []) {
      rows.push({
        depth: ri.depth,
        table: ri.table,
        reportFile: ref.file,
        reportName: ref.name,
        datasetName: ref.datasetName,
        connection: ref.connection
      })
    }
  }
  return rows
})

const handleAnalyze = async (request) => {
  loading.value = true
  neo4jError.value = ''
  lastRequest.value = request
  pathDialogVisible.value = false
  try {
    const res = await analyze(request)
    result.value = res.data
    const fieldLevel = !!(request.fieldName && request.fieldName !== '')

    if (fieldLevel) {
      const nodes = res.data?.nodes || []
      const edges = res.data?.edges || []
      const startId = `${request.schema}.${request.tableShortName}.${request.fieldName}`.toLowerCase()
      sourceNodeId.value = nodes.some(n => n.id === startId) ? startId
        : (nodes.find(n => n.depth === 0 && n.nodeLevel === 'FIELD')?.id || startId)
      // UPSTREAM：行 = 来源字段清单（反向传播推导）；BOTH 暂按下游清单展示
      isUpstream.value = (request.direction || 'DOWNSTREAM') === 'UPSTREAM'
      impactRows.value = isUpstream.value
        ? buildFieldSourceRows(nodes, edges, sourceNodeId.value)
        : buildFieldImpactRows(nodes, edges, sourceNodeId.value)
      pathDialogNodes.value = nodes
      pathDialogEdges.value = edges
      exportFileName.value = `影响分析_${request.schema}.${request.tableShortName}.${request.fieldName}_${request.direction || 'DOWNSTREAM'}`
    } else {
      const built = buildTableImpactData(res.data?.sourceTable, res.data?.impactedByDepth || {}, res.data?.sourceLayer)
      sourceNodeId.value = res.data?.sourceTable || ''
      isUpstream.value = false
      impactRows.value = built.rows
      pathDialogNodes.value = built.nodes
      pathDialogEdges.value = built.edges
      exportFileName.value = `影响分析_${res.data?.sourceTable || ''}_${request.direction || 'DOWNSTREAM'}`
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
}

// 分析源表（字段级取 col_key 前两段），作为脚本片段弹窗的上游影响来源
const sourceTableLabel = computed(() => {
  const id = sourceNodeId.value || ''
  const parts = id.split('.')
  return parts.length >= 3 ? `${parts[0]}.${parts[1]}` : id
})

const handleRowViewScript = (row) => {
  if (!row.procName || row.procName === '-') {
    ElMessage.warning('该表无关联脚本')
    return
  }
  scriptModalData.value = {
    name: row.table,
    scriptFile: row.procName,
    impactSource: sourceTableLabel.value
  }
  scriptModalVisible.value = true
}

const handlePathEdgeViewScript = (edge) => {
  scriptModalData.value = {
    name: edge.target,
    scriptFile: edge.procName,
    impactSource: edge.source
  }
  scriptModalVisible.value = true
}
</script>

<style scoped>
.impact-analysis {
  padding: 10px;
}
.propagation-section {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 16px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f1f1f;
}
.report-tip {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}
</style>
