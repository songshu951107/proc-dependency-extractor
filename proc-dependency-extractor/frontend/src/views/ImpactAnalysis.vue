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

    <ImpactStats :stats="result?.stats" />

    <el-row :gutter="10">
      <el-col :span="18">
        <ImpactFlowGraph
          ref="graphRef"
          :initialNodes="graphInitialNodes"
          :initialEdges="graphInitialEdges"
          :direction="lastRequest?.direction || 'DOWNSTREAM'"
          @nodeClick="handleNodeClick"
          @edgeClick="handleEdgeClick"
        />

        <div class="propagation-section" v-if="result?.impactedByDepth">
          <div class="section-header">
            <span class="section-title">影响传播树</span>
            <el-switch
              v-model="collapseTempTables"
              active-text="折叠临时表"
              size="small"
            />
          </div>
          <ImpactPropagationTree
            :sourceTable="result.sourceTable"
            :sourceLayer="result.sourceLayer"
            :impactedByDepth="result.impactedByDepth"
            :collapsedTempTables="collapseTempTables"
            @viewScript="handleViewScript"
          />
        </div>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <template #header>详情</template>
          <ImpactNodeDetail
            v-if="selectedNode"
            :node="selectedNode"
            @expand="handleExpand"
            @collapse="handleCollapse"
          />
          <ImpactEdgeDetail
            v-else-if="selectedEdge"
            :edge="selectedEdge"
          />
          <el-empty v-else description="点击节点或边查看详情" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <ScriptSnippetModal
      v-if="scriptModalVisible"
      :visible="scriptModalVisible"
      :scriptFile="scriptModalData.scriptFile"
      :impactSource="scriptModalData.impactSource"
      :targetTable="scriptModalData.name"
      @close="scriptModalVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ImpactFilter from '@/components/impact/ImpactFilter.vue'
import ImpactStats from '@/components/impact/ImpactStats.vue'
import ImpactFlowGraph from '@/components/impact/ImpactFlowGraph.vue'
import ImpactNodeDetail from '@/components/impact/ImpactNodeDetail.vue'
import ImpactEdgeDetail from '@/components/impact/ImpactEdgeDetail.vue'
import ImpactPropagationTree from '@/components/impact/ImpactPropagationTree.vue'
import ScriptSnippetModal from '@/components/impact/ScriptSnippetModal.vue'
import { analyze } from '@/api/impact'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const result = ref(null)
const neo4jError = ref('')
const lastRequest = ref(null)
const graphRef = ref(null)
const selectedNode = ref(null)
const selectedEdge = ref(null)
const collapseTempTables = ref(false)
const scriptModalVisible = ref(false)
const scriptModalData = ref({ name: '', scriptFile: '', impactSource: '' })

const graphInitialNodes = ref([])
const graphInitialEdges = ref([])

// 全量非临时表节点（含 depth）和边，用于前端逐级展开
const allNodesByDepth = ref([])
const allEdgesList = ref([])
// 当前已展开可见的节点 id 列表
const visibleNodeIds = ref([])

const DEFAULT_VISIBLE_DEPTH = 3

const isFieldLevel = computed(() => {
  return lastRequest.value?.fieldName && lastRequest.value.fieldName !== ''
})

const handleAnalyze = async (request) => {
  loading.value = true
  collapseTempTables.value = false
  neo4jError.value = ''
  lastRequest.value = request
  try {
    const res = await analyze(request)
    result.value = res.data

    const sourceTable = res.data?.sourceTable
    const sourceLayer = res.data?.sourceLayer
    const impactedByDepth = res.data?.impactedByDepth || {}

    // 构建不含临时表的节点和边（穿透临时表）
    const { nodes, edges } = buildWithoutTemp(sourceTable, sourceLayer, impactedByDepth)
    allNodesByDepth.value = nodes
    allEdgesList.value = edges

    // 默认展示 depth <= 3 的节点
    visibleNodeIds.value = nodes
      .filter(n => n.depth <= DEFAULT_VISIBLE_DEPTH)
      .map(n => n.id)
    updateVisibleGraph()

    // 节点超过 50 个时提示
    if (nodes.length > 50) {
      ElMessage.warning(
        `当前影响链路包含 ${nodes.length} 个非临时表节点，可点击节点逐级展开`
      )
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
}

// 构建不含临时表的节点和边，临时表被穿透（其上下游非临时表直接连接）
function buildWithoutTemp(sourceTable, sourceLayer, impactedByDepth) {
  // 邻接表（含临时表）
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push({ name: t.name, temp: t.temp })
      }
    }
  }

  // 表信息映射
  const tableInfoMap = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.name] = {
        layer: t.layer,
        temp: t.temp,
        scriptFile: t.scriptFile,
        shortName: t.shortName,
        columns: t.columns,
        matchedColumns: t.matchedColumns
      }
    }
  }

  // BFS 遍历，跳过临时表，计算非临时表 depth 和 impactSource
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
        // 临时表：穿透到它的下游非临时表
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

  const nodes = []
  for (const [table, depth] of Object.entries(depthMap)) {
    const info = tableInfoMap[table] || {}
    nodes.push({
      id: table,
      label: depth === 0 ? table : (info.shortName || table),
      layer: depth === 0 ? sourceLayer : (info.layer || 'UNKNOWN'),
      isTemp: false,
      depth,
      impactSource: impactSourceMap[table],
      scriptFile: info.scriptFile,
      matchedColumns: info.matchedColumns || []
    })
  }

  const edges = []
  for (const [table, src] of Object.entries(impactSourceMap)) {
    edges.push({ source: src, target: table })
  }

  return { nodes, edges }
}

function updateVisibleGraph() {
  const visibleSet = new Set(visibleNodeIds.value)
  const visibleNodes = allNodesByDepth.value.filter(n => visibleSet.has(n.id))
  const visibleEdges = allEdgesList.value.filter(e =>
    visibleSet.has(e.source) && visibleSet.has(e.target))
  graphInitialNodes.value = visibleNodes
  graphInitialEdges.value = visibleEdges
}

const handleNodeClick = (node) => {
  selectedNode.value = node
  selectedEdge.value = null
}

const handleEdgeClick = (edge) => {
  selectedEdge.value = edge
  selectedNode.value = null
}

const handleExpand = async (node) => {
  const nodeId = node.id || node.data?.id
  if (!nodeId) return

  // 找到该节点的直接下游非临时表节点
  const downstreamNodes = allNodesByDepth.value.filter(n => n.impactSource === nodeId)
  if (downstreamNodes.length === 0) {
    ElMessage.info(`表 "${nodeId}" 没有更多下游依赖`)
    return
  }

  const currentVisible = new Set(visibleNodeIds.value)
  let added = false
  downstreamNodes.forEach(n => {
    if (!currentVisible.has(n.id)) {
      currentVisible.add(n.id)
      added = true
    }
  })

  if (added) {
    visibleNodeIds.value = Array.from(currentVisible)
    updateVisibleGraph()
  } else {
    ElMessage.info('该节点下游已全部展示')
  }
}

const handleCollapse = (node) => {
  ElMessage.info('折叠功能待实现，可重新分析查看')
}

const handleViewScript = ({ name, scriptFile, impactSource }) => {
  if (!scriptFile) {
    ElMessage.warning('该表无关联脚本')
    return
  }
  scriptModalData.value = { name, scriptFile, impactSource }
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
</style>
