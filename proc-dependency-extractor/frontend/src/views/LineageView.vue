<template>
  <div class="lineage-view">
    <!-- 头部信息 -->
    <div class="lineage-header">
      <el-button @click="$router.back()" :icon="ArrowLeft" text>返回</el-button>
      <span class="title">存储过程血缘图：{{ procName }}</span>
      <span class="meta" v-if="graphData">
        更新目标：{{ graphData.updateTarget || '-' }}
        · 节点 {{ graphData.nodes?.length || 0 }}
        · 边 {{ graphData.edges?.length || 0 }}
      </span>
      <el-tag v-if="graphData?.truncated" type="warning">递归已截断</el-tag>
    </div>

    <!-- 工具栏 -->
    <div class="lineage-toolbar">
      <el-button size="small" @click="toggleTemp">收起/展开临时表</el-button>
      <el-button size="small" @click="exportPng">导出 PNG</el-button>
      <el-button size="small" @click="reload">重新加载</el-button>
    </div>

    <!-- 图例 -->
    <div class="legend">
      <span class="legend-item"><i class="dot source"></i>源表</span>
      <span class="legend-item"><i class="dot temp"></i>临时表</span>
      <span class="legend-item"><i class="dot target"></i>目标表</span>
    </div>

    <!-- G6 画布 -->
    <div class="lineage-canvas-container" v-loading="loading">
      <div ref="canvasRef" class="lineage-canvas"></div>
      <el-empty
        v-if="loaded && (!graphData || !graphData.nodes?.length)"
        description="未解析到血缘关系，请重新解析"
      />
    </div>

    <!-- 详情面板 -->
    <LineageDetailPanel
      v-model:visible="panelVisible"
      :type="selectedType"
      :node="selectedNode"
      :edge="selectedEdge"
      @trace-upstream="handleTraceUpstream"
      @trace-downstream="handleTraceDownstream"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { Graph } from '@antv/g6'
import { getLineage, traceUpstream, traceDownstream } from '../api/lineage'
import LineageDetailPanel from '../components/LineageDetailPanel.vue'

const route = useRoute()
const procName = ref(route.params.procName)

const canvasRef = ref(null)
const graph = ref(null)
const graphData = ref(null)
const loaded = ref(false)
const loading = ref(false)

const panelVisible = ref(false)
const selectedType = ref('')
const selectedNode = ref(null)
const selectedEdge = ref(null)

const hideTemp = ref(false)

const NODE_COLORS = {
  SOURCE: '#67c23a',
  TEMP: '#e6a23c',
  TARGET: '#f56c6c'
}

function transformToG6(vo) {
  if (!vo) return { nodes: [], edges: [] }
  const nodes = (vo.nodes || []).map((n) => {
    const color = NODE_COLORS[n.nodeType] || '#909399'
    return {
      id: n.id,
      data: { ...n },
      style: {
        fill: color,
        stroke: color,
        labelText: n.id
      }
    }
  })
  const edges = (vo.edges || []).map((e, i) => ({
    id: `edge-${i}`,
    source: e.source,
    target: e.target,
    data: { ...e },
    style: {
      labelText: e.operation || ''
    }
  }))
  return { nodes, edges }
}

async function loadData(name) {
  loading.value = true
  try {
    const res = await getLineage(name)
    // request.js 拦截器已返回 response.data，即 { code, data, message }
    if (res && res.code === 0) {
      graphData.value = res.data
    } else {
      ElMessage.error(res?.message || '查询失败')
      graphData.value = null
    }
    loaded.value = true
    renderGraph()
  } catch (e) {
    console.error(e)
    ElMessage.error('加载血缘数据失败')
    loaded.value = true
  } finally {
    loading.value = false
  }
}

async function renderGraph() {
  if (!graph.value) {
    initGraph()
  }
  if (!graph.value) return
  const data = transformToG6(graphData.value)
  if (hideTemp.value) {
    const tempIds = new Set(
      data.nodes.filter((n) => n.data.nodeType === 'TEMP').map((n) => n.id)
    )
    data.nodes = data.nodes.filter((n) => !tempIds.has(n.id))
    data.edges = data.edges.filter(
      (e) => !tempIds.has(e.source) && !tempIds.has(e.target)
    )
  }
  try {
    graph.value.setData(data)
    await graph.value.render()
  } catch (e) {
    console.error('render error:', e)
  }
}

