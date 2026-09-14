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
      <div class="hop-paths" v-if="hopPaths.length">
        <div v-for="(p, pi) in hopPaths" :key="pi" class="hop-path">
          <div class="hop-path-title">
            <el-tag size="small" :type="hopPaths.length > 1 ? 'info' : 'success'">
              传播路径{{ hopPaths.length > 1 ? ` ${pi + 1}` : '' }}（共 {{ p.reduce((m, s) => Math.max(m, s.hop), 0) }} 跳）
            </el-tag>
            <span class="hop-tip">每跳一行；临时表中转已折叠，跳数与层级对齐</span>
          </div>
          <el-table :data="p" border size="small">
            <el-table-column prop="hop" label="跳" width="52" align="center" />
            <el-table-column label="类型" width="110" align="center">
              <template #default="{ row: s }">
                <el-tag size="small" :type="stepTagType(s)">{{ stepTypeLabel(s) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="源 → 到达" min-width="220" show-overflow-tooltip>
              <template #default="{ row: s }">
                <span class="hop-node">{{ shortId(s.from) }}</span>
                <span class="hop-arrow"> → </span>
                <span class="hop-node" :class="{ 'hop-final': s.to === row?.targetId }">{{ shortId(s.to) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="加工（存储过程 · 明细）" min-width="260" show-overflow-tooltip>
              <template #default="{ row: s }">{{ stepDetail(s) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <div class="legend">
        <span class="legend-item"><i class="dot fdm"></i>FDM</span>
        <span class="legend-item"><i class="dot cdm"></i>CDM/DWD</span>
        <span class="legend-item"><i class="dot temp"></i>临时表</span>
        <span class="legend-item"><i class="dot transform"></i>Transform</span>
        <span class="legend-item"><span class="line-solid">——</span> 直接派生</span>
        <span class="legend-item"><span class="line-dashed">- - -</span> 行影响(FILTERS: ON/WHERE)</span>
        <span class="legend-item"><span class="line-dots">····</span> 行影响展开(表→列)</span>
      </div>
      <div class="canvas-container" :class="{ 'graph-fs': graphFullscreen }">
        <div ref="canvasRef" class="path-canvas"></div>
        <div class="zoom-toolbar" v-if="graph">
          <span class="zoom-ratio">{{ Math.round(zoomRatio * 100) }}%</span>
          <el-button size="small" :icon="Plus" circle title="放大" @click="zoomIn" />
          <el-button size="small" :icon="Minus" circle title="缩小" @click="zoomOut" />
          <el-button size="small" link type="primary" title="恢复全貌" @click="fitToView">适应画布</el-button>
          <el-button size="small" :icon="graphFullscreen ? Close : FullScreen" circle :title="graphFullscreen ? '退出全屏' : '全屏查看链路图'" @click="toggleGraphFullscreen" />
        </div>
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
import { Plus, Minus, FullScreen, Close } from '@element-plus/icons-vue'
import G6 from '@antv/g6'
import { traceUpstreamSubgraph, traceDownstreamSubgraph, traceHopPaths } from './impactListUtils'

const props = defineProps({
  visible: Boolean,
  row: Object,
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  sourceId: String,
  maxDepth: { type: Number, default: null }
})
const emit = defineEmits(['update:visible', 'viewScript'])

const canvasRef = ref(null)
const graph = ref(null)
const subgraph = ref(null)
const loaded = ref(false)
const selectedNode = ref(null)
const hopPaths = ref([])
// 链路图全屏：只把图区 fixed 铺满视口（标题/路径表/图例仍在弹窗里被盖住），
// 不动 DOM 结构，避免 G6 实例销毁重建；退出后画布缩回弹窗内原位
const graphFullscreen = ref(false)
async function toggleGraphFullscreen() {
  graphFullscreen.value = !graphFullscreen.value
  await nextTick()
  await new Promise(r => setTimeout(r, 120))
  if (graph.value && canvasRef.value) {
    graph.value.changeSize(canvasRef.value.clientWidth, canvasRef.value.clientHeight)
    graph.value.fitView(20)
    zoomRatio.value = graph.value.getZoom()
  }
}

// ---- 缩放工具条 ----
// 复杂链路（多层 DAG）fitView 后节点过小，提供 +/-/适应画布 三键缩放；
// 下限必须低于最复杂链路的 fit 比例（实测 11 跳约 0.24，否则点"-"会被下限顶成放大）；
// viewportchange 同步滚轮缩放的比例显示
const ZOOM_MIN = 0.1
const ZOOM_MAX = 4
const ZOOM_STEP = 0.2
const zoomRatio = ref(1)
function clampZoom(z) { return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z)) }
// 以画布中心为锚点缩放，避免缩放后视口偏到一角
function zoomBy(step) {
  if (!graph.value) return
  const center = { x: graph.value.getWidth() / 2, y: graph.value.getHeight() / 2 }
  graph.value.zoomTo(clampZoom(graph.value.getZoom() + step), center)
  zoomRatio.value = graph.value.getZoom()
}
function zoomIn() { zoomBy(ZOOM_STEP) }
function zoomOut() { zoomBy(-ZOOM_STEP) }
function fitToView() {
  if (!graph.value) return
  graph.value.fitView(20)
  zoomRatio.value = graph.value.getZoom()
}

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

// 临时表判定对 TABLE 节点(schema.table)与 FIELD 节点(schema.table.field)通用：
// 表名均为 id 的第 2 段；优先用权威标志 temp/isTemp（覆盖不带 vt_ 前缀的临时表），前缀兜底
function isTempNode(n) {
  if (n.temp || n.isTemp) return true
  const parts = (n.id || '').split('.')
  return parts.length >= 2 && parts[1].toLowerCase().startsWith('vt_')
}
// 着色与图例对齐：临时表橙 / Transform 灰 / 物理表按 layer（FDM 绿、CDM/DWD 蓝）。
// TABLE 节点不再用图例外的紫色，避免临时表节点底色与图例不符
function nodeColor(n) {
  if (n.nodeLevel === 'TRANSFORM') return '#909399'
  if (isTempNode(n)) return LAYER_COLORS.TEMP
  return LAYER_COLORS[n.layer] || '#409eff'
}

async function handleOpen() {
  await nextTick()
  loaded.value = false
  hopPaths.value = []
  if (!props.row) return
  // 逐跳路径回溯（方案A）：仅下游字段级行；双通道（值+行），跨 proc 完整展示；
  // maxDepth 预算内路径才展示（超预算字段的行已回落首达层级，长路拼接路径不展示）
  if (!props.row.upstream && props.row.targetId && props.row.field) {
    hopPaths.value = traceHopPaths(props.nodes, props.edges, props.row.targetId, props.sourceId, 16, props.row.level, props.maxDepth)
  }
  // UPSTREAM 行: 来源字段在该行脚本中的正向流向（traceUpstream 的镜像）;
  // DOWNSTREAM 行: 目标字段到分析起点的完整反向回溯——不按 procName 过滤：
  // 跨 proc 多跳链路的首跳边（FILTERS/ROW_IMPACT 属上游 proc）被过滤会导致
  // 图中只剩最后一跳、看不到分析起点，与标题/路径表的完整链路语义不一致
  subgraph.value = props.row.upstream
    ? traceDownstreamSubgraph(props.nodes, props.edges, props.row.targetId, props.row.procName)
    : traceUpstreamSubgraph(props.nodes, props.edges, props.row.targetId)
  loaded.value = true
  await renderGraph()
}

function handleClosed() {
  destroyGraph()
  subgraph.value = null
  loaded.value = false
  selectedNode.value = null
  graphFullscreen.value = false
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
    // 布局落定后再做遮挡检测：直线压到中间节点的边改为最小弧度绕行（不改变布局）
    rerouteOccludedEdges(graph.value, data)
    graph.value.fitView(20)
    zoomRatio.value = graph.value.getZoom()
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
  // ROW_IMPACT（表→列）行影响展开边必须画：弹窗子图只含当前链路节点（非全表所有列），
  // 纯行影响终点字段唯一的入边就是 ROW_IMPACT，滤掉会导致目标列节点孤立、链路断成两截
  // 无文本的边不能带 labelCfg：G6 对空 label 也渲染白色背景矩形，
  // 会遮住曲线顶点一段（值影响灰线视觉上"断开"）
  // 视觉去重：同 (source,target) 同视觉类型的多条边合并为一条（同 proc 多块重复 JOIN/WHERE 等），
  // label 追加 ×N 标记；不同视觉类型（值/JOIN/WHERE/行影响）各自保留。
  // 注意 edge.id 仍从原始 edges 下标派生 pe-<i>，点击边回溯原边索引用 indexMap
  const groups = new Map()
  const indexMap = []
  sg.edges.forEach((e, i) => {
    const isRowImpact = e.edgeType === 'ROW_IMPACT'
    const isCond = !isRowImpact && (e.role === 'join_condition' || e.role === 'where_condition')
    const kind = isRowImpact ? 'row' : (isCond ? 'join' : 'value')
    const key = `${e.source}|${e.target}|${kind}`
    if (!groups.has(key)) {
      groups.set(key, { source: e.source, target: e.target, kind, label: isRowImpact ? '行影响' : (isCond ? (e.role === 'join_condition' ? 'ON' : 'WHERE') : ''), style: {
        stroke: isRowImpact || isCond ? '#e6a23c' : '#909399',
        lineWidth: 1.5,
        lineDash: isRowImpact ? [2, 2] : (isCond ? [6, 4] : undefined),
        endArrow: true
      }, procs: new Set(), firstIdx: i })
    }
    const g = groups.get(key)
    if (e.procName) g.procs.add(e.procName)
    indexMap.push(i) // merged 下标 → 原始边下标
  })
  const edges = [...groups.values()].map((g, mi) => {
    const edge = {
      id: `pe-${g.firstIdx}`, // 用组内首条原始边的下标，保证 viewScript 回溯有效
      source: g.source,
      target: g.target,
      label: g.procs.size > 1 ? `${g.label || ''} ×${g.procs.size}`.trim() : g.label,
      type: 'quadratic',
      style: g.style
    }
    if (g.label) edge.labelCfg = { style: { fontSize: 10, fill: '#e6a23c', background: { fill: '#fff', padding: [2, 4, 2, 4] } } }
    return edge
  })
  return { nodes, edges }
}

// ---- 跨层边最小弧度绕行 ----
// 布局完成后做几何遮挡检测：直线（弦）压到中间节点的边改为弧线绕行，
// 弧度取"恰好不挨着被压节点"的最小值（按抛物线偏差剖面精确计算），
// 不压任何节点的边保留直线。方向上下交替，避免多条弧线叠在一侧。
function rerouteOccludedEdges(g6Graph, g6Data) {
  const nodesInfo = g6Data.nodes.map(n => {
    const it = g6Graph.findById(n.id)
    if (!it) return null
    const m = it.getModel()
    const isT = n.nodeLevel === 'TRANSFORM'
    return { id: n.id, x: m.x, y: m.y, w: isT ? 26 : 150, h: isT ? 26 : 36 }
  }).filter(Boolean)
  const rectOf = id => nodesInfo.find(n => n.id === id)
  const segHitsRect = (x1, y1, x2, y2, r) => {
    const steps = 24
    for (let i = 0; i <= steps; i++) {
      const t = i / steps, x = x1 + (x2 - x1) * t, y = y1 + (y2 - y1) * t
      if (x >= r.x - r.w / 2 - 4 && x <= r.x + r.w / 2 + 4 && y >= r.y - r.h / 2 - 4 && y <= r.y + r.h / 2 + 4) return true
    }
    return false
  }
  const EPS = 8 // 弧顶与节点边缘的最小间隙
  let alt = 0
  for (const e of g6Data.edges) {
    if (e.source === e.target) continue
    const a = rectOf(e.source), b = rectOf(e.target)
    if (!a || !b) continue
    const hit = nodesInfo.filter(n => n.id !== e.source && n.id !== e.target && segHitsRect(a.x, a.y, b.x, b.y, n))
    if (!hit.length) continue
    const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy)
    if (!len) continue
    const ux = dx / len, uy = dy / len
    const side = (alt % 2 === 0) ? 1 : -1
    const nx = -uy * side, ny = ux * side
    let s = 0
    for (const n of hit) {
      const px = n.x - a.x, py = n.y - a.y
      const along = px * ux + py * uy
      const dSigned = px * nx + py * ny
      const rN = (n.w / 2) * Math.abs(ny) + (n.h / 2) * Math.abs(nx)
      const need = dSigned + rN + EPS
      if (need <= 0) continue // 节点在弧背侧，不构成遮挡
      const u = Math.max(-0.85, Math.min(0.85, (along / len) * 2 - 1))
      s = Math.max(s, need / (1 - u * u))
    }
    if (s <= 0) { alt++; continue }
    const off = Math.round(2 * s) // quadratic 控制点偏移 = 2×弦中点 sagitta
    alt++
    g6Graph.updateItem(e.id, { type: 'quadratic', curveOffset: off, curvePosition: 0.5 })
  }
}

function shortLabel(n) {
  if (n.nodeLevel === 'FIELD') {
    const parts = (n.id || '').split('.')
    return parts.length >= 3 ? `${parts[1]}.${parts[2]}` : (n.label || n.id)
  }
  return n.label || n.id
}
function truncate(s, len) { return s && s.length > len ? s.slice(0, len) + '...' : (s || '') }

// ---- 逐跳路径表辅助 ----
// id 显示：字段取 table.field，表取 schema.table（全名 tooltip 由 show-overflow-tooltip 承担）
function shortId(id) {
  const parts = (id || '').split('.')
  if (parts.length >= 3) return `${parts[1]}.${parts[2]}`
  return id || ''
}
function stepTypeLabel(s) {
  if (s.type === 'FILTERS') return s.role === 'where_condition' ? '行过滤·WHERE' : '行过滤·JOIN'
  return '值传播'
}
function stepTagType(s) {
  return s.type === 'FILTERS' ? 'danger' : 'success'
}
function stepDetail(s) {
  if (s.type === 'FILTERS') {
    if (s.expanded) {
      const cond = s.role === 'where_condition' ? 'WHERE' : 'JOIN'
      const base = s.clause ? `${s.procName || '-'} · ${s.clause}` : `${s.procName || '-'} · 表内列行集受${cond}条件影响`
      return s.expression ? `${base} · 值经 ${s.expression}` : base
    }
    const base = `${s.procName || '-'} · ${s.clause || '-'}`
    return s.expression ? `${base} · 值经 ${s.expression}` : base
  }
  if (s.type === 'VALUE') return `${s.procName || '-'} · ${s.expression || '-'}`
  return s.procName || '直接取值'
}

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
  // 滚轮缩放/拖拽平移后同步工具条比例显示
  graph.value.on('viewportchange', () => {
    if (graph.value) zoomRatio.value = graph.value.getZoom()
  })
  graph.value.on('node:click', evt => {
    if (!evt.item || evt.item.destroyed) return
    const node = subgraph.value?.nodes.find(n => n.id === evt.item.getID())
    selectedNode.value = node || null
  })
  graph.value.on('edge:click', evt => {
    if (!evt.item || evt.item.destroyed) return
    const idx = Number(String(evt.item.getID()).replace('pe-', ''))
    // 与 toG6Data 一致：边 id 按子图边序号编号（含 ROW_IMPACT），索引直接对齐
    const edge = (subgraph.value?.edges || [])[idx]
    if (edge && edge.procName) emit('viewScript', edge)
  })
}

