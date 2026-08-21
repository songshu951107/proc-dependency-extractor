<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="dialogTitle"
    width="75%"
    top="6vh"
    destroy-on-close
    @open="handleOpen"
    @closed="handleClosed"
  >
    <div class="path-dialog-body">
      <div class="summary" v-if="row">
        <el-tag v-for="r in (row.reasons || [])" :key="r" size="small" style="margin-right: 4px">{{ r }}</el-tag>
        <span class="summary-text">
          {{ row.upstream
            ? `${row.table}${row.field ? '.' + row.field : ''} 在该脚本中的流向（距分析起点 ${row.level} 跳）`
            : `${sourceId} →（${row.level} 跳）→ ${row.table}${row.field ? '.' + row.field : ''}` }}
        </span>
        <span class="meta" v-if="subgraph">节点 {{ subgraph.nodes.length }} · 边 {{ subgraph.edges.length }}</span>
      </div>
      <div class="legend">
        <span class="legend-item"><i class="dot fdm"></i>FDM</span>
        <span class="legend-item"><i class="dot cdm"></i>CDM/DWD</span>
        <span class="legend-item"><i class="dot temp"></i>临时表</span>
        <span class="legend-item"><i class="dot transform"></i>Transform</span>
        <span class="legend-item"><span class="line-solid">——</span> 直接派生</span>
        <span class="legend-item"><span class="line-dashed">- - -</span> 行影响(FILTERS: ON/WHERE)</span>
      </div>
      <div class="canvas-container">
        <div ref="canvasRef" class="path-canvas"></div>
        <el-empty v-if="loaded && (!subgraph || !subgraph.nodes.length)" description="未回溯到链路数据" />
      </div>
      <div class="node-panel" v-if="selectedNode">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="名称">{{ selectedNode.label }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ selectedNode.nodeLevel }}</el-descriptions-item>
          <el-descriptions-item label="层级">{{ selectedNode.layer || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import G6 from '@antv/g6'
import { traceUpstreamSubgraph, traceDownstreamSubgraph } from './impactListUtils'

const props = defineProps({
  visible: Boolean,
  row: Object,
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  sourceId: String
})
const emit = defineEmits(['update:visible', 'viewScript'])

const canvasRef = ref(null)
const graph = ref(null)
const subgraph = ref(null)
const loaded = ref(false)
const selectedNode = ref(null)

const dialogTitle = computed(() => {
  if (!props.row) return '影响链路'
  const target = `${props.row.table}${props.row.field ? '.' + props.row.field : ''}`
  return props.row.upstream ? `来源链路：${target}` : `影响链路：${target}`
})

const LAYER_COLORS = {
  FDM: '#67c23a',   // 绿
  CDM: '#409eff',   // 蓝
  DWM: '#409eff',
  DWD: '#409eff',
  TEMP: '#e6a23c'   // 橙
}

function nodeColor(n) {
  if (n.nodeLevel === 'TABLE') return '#b37feb'
  if (n.nodeLevel === 'TRANSFORM') return '#909399'
  if (n.temp || (n.id && n.id.split('.').length >= 2 && n.id.split('.')[1].toLowerCase().startsWith('vt_'))) return LAYER_COLORS.TEMP
  return LAYER_COLORS[n.layer] || '#409eff'
}

async function handleOpen() {
  await nextTick()
  loaded.value = false
  if (!props.row) return
  // UPSTREAM 行: 来源字段在该行脚本中的正向流向（traceUpstream 的镜像）;
  // DOWNSTREAM 行: 目标字段在该行脚本中的反向回溯
  subgraph.value = props.row.upstream
    ? traceDownstreamSubgraph(props.nodes, props.edges, props.row.targetId, props.row.procName)
    : traceUpstreamSubgraph(props.nodes, props.edges, props.row.targetId, props.row.procName)
  loaded.value = true
  await renderGraph()
}

function handleClosed() {
  destroyGraph()
  subgraph.value = null
  loaded.value = false
  selectedNode.value = null
}

function destroyGraph() {
  if (graph.value) {
    try { graph.value.destroy() } catch (e) { /* 忽略 */ }
    graph.value = null
  }
}

async function renderGraph() {
  destroyGraph()
  await initGraph()
  if (!graph.value || !subgraph.value) return
  try {
    const data = toG6Data(subgraph.value)
    graph.value.data(data)
    graph.value.render()
    await new Promise(r => setTimeout(r, 100))
    graph.value.fitView(20)
  } catch (e) {
    console.error('render error:', e)
    ElMessage.error('链路图渲染失败')
  }
}

function toG6Data(sg) {
  const isSource = id => id === props.sourceId
  const nodes = sg.nodes.map(n => ({
    id: n.id,
    label: n.nodeLevel === 'TRANSFORM' ? truncate(n.label || n.id, 24) : shortLabel(n),
    size: n.nodeLevel === 'TRANSFORM' ? 26 : [150, 36],
    style: {
      fill: nodeColor(n),
      stroke: isSource(n.id) ? '#f56c6c' : nodeColor(n),
      lineWidth: isSource(n.id) ? 3 : 2,
      fillOpacity: 0.2
    },
    labelCfg: { style: { fill: '#303133', fontSize: 11 } }
  }))
  // ROW_IMPACT（表→列）不逐列画边：行影响通过 FILTERS 边 + 列清单标签表达
  const edges = sg.edges.filter(e => e.edgeType !== 'ROW_IMPACT').map((e, i) => {
    const isCond = e.role === 'join_condition' || e.role === 'where_condition'
    return {
      id: `pe-${i}`,
      source: e.source,
      target: e.target,
      label: isCond ? (e.role === 'join_condition' ? 'ON' : 'WHERE') : '',
      style: {
        stroke: isCond ? '#e6a23c' : '#909399',
        lineWidth: 1.5,
        lineDash: isCond ? [6, 4] : undefined,
        endArrow: true
      },
      labelCfg: { style: { fontSize: 10, fill: '#e6a23c', background: { fill: '#fff', padding: [2, 4, 2, 4] } } }
    }
  })
  return { nodes, edges }
}

function shortLabel(n) {
  if (n.nodeLevel === 'FIELD') {
    const parts = (n.id || '').split('.')
    return parts.length >= 3 ? `${parts[1]}.${parts[2]}` : (n.label || n.id)
  }
  return n.label || n.id
}
function truncate(s, len) { return s && s.length > len ? s.slice(0, len) + '...' : (s || '') }

async function initGraph() {
  if (!canvasRef.value) return
  await nextTick()
  const width = canvasRef.value.clientWidth || 800
  const height = canvasRef.value.clientHeight || 460
  graph.value = new G6.Graph({
    container: canvasRef.value,
    width, height,
    fitView: true,
    fitViewPadding: 20,
    modes: { default: ['drag-canvas', 'zoom-canvas'] },
    layout: { type: 'dagre', rankdir: 'LR', nodesep: 30, ranksep: 70, preventOverlap: true },
    defaultNode: {
      type: 'rect',
      size: [150, 36],
      style: { radius: 6, lineWidth: 2, fillOpacity: 0.2 },
      labelCfg: { style: { fill: '#303133', fontSize: 11 } }
    },
    defaultEdge: {
      type: 'quadratic',
      style: { stroke: '#909399', lineWidth: 1.5, endArrow: true },
      labelCfg: { style: { fontSize: 10, fill: '#606266' } }
    }
  })
  graph.value.on('node:click', evt => {
    if (!evt.item || evt.item.destroyed) return
    const node = subgraph.value?.nodes.find(n => n.id === evt.item.getID())
    selectedNode.value = node || null
  })
  graph.value.on('edge:click', evt => {
    if (!evt.item || evt.item.destroyed) return
    const idx = Number(String(evt.item.getID()).replace('pe-', ''))
    // 与 toG6Data 一致：先过滤 ROW_IMPACT 再编号，保证索引对齐
    const edge = (subgraph.value?.edges || []).filter(e => e.edgeType !== 'ROW_IMPACT')[idx]
    if (edge && edge.procName) emit('viewScript', edge)
  })
}

onBeforeUnmount(destroyGraph)
</script>

<style scoped>
.path-dialog-body { display: flex; flex-direction: column; height: 72vh; }
.summary { display: flex; align-items: center; gap: 10px; padding: 2px 0 8px; }
.summary-text { font-size: 13px; color: #303133; }
.summary .meta { color: #909399; font-size: 12px; margin-left: auto; }
.legend { padding: 2px 0 6px; font-size: 12px; color: #606266; }
.legend-item { margin-right: 14px; }
.legend-item .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.dot.fdm { background: #67c23a; } .dot.cdm { background: #409eff; }
.dot.temp { background: #e6a23c; } .dot.transform { background: #909399; }
.line-solid { color: #909399; } .line-dashed { color: #e6a23c; }
.canvas-container { flex: 1; position: relative; overflow: hidden; min-height: 380px; border: 1px solid #ebeef5; }
.path-canvas { width: 100%; height: 100%; }
.node-panel { padding-top: 8px; }
</style>
