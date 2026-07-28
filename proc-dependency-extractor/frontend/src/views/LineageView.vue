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
      <el-button link type="primary" @click="scriptVisible = true" style="margin-left: auto">
        查看脚本
      </el-button>
    </div>

    <!-- 主 Tab -->
    <el-tabs v-model="activeMainTab" class="main-tabs">
      <el-tab-pane label="血缘图" name="graph" class="tab-graph">
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
      </el-tab-pane>

      <el-tab-pane label="字段来源列表" name="fields">
        <FieldSourceList
          :proc-name="procName"
          :target-tables="targetTableOptions"
        />
      </el-tab-pane>

      <el-tab-pane label="SQL 块详情" name="blocks">
        <SqlBlockDetails
          :proc-name="procName"
          @view-script="handleViewScript"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 详情面板 -->
    <LineageDetailPanel
      v-model:visible="panelVisible"
      :type="selectedType"
      :node="selectedNode"
      :edge="selectedEdge"
      @trace-upstream="handleTraceUpstream"
      @trace-downstream="handleTraceDownstream"
    />

    <!-- 脚本查看弹窗 -->
    <ScriptViewer
      v-model:visible="scriptVisible"
      :proc-name="procName"
      :highlight-block-seq="highlightBlockSeq"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import G6 from '@antv/g6'
import { getLineage, traceUpstream, traceDownstream } from '../api/lineage'
import LineageDetailPanel from '../components/LineageDetailPanel.vue'
import FieldSourceList from '../components/FieldSourceList.vue'
import SqlBlockDetails from '../components/SqlBlockDetails.vue'
import ScriptViewer from '../components/ScriptViewer.vue'

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

// 主 Tab 状态
const activeMainTab = ref('graph')
// 脚本查看弹窗
const scriptVisible = ref(false)
const highlightBlockSeq = ref(null)

// 防止重入
let rendering = false
let resizeTimer = null

const NODE_COLORS = {
  SOURCE: '#67c23a',
  TEMP: '#e6a23c',
  TARGET: '#f56c6c'
}

// 从 graphData.nodes 转换为 FieldSourceList 需要的 [{name, type}] 格式
const targetTableOptions = computed(() => {
  if (!graphData.value || !graphData.value.nodes) return []
  return graphData.value.nodes.map((n) => ({
    name: n.id,
    type: n.nodeType
  }))
})

function transformToG6(vo) {
  if (!vo) return { nodes: [], edges: [] }
  const nodes = (vo.nodes || []).map((n) => ({
    id: n.id,
    label: n.id,
    nodeType: n.nodeType,
    fullTableName: n.fullTableName,
    schemaName: n.schemaName,
    schemaVarSrc: n.schemaVarSrc,
    procName: n.procName,
    style: {
      fill: NODE_COLORS[n.nodeType] || '#909399',
      stroke: NODE_COLORS[n.nodeType] || '#909399',
      fillOpacity: 0.15
    }
  }))
  const edges = (vo.edges || []).map((e, i) => ({
    id: `edge-${i}`,
    source: e.source,
    target: e.target,
    label: e.operation || '',
    operation: e.operation,
    sqlBlockSeq: e.sqlBlockSeq,
    filters: e.filters,
    mainTable: e.mainTable,
    fieldMappings: e.fieldMappings
  }))
  return { nodes, edges }
}

async function loadData(name) {
  loading.value = true
  try {
    const res = await getLineage(name)
    if (res && res.data) {
      graphData.value = res.data
    } else {
      graphData.value = res || null
    }
    loaded.value = true
    await renderGraph()
  } catch (e) {
    console.error(e)
    ElMessage.error('加载血缘数据失败')
    graphData.value = null
    loaded.value = true
  } finally {
    loading.value = false
  }
}

async function renderGraph() {
  if (rendering) return
  rendering = true
  try {
    // 每次渲染都销毁旧实例重建，避免 G6 v4 内部状态残留导致的时序问题
    if (graph.value) {
      try {
        graph.value.destroy()
      } catch (e) {
        // 忽略
      }
      graph.value = null
    }
    await initGraph()
    if (!graph.value) return

    const data = transformToG6(graphData.value)
    if (hideTemp.value) {
      const tempIds = new Set(
        data.nodes.filter((n) => n.nodeType === 'TEMP').map((n) => n.id)
      )
      data.nodes = data.nodes.filter((n) => !tempIds.has(n.id))
      data.edges = data.edges.filter(
        (e) => !tempIds.has(e.source) && !tempIds.has(e.target)
      )
    }
    // G6 v4 标准流程：data → render
    graph.value.data(data)
    graph.value.render()
    // 等待布局完成再 fitView
    await new Promise((resolve) => setTimeout(resolve, 100))
    graph.value.fitView(20)
  } catch (e) {
    console.error('render error:', e)
  } finally {
    rendering = false
  }
}