function initGraph() {
  if (!canvasRef.value) return
  try {
    graph.value = new Graph({
      container: canvasRef.value,
      width: canvasRef.value.clientWidth || 800,
      height: canvasRef.value.clientHeight || 600,
      autoFit: 'view',
      layout: {
        type: 'dagre',
        rankdir: 'LR',
        nodesep: 40,
        ranksep: 80
      },
      node: {
        type: 'rect',
        style: {
          size: [160, 40],
          radius: 8,
          fillOpacity: 0.15,
          lineWidth: 2,
          fill: '#909399',
          stroke: '#909399',
          labelFill: '#303133',
          labelFontSize: 12,
          labelPlacement: 'center'
        }
      },
      edge: {
        type: 'quadratic',
        style: {
          stroke: '#909399',
          lineWidth: 1.5,
          endArrow: true,
          labelFontSize: 10,
          labelFill: '#606266'
        }
      },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'click-select']
    })

    graph.value.on('node:click', (e) => {
      const id = typeof e.target === 'string' ? e.target : e.target?.id
      if (!id) return
      let model
      try {
        model = graph.value.getNodeData(id)
      } catch (err) {
        model = null
      }
      const nodeData = model?.data || {}
      selectedType.value = 'node'
      selectedNode.value = nodeData
      selectedEdge.value = null
      panelVisible.value = true
    })

    graph.value.on('edge:click', (e) => {
      const id = typeof e.target === 'string' ? e.target : e.target?.id
      if (!id) return
      let model
      try {
        model = graph.value.getEdgeData(id)
      } catch (err) {
        model = null
      }
      const edgeData = model?.data || {}
      selectedType.value = 'edge'
      selectedEdge.value = edgeData
      selectedNode.value = null
      panelVisible.value = true
    })
  } catch (e) {
    console.error('initGraph error:', e)
    ElMessage.error('图谱初始化失败')
  }
}

function toggleTemp() {
  hideTemp.value = !hideTemp.value
  renderGraph()
}

async function exportPng() {
  if (!graph.value) return
  try {
    let url
    if (typeof graph.value.toFullDataURL === 'function') {
      url = await graph.value.toFullDataURL('image/png', { padding: 20 })
    } else if (typeof graph.value.toDataURL === 'function') {
      url = await graph.value.toDataURL('image/png', { padding: 20 })
    }
    if (!url) {
      ElMessage.warning('当前版本不支持导出')
      return
    }
    const a = document.createElement('a')
    a.href = url
    a.download = `${procName.value}_lineage.png`
    a.click()
    ElMessage.success('导出成功')
  } catch (e) {
    console.error(e)
    ElMessage.error('导出失败')
  }
}

async function handleTraceUpstream(tableName) {
  loading.value = true
  try {
    const res = await traceUpstream(tableName, 5)
    if (res && res.code === 0) {
      graphData.value = res.data
      panelVisible.value = false
      renderGraph()
      ElMessage.success(`已加载 ${tableName} 的上游血缘`)
    } else {
      ElMessage.error(res?.message || '追溯上游失败')
    }
  } catch (e) {
    ElMessage.error('追溯上游失败')
  } finally {
    loading.value = false
  }
}

async function handleTraceDownstream(tableName) {
  loading.value = true
  try {
    const res = await traceDownstream(tableName, 5)
    if (res && res.code === 0) {
      graphData.value = res.data
      panelVisible.value = false
      renderGraph()
      ElMessage.success(`已加载 ${tableName} 的下游血缘`)
    } else {
      ElMessage.error(res?.message || '追溯下游失败')
    }
  } catch (e) {
    ElMessage.error('追溯下游失败')
  } finally {
    loading.value = false
  }
}

function reload() {
  loadData(procName.value)
}

watch(
  () => route.params.procName,
  (newName) => {
    if (newName && newName !== procName.value) {
      procName.value = newName
      loadData(newName)
    }
  }
)

function handleResize() {
  if (graph.value && canvasRef.value) {
    try {
      graph.value.resize(
        canvasRef.value.clientWidth,
        canvasRef.value.clientHeight
      )
    } catch (e) {
      try {
        graph.value.setSize(
          canvasRef.value.clientWidth,
          canvasRef.value.clientHeight
        )
      } catch (e2) {
        // 忽略
      }
    }
  }
}

onMounted(() => {
  loadData(procName.value)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (graph.value) {
    try {
      graph.value.destroy()
    } catch (e) {
      // 忽略
    }
    graph.value = null
  }
})
</script>

<style scoped>
.lineage-view {
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
}
.lineage-header {
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  gap: 12px;
}
.lineage-header .title {
  font-weight: bold;
  font-size: 16px;
}
.lineage-header .meta {
  color: #909399;
  font-size: 13px;
}
.lineage-toolbar {
  padding: 8px 16px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  gap: 8px;
}
.legend {
  padding: 4px 16px;
  font-size: 12px;
  color: #606266;
  background: #fafafa;
}
.legend-item {
  margin-right: 16px;
}
.legend-item .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.dot.source {
  background: #67c23a;
}
.dot.temp {
  background: #e6a23c;
}
.dot.target {
  background: #f56c6c;
}
.lineage-canvas-container {
  flex: 1;
  position: relative;
  overflow: hidden;
}
.lineage-canvas {
  width: 100%;
  height: 100%;
}
</style>
