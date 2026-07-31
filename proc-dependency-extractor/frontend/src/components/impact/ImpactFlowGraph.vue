<template>
  <div class="flow-graph-container">
    <VueFlow
      :nodes="nodes"
      :edges="edges"
      :fit-view-on-init="true"
      @node-click="handleNodeClick"
      @edge-click="handleEdgeClick"
    >
      <template #node-impactNode="props">
        <ImpactNode :id="props.id" :data="props.data" />
      </template>
    </VueFlow>
    <div v-if="nodes.length === 0" class="empty-tip">
      <el-empty description="未找到影响链路" :image-size="60" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import dagre from 'dagre'
import ImpactNode from './ImpactNode.vue'
import { ElMessage } from 'element-plus'
import { expand } from '@/api/impact'

const props = defineProps({
  initialNodes: { type: Array, default: () => [] },
  initialEdges: { type: Array, default: () => [] },
  direction: { type: String, default: 'DOWNSTREAM' }
})

const emit = defineEmits(['nodeClick', 'edgeClick'])

const nodes = ref([])
const edges = ref([])

const graphStore = reactive({
  nodeMap: new Map(),
  edgeMap: new Map()
})

function handleNodeClick(evt) {
  emit('nodeClick', evt.node)
}

function handleEdgeClick(evt) {
  emit('edgeClick', evt.edge)
}

function rebuildGraph() {
  if (graphStore.nodeMap.size === 0) {
    nodes.value = []
    edges.value = []
    return
  }

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 80 })
  g.setDefaultEdgeLabel(() => ({}))

  graphStore.nodeMap.forEach((n, id) => {
    g.setNode(id, { width: 220, height: 60 })
  })
  graphStore.edgeMap.forEach((e, key) => {
    g.setEdge(e.source, e.target)
  })

  dagre.layout(g)

  nodes.value = g.nodes().map(id => {
    const node = graphStore.nodeMap.get(id)
    const dagreNode = g.node(id)
    return {
      id,
      position: { x: dagreNode.x - 110, y: dagreNode.y - 30 },
      data: node,
      type: 'impactNode'
    }
  })

  edges.value = g.edges().map(e => ({
    id: `${e.v}-${e.w}`,
    source: e.v,
    target: e.w,
    type: 'smoothstep',
    animated: true,
    style: { stroke: '#64748b', strokeWidth: 1.5 }
  }))
}

async function expandNode(nodeId) {
  try {
    const resp = await expand({
      nodeId: nodeId,
      direction: props.direction,
      depth: 1
    })
    const newNodes = resp.data?.nodes || []
    const newEdges = resp.data?.edges || []

    let changed = false
    newNodes.forEach(n => {
      if (!graphStore.nodeMap.has(n.id)) {
        graphStore.nodeMap.set(n.id, {
          label: n.label || n.id,
          layer: n.layer,
          isTemp: n.isTemp,
          matchedColumns: n.matchedColumns || []
        })
        changed = true
      }
    })
    newEdges.forEach(e => {
      const key = `${e.source}-${e.target}`
      if (!graphStore.edgeMap.has(key)) {
        graphStore.edgeMap.set(key, { source: e.source, target: e.target })
        changed = true
      }
    })

    if (changed) {
      rebuildGraph()
    } else {
      ElMessage.info(`表 "${nodeId}" 没有更多下游依赖`)
    }
  } catch (e) {
    ElMessage.error('展开失败: ' + (e.message || ''))
  }
}

function loadInitial(initialNodes, initialEdges) {
  graphStore.nodeMap.clear()
  graphStore.edgeMap.clear()

  initialNodes.forEach(n => {
    graphStore.nodeMap.set(n.id || n.name, {
      label: n.label || n.shortName || n.id || n.name,
      layer: n.layer,
      isTemp: n.isTemp || n.temp,
      matchedColumns: n.matchedColumns || []
    })
  })
  initialEdges.forEach(e => {
    const src = e.source || e.impactSource
    const tgt = e.target || e.name
    if (src && tgt) {
      graphStore.edgeMap.set(`${src}-${tgt}`, { source: src, target: tgt })
    }
  })

  rebuildGraph()
}

watch(() => [props.initialNodes, props.initialEdges], () => {
  loadInitial(props.initialNodes, props.initialEdges)
}, { immediate: true, deep: true })

defineExpose({ expandNode })
</script>

<style scoped>
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';

.flow-graph-container {
  position: relative;
  width: 100%;
  height: 500px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  background: #fafafa;
  overflow: hidden;
}
.empty-tip {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}
</style>
