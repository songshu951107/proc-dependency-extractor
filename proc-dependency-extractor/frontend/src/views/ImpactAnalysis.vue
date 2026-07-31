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

    // 构建初始图数据：起点 + 第 1 跳下游
    const sourceTable = res.data?.sourceTable
    const sourceLayer = res.data?.sourceLayer
    const impactedByDepth = res.data?.impactedByDepth || {}
    const firstLevel = impactedByDepth[1] || []

    const initialNodes = [{
      id: sourceTable,
      label: sourceTable,
      layer: sourceLayer,
      isTemp: false,
      matchedColumns: []
    }]
    firstLevel.forEach(t => {
      initialNodes.push({
        id: t.name,
        label: t.shortName || t.name,
        layer: t.layer,
        isTemp: t.temp,
        matchedColumns: t.matchedColumns || []
      })
    })

    const initialEdges = []
    firstLevel.forEach(t => {
      if (t.impactSource) {
        initialEdges.push({
          source: t.impactSource,
          target: t.name
        })
      }
    })

    graphInitialNodes.value = initialNodes
    graphInitialEdges.value = initialEdges

    // 节点超过 50 个时提示折叠临时表
    const totalNodes = Object.values(impactedByDepth)
      .reduce((sum, list) => sum + list.length, 0) + 1
    if (totalNodes > 50) {
      ElMessage.warning(
        `当前影响链路包含 ${totalNodes} 个节点，建议折叠临时表以提升可读性`
      )
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
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
  if (graphRef.value) {
    await graphRef.value.expandNode(node.id || node.data?.id)
  }
}

const handleCollapse = (node) => {
  // Vue Flow 的折叠逻辑较复杂，暂用重新渲染实现
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
