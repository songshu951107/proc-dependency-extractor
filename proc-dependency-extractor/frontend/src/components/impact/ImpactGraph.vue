<template>
  <div class="impact-graph-container">
    <div v-if="!fallbackMode" ref="graphContainer" class="graph-canvas"></div>
    <el-empty v-if="!fallbackMode && (!nodes || nodes.length === 0)" description="未找到影响链路" />

    <!-- G6 渲染失败时降级为表格视图 -->
    <div v-if="fallbackMode" class="fallback-view">
      <el-alert
        title="G6 图渲染失败，已降级为表格视图"
        type="warning"
        :description="fallbackError"
        show-icon
        :closable="false"
        style="margin-bottom: 10px"
      />
      <div style="margin-bottom: 10px; font-weight: bold">节点列表</div>
      <el-table :data="nodes" size="small" border style="margin-bottom: 10px" max-height="200">
        <el-table-column prop="id" label="ID" min-width="160" />
        <el-table-column prop="label" label="名称" min-width="120" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="depth" label="深度" width="80" />
      </el-table>
      <div style="margin-bottom: 10px; font-weight: bold">边列表</div>
      <el-table :data="edges" size="small" border max-height="200">
        <el-table-column prop="source" label="源" min-width="160" />
        <el-table-column prop="target" label="目标" min-width="160" />
        <el-table-column prop="operation" label="操作" width="120" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import G6 from '@antv/g6'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  isFieldLevel: { type: Boolean, default: false }
})
const emit = defineEmits(['nodeClick', 'edgeClick'])

const graphContainer = ref(null)
const fallbackMode = ref(false)
const fallbackError = ref('')
let graph = null

const NODE_COLORS = {
  SOURCE: '#52c41a',
  TEMP: '#faad14',
  TARGET: '#f5222d',
  FIELD: '#1890ff'
}

const initGraph = () => {
  if (!graphContainer.value) return
  try {
    const width = graphContainer.value.offsetWidth
    const height = graphContainer.value.offsetHeight || 500

    graph = new G6.Graph({
      container: graphContainer.value,
      width,
      height,
      modes: {
        default: ['drag-canvas', 'zoom-canvas', 'drag-node']
      },
      layout: {
        type: 'dagre',
        rankdir: 'LR',
        nodesep: 30,
        ranksep: 60
      },
      defaultNode: {
        type: 'rect',
        style: { radius: 4 },
        labelCfg: { style: { fontSize: 12 } }
      },
      defaultEdge: {
        type: 'cubic-horizontal',
        style: { endArrow: true, lineWidth: 1.5 }
      },
      nodeStateStyles: {
        hover: { shadowBlur: 10, shadowColor: '#1890ff' }
      }
    })

    graph.on('node:click', (evt) => {
      emit('nodeClick', evt.item.getModel())
    })
    graph.on('edge:click', (evt) => {
      emit('edgeClick', evt.item.getModel())
    })

    renderGraph()
  } catch (e) {
    console.error('G6 初始化失败，降级为表格视图', e)
    fallbackError.value = e?.message || String(e)
    fallbackMode.value = true
    graph = null
  }
}

const renderGraph = () => {
  if (!graph || !props.nodes) return

  try {
    const g6Nodes = props.nodes.map(n => ({
      id: n.id,
      label: n.label || n.id,
      style: {
        fill: props.isFieldLevel ? NODE_COLORS.FIELD : (NODE_COLORS[n.type] || '#d9d9d9'),
        stroke: '#333'
      }
    }))

    const g6Edges = props.edges.map((e, i) => ({
      id: `edge-${i}`,
      source: e.source,
      target: e.target,
      style: {
        lineDash: props.isFieldLevel ? [4, 4] : undefined
      }
    }))

    graph.data({ nodes: g6Nodes, edges: g6Edges })
    graph.render()
  } catch (e) {
    console.error('G6 渲染失败，降级为表格视图', e)
    fallbackError.value = e?.message || String(e)
    fallbackMode.value = true
    if (graph) {
      try { graph.destroy() } catch (_) {}
      graph = null
    }
  }
}

const addNodes = (newNodes, newEdges) => {
  if (!graph) return
  const g6Nodes = newNodes.map(n => ({
    id: n.id,
    label: n.label || n.id,
    style: {
      fill: props.isFieldLevel ? NODE_COLORS.FIELD : (NODE_COLORS[n.type] || '#d9d9d9'),
      stroke: '#333'
    }
  }))
  const g6Edges = newEdges.map((e, i) => ({
    id: `edge-add-${Date.now()}-${i}`,
    source: e.source,
    target: e.target,
    style: {
      lineDash: props.isFieldLevel ? [4, 4] : undefined
    }
  }))
  graph.addItem('nodes', g6Nodes)
  // G6 v4 批量添加需逐个
  g6Edges.forEach(e => graph.addItem('edge', e))
}

const removeDownstream = (nodeId) => {
  if (!graph) return
  // 递归查找下游节点并移除
  const toRemove = new Set()
  const queue = [nodeId]
  while (queue.length) {
    const current = queue.shift()
    const edges = graph.getEdges().filter(e => e.getModel().source === current)
    edges.forEach(e => {
      const target = e.getModel().target
      if (!toRemove.has(target)) {
        toRemove.add(target)
        queue.push(target)
      }
    })
  }
  toRemove.forEach(id => graph.removeItem(id))
}

const handleResize = () => {
  if (graph && graphContainer.value) {
    graph.changeSize(graphContainer.value.offsetWidth, graphContainer.value.offsetHeight || 500)
  }
}

onMounted(() => {
  nextTick(() => initGraph())
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (graph) {
    graph.destroy()
    graph = null
  }
})

watch(() => [props.nodes, props.edges], () => {
  renderGraph()
}, { deep: true })

defineExpose({ addNodes, removeDownstream })
</script>

<style scoped>
.impact-graph-container {
  position: relative;
  height: 500px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}
.graph-canvas {
  width: 100%;
  height: 100%;
}
.fallback-view {
  padding: 10px;
  height: 100%;
  overflow: auto;
}
</style>