async function initGraph() {
  if (!canvasRef.value) return
  await nextTick()
  const width = canvasRef.value.clientWidth || 800
  const height = canvasRef.value.clientHeight || 600
  try {
    graph.value = new G6.Graph({
      container: canvasRef.value,
      width,
      height,
      fitView: true,
      fitViewPadding: 20,
      modes: {
        default: ['drag-canvas', 'zoom-canvas']
      },
      layout: {
        type: 'dagre',
        rankdir: 'LR',
        nodesep: 40,
        ranksep: 80,
        preventOverlap: true
      },
      defaultNode: {
        type: 'rect',
        size: [160, 40],
        style: {
          radius: 8,
          lineWidth: 2,
          fillOpacity: 0.15
        },
        labelCfg: {
          style: {
            fill: '#303133',
            fontSize: 12
          }
        }
      },
      defaultEdge: {
        type: 'quadratic',
        style: {
          stroke: '#909399',
          lineWidth: 1.5,
          endArrow: true
        },
        labelCfg: {
          style: {
            fontSize: 10,
            fill: '#606266'
          }
        }
      }
    })

    graph.value.on('node:click', (e) => {
      try {
        const item = e.item
        if (!item || item.destroyed) return
        const model = item.getModel()
        if (!model) return
        selectedType.value = 'node'
        selectedNode.value = {
          id: model.id,
          nodeType: model.nodeType,
          fullTableName: model.fullTableName,
          schemaName: model.schemaName,
          schemaVarSrc: model.schemaVarSrc,
          procName: model.procName
        }
        selectedEdge.value = null
        panelVisible.value = true
      } catch (err) {
        console.warn('node:click error:', err)
      }
    })

    graph.value.on('edge:click', (e) => {
      try {
        const item = e.item
        if (!item || item.destroyed) return
        const model = item.getModel()
        if (!model) return
        selectedType.value = 'edge'
        selectedEdge.value = {
          source: model.source,
          target: model.target,
          operation: model.operation,
          sqlBlockSeq: model.sqlBlockSeq,
          filters: model.filters,
          mainTable: model.mainTable,
          fieldMappings: model.fieldMappings
        }
        selectedNode.value = null
        panelVisible.value = true
      } catch (err) {
        console.warn('edge:click error:', err)
      }
    })
  } catch (e) {
    console.error('initGraph error:', e)
    ElMessage.error('图谱初始化失败')
    graph.value = null
  }
}

function toggleTemp() {
  hideTemp.value = !hideTemp.value
  renderGraph()
}

async function exportPng() {
  if (!graph.value) return
  try {
    graph.value.downloadFullImage(`${procName.value}_lineage`, 'png', {
      padding: 20
    })
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
    if (res && res.data) {
      graphData.value = res.data
      panelVisible.value = false
      await renderGraph()
      ElMessage.success(`已加载 ${tableName} 的上游血缘`)
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
    if (res && res.data) {
      graphData.value = res.data
      panelVisible.value = false
      await renderGraph()
      ElMessage.success(`已加载 ${tableName} 的下游血缘`)
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

// 由 SqlBlockDetails 触发，打开脚本弹窗并高亮指定块
function handleViewScript(blockSeq) {
  highlightBlockSeq.value = blockSeq
  scriptVisible.value = true
}

// Tab 切回"血缘图"时重新调整画布尺寸（隐藏期间 clientWidth 可能为 0）
watch(activeMainTab, (v) => {
  if (v === 'graph') {
    nextTick(() => handleResize())
  }
})

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
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    if (graph.value && canvasRef.value && !rendering) {
      try {
        graph.value.changeSize(
          canvasRef.value.clientWidth,
          canvasRef.value.clientHeight
        )
        graph.value.fitView(20)
      } catch (e) {
        // 忽略
      }
    }
  }, 200)
}

onMounted(() => {
  loadData(procName.value)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  if (resizeTimer) {
    clearTimeout(resizeTimer)
    resizeTimer = null
  }
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
  min-height: 400px;
}
.lineage-canvas {
  width: 100%;
  height: 100%;
}

/* 主 Tab 样式：撑满剩余空间 */
.main-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.main-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.main-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.main-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.tab-graph {
  height: 100%;
  display: flex;
  flex-direction: column;
}
</style>