onBeforeUnmount(destroyGraph)
</script>

<style scoped>
/* 链路图全屏：图区 fixed 铺满视口盖住弹窗（同在 el-overlay 层叠上下文内，
   z-index 高于弹窗内容即可），白底防透视；退出移除类名即回弹窗原位 */
.canvas-container.graph-fs { position: fixed; inset: 0; z-index: 3000; border: none; background: #fff; }
/* overflow:hidden 兜底：路径表(≤32vh)+图(min-height 150)在小屏窗口下也不再溢出弹窗 */
.path-dialog-body { display: flex; flex-direction: column; height: 72vh; overflow: hidden; }
.summary { display: flex; align-items: center; gap: 10px; padding: 2px 0 8px; }
.summary-text { font-size: 13px; color: #303133; }
.summary .meta { color: #909399; font-size: 12px; margin-left: auto; }
/* flex-shrink:0 防止路径区被 canvas 的 min-height 挤压（窄窗口下曾压到 14px 触发滚动条）；
   max-height 用 min(300px,32vh)：大屏保持 300px，矮窗口自动收缩，给图留出空间 */
.hop-paths { flex-shrink: 0; max-height: min(300px, 32vh); overflow: auto; margin-bottom: 8px; border-bottom: 1px dashed #ebeef5; }
.hop-path { margin-bottom: 10px; }
.hop-path-title { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.hop-tip { font-size: 12px; color: #909399; }
.hop-node { font-family: Menlo, Consolas, monospace; font-size: 12px; }
.hop-arrow { color: #909399; }
.hop-final { color: #f56c6c; font-weight: 600; }
.legend { padding: 2px 0 6px; font-size: 12px; color: #606266; }
.legend-item { margin-right: 14px; }
.legend-item .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.dot.fdm { background: #67c23a; } .dot.cdm { background: #409eff; }
.dot.temp { background: #e6a23c; } .dot.transform { background: #909399; }
.line-solid { color: #909399; } .line-dashed { color: #e6a23c; } .line-dots { color: #e6a23c; letter-spacing: 2px; }
.canvas-container { flex: 1; position: relative; overflow: hidden; min-height: 150px; border: 1px solid #ebeef5; }
/* 右上角悬浮缩放工具条：白底半透明不挡图，比例文字等宽字体对齐 */
.zoom-toolbar { position: absolute; top: 8px; right: 10px; z-index: 2; display: flex; align-items: center; gap: 6px; padding: 3px 8px; background: rgba(255, 255, 255, 0.9); border: 1px solid #ebeef5; border-radius: 4px; }
.zoom-ratio { font-family: Menlo, Consolas, monospace; font-size: 12px; color: #606266; min-width: 38px; text-align: center; }
.path-canvas { width: 100%; height: 100%; }
.node-panel { padding-top: 8px; }
</style>
