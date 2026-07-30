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
        <ImpactGraph
          ref="graphRef"
          :nodes="renderNodes"
          :edges="renderEdges"
          :isFieldLevel="isFieldLevel"
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
      :procName="scriptModalData.procName"
      :impactSource="scriptModalData.impactSource"
      :targetTable="scriptModalData.table"
      @close="scriptModalVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ImpactFilter from '@/components/impact/ImpactFilter.vue'
import ImpactStats from '@/components/impact/ImpactStats.vue'
import ImpactGraph from '@/components/impact/ImpactGraph.vue'
import ImpactNodeDetail from '@/components/impact/ImpactNodeDetail.vue'
import ImpactEdgeDetail from '@/components/impact/ImpactEdgeDetail.vue'
import ImpactPropagationTree from '@/components/impact/ImpactPropagationTree.vue'
import ScriptSnippetModal from '@/components/impact/ScriptSnippetModal.vue'
import { analyze, expand } from '@/api/impact'
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
const scriptModalData = ref({ table: '', procName: '', impactSource: '' })

// 完整缓存（analyze 返回的全部节点/边）
const allNodes = ref([])
const allEdges = ref([])

// 实际渲染的节点/边（默认仅第 1 层）
const renderNodes = ref([])
const renderEdges = ref([])

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
    allNodes.value = res.data?.nodes || []
    allEdges.value = res.data?.edges || []

    // 默认仅渲染第 1 层（起点 + 直接相邻节点）
    const startNode = allNodes.value.find(n => n.depth === 0 || n.id === getStartId(request))
    if (startNode) {
      renderNodes.value = [startNode]
      renderEdges.value = []
      // 找直接相邻节点
      const directEdges = allEdges.value.filter(e =>
        e.source === startNode.id || e.target === startNode.id)
      const directNodeIds = new Set([startNode.id])
      directEdges.forEach(e => {
        directNodeIds.add(e.source)
        directNodeIds.add(e.target)
      })
      renderNodes.value = allNodes.value.filter(n => directNodeIds.has(n.id))
      renderEdges.value = directEdges
    } else {
      renderNodes.value = allNodes.value
      renderEdges.value = allEdges.value
    }

    // 节点超过 50 个时提示折叠临时表
    if (allNodes.value.length > 50) {
      ElMessage.warning(
        `当前影响链路包含 ${allNodes.value.length} 个节点，建议折叠临时表以提升可读性`
      )
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
}

const getStartId = (request) => {
  const schema = request.schema || ''
  const table = request.tableShortName || ''
  const base = schema ? `${schema}.${table}` : table
  if (request.fieldName) {
    return `${base}.${request.fieldName.toUpperCase()}`
  }
  return base.toUpperCase()
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
  // 先查缓存（根据方向匹配边：UPSTREAM 匹配 target，DOWNSTREAM 匹配 source，BOTH 两者皆可）
  const direction = lastRequest.value?.direction || 'DOWNSTREAM'
  const cachedEdges = allEdges.value.filter(e =>
    direction === 'UPSTREAM' ? e.target === node.id :
    direction === 'DOWNSTREAM' ? e.source === node.id :
    e.source === node.id || e.target === node.id
  )
  if (cachedEdges.length > 0) {
    const newNodeIds = new Set(renderNodes.value.map(n => n.id))
    cachedEdges.forEach(e => { newNodeIds.add(e.source); newNodeIds.add(e.target) })
    renderNodes.value = allNodes.value.filter(n => newNodeIds.has(n.id))
    renderEdges.value = [...renderEdges.value, ...cachedEdges]
    return
  }

  // 缓存未命中，调 expand
  try {
    const res = await expand({
      nodeId: node.id,
      direction: lastRequest.value?.direction || 'DOWNSTREAM',
      depth: 1
    })
    const newNodes = res.data?.nodes || []
    const newEdges = res.data?.edges || []
    allNodes.value = [...allNodes.value, ...newNodes]
    allEdges.value = [...allEdges.value, ...newEdges]
    renderNodes.value = [...renderNodes.value, ...newNodes]
    renderEdges.value = [...renderEdges.value, ...newEdges]
  } catch (e) {
    ElMessage.error('展开失败')
  }
}

const handleCollapse = (node) => {
  // 前端本地移除下游节点
  if (graphRef.value) {
    graphRef.value.removeDownstream(node.id)
  }
}

const handleViewScript = ({ table, procName, impactSource }) => {
  if (!procName) {
    ElMessage.warning('该表无关联存储过程')
    return
  }
  scriptModalData.value = { table, procName, impactSource }
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
