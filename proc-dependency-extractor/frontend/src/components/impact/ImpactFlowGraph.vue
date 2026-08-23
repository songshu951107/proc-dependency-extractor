<template>
  <div class="flow-graph-container">
    <VueFlow
      ref="vueFlowRef"
      :nodes="nodes"
      :edges="edges"
      :fit-view-on-init="true"
      @node-click="handleNodeClick"
      @edge-click="handleEdgeClick"
    >
      <template #node-impactNode="props">
        <ImpactNode :id="props.id" :data="props.data" />
      </template>
      <template #node-transformNode="props">
        <ImpactNode :id="props.id" :data="props.data" />
      </template>
    </VueFlow>
    <div v-if="nodes.length === 0" class="empty-tip">
      <el-empty description="未找到影响链路" :image-size="60" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, nextTick } from 'vue'
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
  direction: { type: String, default: 'DOWNSTREAM' },
  // 命中节点 id 集合（null/空 = 无筛选，全部高亮）
  matchedNodeIds: { type: Set, default: null },
  // 隐藏模式下需要保留的节点集合（命中节点 + 其链路祖先）；null=不做保留过滤
  preservedNodeIds: { type: Set, default: null },
  // 是否隐藏未命中节点（true=移出画布；false=变淡保留）
  hideUnmatched: { type: Boolean, default: false }
})

const emit = defineEmits(['nodeClick', 'edgeClick'])

const nodes = ref([])
const edges = ref([])

// VueFlow 实例引用，用于节点异步加载后主动 fitView（fit-view-on-init 仅在初始化时空视图生效）
const vueFlowRef = ref(null)

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

// 判断当前是否有筛选生效
function hasFilter() {
  return props.matchedNodeIds && props.matchedNodeIds.size > 0
}

function isMatched(id) {
  if (!hasFilter()) return true
  return props.matchedNodeIds.has(id)
}

function rebuildGraph() {
  if (graphStore.nodeMap.size === 0) {
    nodes.value = []
    edges.value = []
    return
  }

  const filtering = hasFilter()

  // 隐藏模式下：只保留链路上的节点（命中节点 + 其祖先）；变淡模式下：全部参与布局
  // preservedNodeIds 为 null 时表示不需要保留过滤（非隐藏模式或无筛选）
  const preserved = props.preservedNodeIds
  const visibleNodeIds = new Set()
  graphStore.nodeMap.forEach((n, id) => {
    if (filtering && props.hideUnmatched && preserved && !preserved.has(id)) return
    visibleNodeIds.add(id)
  })

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 80 })
  g.setDefaultEdgeLabel(() => ({}))

  visibleNodeIds.forEach(id => {
    g.setNode(id, { width: 220, height: 60 })
  })
  graphStore.edgeMap.forEach((e, key) => {
    // 两端必须都可见才画边
    if (!visibleNodeIds.has(e.source) || !visibleNodeIds.has(e.target)) return
    g.setEdge(e.source, e.target)
  })

  dagre.layout(g)

  nodes.value = g.nodes().map(id => {
    const node = graphStore.nodeMap.get(id)
    const dagreNode = g.node(id)
    const matched = isMatched(id)
    return {
      id,
      position: { x: dagreNode.x - 110, y: dagreNode.y - 30 },
      data: node,
      type: node.nodeLevel === 'TRANSFORM' ? 'transformNode' : 'impactNode',
      // 筛选时：命中=高亮，未命中（链路祖先）=变淡；无筛选=正常
      classed: filtering ? (matched ? 'node-highlight' : 'node-dim') : ''
    }
  })

  edges.value = g.edges().map(e => {
    const bothMatched = isMatched(e.v) && isMatched(e.w)
    const edgeData = graphStore.edgeMap.get(`${e.v}-${e.w}`) || {}
    const isInputTo = edgeData.edgeType === 'INPUT_TO'
    const isOutputTo = edgeData.edgeType === 'OUTPUT_TO'
    const isDerivedFrom = edgeData.edgeType === 'DERIVED_FROM'
    return {
      id: `${e.v}-${e.w}`,
      source: e.v,
      target: e.w,
      type: 'smoothstep',
      animated: filtering ? bothMatched : true,
      classed: filtering ? (bothMatched ? 'edge-highlight' : 'edge-dim') : '',
      style: {
        stroke: filtering
          ? (bothMatched ? '#1890ff' : '#64748b')
          : (isInputTo ? '#94a3b8' : (isOutputTo ? '#3b82f6' : (isDerivedFrom ? '#10b981' : '#64748b'))),
        strokeWidth: filtering && bothMatched ? 2 : 1.5,
        opacity: filtering ? (bothMatched ? 1 : 0.15) : 1,
        strokeDasharray: isInputTo ? '5,5' : undefined
      }
    }
  })

  // 节点异步赋值后，fit-view-on-init 已失效，需主动 fitView 使图谱居中
  nextTick(() => {
    vueFlowRef.value?.fitView({ padding: 0.1, duration: 200 })
  })
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
          matchedColumns: n.matchedColumns || [],
          nodeLevel: n.nodeLevel,
          expression: n.expression,
          logicType: n.logicType,
          sqlHint: n.sqlHint,
          procName: n.procName,
          tableFullName: n.tableFullName,
          scriptFile: n.scriptFile
        })
        changed = true
      }
    })
    newEdges.forEach(e => {
      const key = `${e.source}-${e.target}`
      if (!graphStore.edgeMap.has(key)) {
        graphStore.edgeMap.set(key, {
          source: e.source,
          target: e.target,
          edgeType: e.edgeType,
          role: e.role,
          expression: e.expression,
          sourceAlias: e.sourceAlias,
          procName: e.procName
        })
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
      matchedColumns: n.matchedColumns || [],
      nodeLevel: n.nodeLevel,
      expression: n.expression,
      logicType: n.logicType,
      sqlHint: n.sqlHint,
      procName: n.procName,
      tableFullName: n.tableFullName,
      scriptFile: n.scriptFile
    })
  })
  initialEdges.forEach(e => {
    const src = e.source || e.impactSource
    const tgt = e.target || e.name
    if (src && tgt) {
      graphStore.edgeMap.set(`${src}-${tgt}`, {
        source: src,
        target: tgt,
        edgeType: e.edgeType,
        role: e.role,
        expression: e.expression,
        sourceAlias: e.sourceAlias,
        procName: e.procName
      })
    }
  })

  rebuildGraph()
}

watch(() => [props.initialNodes, props.initialEdges], () => {
  loadInitial(props.initialNodes, props.initialEdges)
}, { immediate: true, deep: true })

// 筛选条件变化时重新构建（不会重新加载 initialNodes，只重排已有节点）
watch(() => [props.matchedNodeIds, props.preservedNodeIds, props.hideUnmatched], () => {
  rebuildGraph()
})

defineExpose({ expandNode })
</script>

<style>
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';

/* 筛选状态样式（非 scoped，VueFlow 内部渲染需要全局 class） */
.vue-flow__node.node-highlight {
  outline: 2px solid #1890ff;
  outline-offset: 2px;
}
.vue-flow__node.node-dim {
  opacity: 0.15;
}
.vue-flow__edge.edge-dim .vue-flow__edge-path {
  opacity: 0.15;
}
.vue-flow__edge.edge-highlight .vue-flow__edge-path {
  stroke: #1890ff !important;
  stroke-width: 2;
}
</style>

<style scoped>
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
