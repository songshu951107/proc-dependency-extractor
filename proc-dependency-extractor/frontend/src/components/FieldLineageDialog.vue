<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="`临时表血缘图：${tableName}`"
    width="80%"
    top="5vh"
    destroy-on-close
    @open="handleOpen"
    @closed="handleClosed"
  >
    <div v-loading="loading" class="dialog-body">
      <div class="dialog-toolbar">
        <span class="meta" v-if="graphData">
          节点 {{ graphData.nodes?.length || 0 }} · 边 {{ graphData.edges?.length || 0 }}
        </span>
        <el-button size="small" @click="reload">重新加载</el-button>
      </div>
      <div class="legend">
        <span class="legend-item"><i class="dot source"></i>源表</span>
        <span class="legend-item"><i class="dot temp"></i>临时表</span>
        <span class="legend-item"><i class="dot target"></i>目标表</span>
      </div>
      <div class="canvas-container">
        <div ref="canvasRef" class="lineage-canvas"></div>
        <el-empty
          v-if="loaded && (!graphData || !graphData.nodes?.length)"
          description="未解析到上游血缘"
        />
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import G6 from '@antv/g6'
import { traceUpstream } from '../api/lineage'

const props = defineProps({
  visible: Boolean,
  procName: String,
  tableName: String
})

const emit = defineEmits(['update:visible'])

const canvasRef = ref(null)
const graph = ref(null)
const graphData = ref(null)
const loaded = ref(false)
const loading = ref(false)

const NODE_COLORS = {
  SOURCE: '#67c23a',
  TEMP: '#e6a23c',
  TARGET: '#f56c6c'
}

let rendering = false

async function handleOpen() {
  await nextTick()
  await loadData()
}

function handleClosed() {
  if (graph.value) {
    try {
      graph.value.destroy()
    } catch (e) {
      // 忽略
    }
    graph.value = null
  }
  graphData.value = null
  loaded.value = false
}

async function loadData() {
  if (!props.tableName) return
  loading.value = true
  try {
    const res = await traceUpstream(props.tableName, 5)
    graphData.value = (res && (res.data || res)) || null
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

function transformToG6(vo) {
  if (!vo) return { nodes: [], edges: [] }
  const nodes = (vo.nodes || []).map((n) => ({
    id: n.id,
    label: n.id,
    nodeType: n.nodeType,
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
    label: e.operation || ''
  }))
  return { nodes, edges }
}

async function renderGraph() {
  if (rendering) return
  rendering = true
  try {
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
    graph.value.data(data)
    graph.value.render()
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
  const height = canvasRef.value.clientHeight || 500
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
  } catch (e) {
    console.error('initGraph error:', e)
    ElMessage.error('图谱初始化失败')
    graph.value = null
  }
}

function reload() {
  loadData()
}

onBeforeUnmount(() => {
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
.dialog-body {
  display: flex;
  flex-direction: column;
  height: 70vh;
}
.dialog-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0 8px;
}
.dialog-toolbar .meta {
  color: #909399;
  font-size: 13px;
}
.legend {
  padding: 4px 0;
  font-size: 12px;
  color: #606266;
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
.canvas-container {
  flex: 1;
  position: relative;
  overflow: hidden;
  min-height: 400px;
  border: 1px solid #ebeef5;
}
.lineage-canvas {
  width: 100%;
  height: 100%;
}
</style>
