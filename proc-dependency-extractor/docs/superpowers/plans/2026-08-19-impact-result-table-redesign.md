# 影响分析结果表格化重构 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 影响分析结果从全景图谱改为"层级 Tab + 受影响清单表格 + 链路弹窗 + Excel 导出"。

**架构：** 纯前端改造。新增纯函数模块（层级最长路径计算/行推导/链路回溯）+ 两个新组件（层级表格、G6 链路弹窗），ImpactAnalysis.vue 主区域集成替换，后端零改动。

**技术栈：** Vue 3 `<script setup>`、element-plus、@antv/g6@4.8.24（已有）、xlsx@0.18.5（新增）、vite。

**规格：** `docs/superpowers/specs/2026-08-19-impact-result-table-redesign-design.md`

---

## 数据结构备忘（所有任务共用，先读一遍）

后端 `/api/impact/analyze` 响应（`result.value = res.data`）：

```
result.nodes:   [{ id, label, nodeLevel: 'FIELD'|'TRANSFORM', layer, tableFullName, depth, procName, scriptFile, expression, logicType }]
result.edges:   [{ source, target, edgeType: 'DERIVED_FROM'|'INPUT_TO'|'OUTPUT_TO', procName, role, sourceAlias, expression }]
result.impactedByDepth: { [depth]: [{ name, layer, temp, scriptFile, shortName, columns, matchedColumns, impactSource }] }   // 仅表级
result.sourceTable / result.sourceLayer / result.stats / result.reportImpacts
```

字段级边语义（源码 ImpactAnalysisServiceImpl.java L697-782）：
- `DERIVED_FROM`：field→field（DIRECT 型）。无 role=直接派生；`role=join_condition/where_condition`=条件影响（target 为受影响字段）
- `INPUT_TO`：field→Transform，role 含条件角色（target 是 Transform，不是交付物）
- `OUTPUT_TO`：Transform→field（field 为受影响字段）
- 字段 id（col_key）格式：`schema.table.field` 全小写；临时表命名 `vt_` 前缀

表级边（collectEdges L860-916）：无 edgeType、带 `operation`/`procName`；表级分析响应的所有边都是 READS 语义。

**关键规则（规格 §3/§4）：**
- 层级 = 穿透临时表（`vt_` 前缀表 / 表级 `temp` 标记）和 Transform 节点后的最长路径跳数，起点=0
- 行粒度：字段级 `(脚本, 受影响表, 受影响字段, 原因)`；表级 `(脚本, 受影响表)`
- OUTPUT_TO 边的受影响字段要聚合其 Transform 的条件 INPUT_TO role 作为额外行

---

### 任务 1：impactListUtils.js 纯函数模块

**文件：**
- 创建：`frontend/src/components/impact/impactListUtils.js`

- [ ] **步骤 1：创建模块，实现全部导出函数**

```javascript
// 影响分析清单化展示的纯函数工具：层级计算（最长路径+临时表/Transform穿透）、清单行推导、链路回溯

const CONDITION_ROLE_LABELS = {
  join_condition: 'JOIN 条件',
  where_condition: 'WHERE 条件'
}

// 字段 id（schema.table.field）的表名部分是否临时表（vt_ 前缀，与解析器建临时表命名一致）
export function isTempFieldName(nodeId) {
  const parts = (nodeId || '').split('.')
  return parts.length >= 2 && parts[1].toLowerCase().startsWith('vt_')
}

/**
 * 最长路径层级计算（规格 §3.2）。
 * 松弛 depth[t] = max(depth[t], depth[s] + inc(t))，inc: 透明节点(临时表/Transform)=0，其余=1。
 * 环防护：松弛轮次上限 edges.length + 1，超限整体回退 BFS 最浅深度。
 * @param nodes [{id, nodeLevel, temp?}]  @param edges [{source, target}]  @param startId 起点id
 * @param isTransparent (node) => bool  @return Map<id, depth>（不可达节点不在 Map 中）
 */
export function computeLongestDepth(nodes, edges, startId, isTransparent) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  const depth = new Map([[startId, 0]])
  const maxRounds = edges.length + 1
  let round = 0
  let changed = true
  while (changed && round < maxRounds) {
    changed = false
    round++
    for (const e of edges) {
      const ds = depth.get(e.source)
      if (ds === undefined) continue
      const tNode = nodeMap.get(e.target)
      if (!tNode) continue
      const candidate = ds + (isTransparent(tNode) ? 0 : 1)
      if (candidate > (depth.get(e.target) ?? -1)) {
        depth.set(e.target, candidate)
        changed = true
      }
    }
  }
  if (round >= maxRounds && changed) {
    // 存在环：回退 BFS 最浅深度（临时节点 inc 同样为 0），保证所有节点有确定层级
    return bfsShallowDepth(nodeMap, edges, startId, isTransparent)
  }
  return depth
}

function bfsShallowDepth(nodeMap, edges, startId, isTransparent) {
  const outEdges = new Map()
  for (const e of edges) {
    if (!outEdges.has(e.source)) outEdges.set(e.source, [])
    outEdges.get(e.source).push(e)
  }
  const depth = new Map([[startId, 0]])
  const queue = [startId]
  while (queue.length) {
    const cur = queue.shift()
    for (const e of (outEdges.get(cur) || [])) {
      if (depth.has(e.target)) continue
      const tNode = nodeMap.get(e.target)
      if (!tNode) continue
      depth.set(e.target, depth.get(cur) + (isTransparent(tNode) ? 0 : 1))
      queue.push(e.target)
    }
  }
  return depth
}

/**
 * 字段级清单行推导（规格 §4.1）。
 * 只为"非临时表的 FIELD 节点"（排除起点）生成行；
 * DERIVED_FROM 入边：无 role=直接来源，有 role=条件原因；
 * OUTPUT_TO 入边：直接来源 + 聚合该 Transform 的条件 INPUT_TO role；
 * INPUT_TO（target=Transform）不生成行。
 * @return [{ procName, table, field, reason, level, targetId }] 按 level/表/字段排序
 */
export function buildFieldImpactRows(nodes, edges, startFieldId) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  const depth = computeLongestDepth(nodes, edges, startFieldId,
    n => n.nodeLevel === 'TRANSFORM' || isTempFieldName(n.id))
  const rows = []
  const seen = new Set()
  const addRow = (targetNode, procName, reason) => {
    const key = `${procName}|${targetNode.id}|${reason}`
    if (seen.has(key)) return
    seen.add(key)
    const parts = targetNode.id.split('.')
    rows.push({
      procName: procName || '-',
      table: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : targetNode.id,
      field: parts[parts.length - 1],
      reason,
      level: depth.get(targetNode.id),
      targetId: targetNode.id
    })
  }
  for (const e of edges) {
    const t = nodeMap.get(e.target)
    if (!t || t.nodeLevel !== 'FIELD' || t.id === startFieldId) continue
    if (isTempFieldName(t.id) || !depth.has(t.id)) continue
    if (e.edgeType === 'OUTPUT_TO') {
      addRow(t, e.procName, '直接来源')
      // 聚合该 Transform 的条件输入（同一 procName）
      for (const ie of edges) {
        if (ie.target === e.source && ie.procName === e.procName && CONDITION_ROLE_LABELS[ie.role]) {
          addRow(t, e.procName, CONDITION_ROLE_LABELS[ie.role])
        }
      }
    } else if (e.edgeType === 'DERIVED_FROM') {
      addRow(t, e.procName, e.role ? (CONDITION_ROLE_LABELS[e.role] || e.role) : '直接来源')
    }
  }
  rows.sort((a, b) => (a.level ?? 99) - (b.level ?? 99)
    || a.table.localeCompare(b.table) || a.field.localeCompare(b.field))
  return rows
}

/**
 * 表级数据构建（规格 §4.2，迁移并改造原 ImpactAnalysis.vue buildWithoutTemp）。
 * 在全图（含临时表）上算最长路径（临时表透明=穿透），返回行+全图（弹窗链路需要含临时表）。
 * @param impactedByDepth 后端 result.impactedByDepth
 * @return { rows: [{procName, table, level, targetId}], nodes, edges, depthMap }
 *   nodes/edges 为含临时表的全图（节点含 temp/scriptFile/shortName），行只含非临时表
 */
export function buildTableImpactData(sourceTable, impactedByDepth) {
  // 全图邻接与信息
  const tableInfoMap = {}
  const allEdges = []
  for (const tables of Object.values(impactedByDepth || {})) {
    for (const t of tables) {
      tableInfoMap[t.name] = t
      if (t.impactSource) allEdges.push({ source: t.impactSource, target: t.name })
    }
  }
  const allNodes = Object.keys(tableInfoMap).map(name => ({
    id: name,
    label: name === sourceTable ? name : (tableInfoMap[name].shortName || name),
    layer: tableInfoMap[name].layer || 'UNKNOWN',
    temp: !!tableInfoMap[name].temp,
    scriptFile: tableInfoMap[name].scriptFile,
    nodeLevel: 'TABLE'
  }))
  const nodeMap = new Map(allNodes.map(n => [n.id, n]))
  const depthMap = computeLongestDepth(allNodes, allEdges, sourceTable, n => n.temp)

  // 行：非临时受影响表的每个写入脚本一行（从全图入边取 distinct procName）
  const rows = []
  const seen = new Set()
  for (const e of allEdges) {
    const t = nodeMap.get(e.target)
    if (!t || t.temp || e.target === sourceTable || !depthMap.has(e.target)) continue
    const procName = tableInfoMap[e.target].scriptFile || '-'
    const key = `${procName}|${e.target}`
    if (seen.has(key)) continue
    seen.add(key)
    rows.push({ procName, table: e.target, level: depthMap.get(e.target), targetId: e.target })
  }
  rows.sort((a, b) => a.level - b.level || a.table.localeCompare(b.table))
  return { rows, nodes: allNodes, edges: allEdges, depthMap }
}

/**
 * 链路回溯（规格 §5.1）：从 targetId 沿边反向可达子图，只走 procName 匹配的边
 * （procName 为空的边保留，避免链路断裂；procName 非空且不等于指定值的跳过）。
 * @return { nodes, edges } 反向可达子图
 */
export function traceUpstreamSubgraph(nodes, edges, targetId, procName) {
  const inEdges = new Map()
  for (const e of edges) {
    if (!inEdges.has(e.target)) inEdges.set(e.target, [])
    inEdges.get(e.target).push(e)
  }
  const keepNodeIds = new Set([targetId])
  const keepEdges = []
  const queue = [targetId]
  while (queue.length) {
    const cur = queue.shift()
    for (const e of (inEdges.get(cur) || [])) {
      if (procName && e.procName && e.procName !== procName) continue
      keepEdges.push(e)
      if (!keepNodeIds.has(e.source)) {
        keepNodeIds.add(e.source)
        queue.push(e.source)
      }
    }
  }
  return {
    nodes: nodes.filter(n => keepNodeIds.has(n.id)),
    edges: keepEdges
  }
}

// 层级数字转中文标签（Tab 用）：1→一、2→二 … 10→十，>10 用数字
export function levelLabel(level) {
  const cn = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十']
  return level >= 0 && level <= 10 ? cn[level] : String(level)
}
```

- [ ] **步骤 2：构建验证**

运行（frontend 目录）：`npm run build`
预期：编译通过（新文件未被引用也参与构建无碍；若无 lint 则以 vite build 成功为准）

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/components/impact/impactListUtils.js
git commit -m "feat: add impact list utils (longest-depth, rows, path trace)"
```

---

### 任务 2：ImpactPathDialog.vue 链路弹窗组件

**文件：**
- 创建：`frontend/src/components/impact/ImpactPathDialog.vue`
- 参考（G6 用法照抄其模式）：`frontend/src/components/FieldLineageDialog.vue`（initGraph/renderGraph/destroy 生命周期、dagre LR 布局、quadratic 边）

- [ ] **步骤 1：创建组件**

props：`visible: Boolean`、`row: Object`（清单行：procName/table/field/reason/level/targetId）、`nodes: Array`、`edges: Array`（当前分析全量，字段级=result.nodes/edges，表级=buildTableImpactData 返回的含临时表全图）、`sourceId: String`（分析起点 id）。

事件：`@viewScript(edge)`——边点击且边带 procName 时抛出（父组件打开 ScriptSnippetModal）。

```vue
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
        <el-tag size="small">{{ row.reason }}</el-tag>
        <span class="summary-text">
          {{ sourceId }} →（{{ row.level }} 跳）→ {{ row.table }}{{ row.field ? '.' + row.field : '' }}
        </span>
        <span class="meta" v-if="subgraph">节点 {{ subgraph.nodes.length }} · 边 {{ subgraph.edges.length }}</span>
      </div>
      <div class="legend">
        <span class="legend-item"><i class="dot fdm"></i>FDM</span>
        <span class="legend-item"><i class="dot cdm"></i>CDM/DWD</span>
        <span class="legend-item"><i class="dot temp"></i>临时表</span>
        <span class="legend-item"><i class="dot transform"></i>Transform</span>
        <span class="legend-item"><span class="line-solid">——</span> 直接派生</span>
        <span class="legend-item"><span class="line-dashed">- - -</span> 条件影响(ON/WHERE)</span>
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
import { traceUpstreamSubgraph } from './impactListUtils'

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

const dialogTitle = computed(() =>
  props.row ? `影响链路：${props.row.table}${props.row.field ? '.' + props.row.field : ''}` : '影响链路')

const LAYER_COLORS = {
  FDM: '#67c23a',   // 绿
  CDM: '#409eff',   // 蓝
  DWM: '#409eff',
  DWD: '#409eff',
  TEMP: '#e6a23c'   // 橙
}

function nodeColor(n) {
  if (n.nodeLevel === 'TRANSFORM') return '#909399'
  if (n.temp || (n.id && n.id.split('.').length >= 2 && n.id.split('.')[1].toLowerCase().startsWith('vt_'))) return LAYER_COLORS.TEMP
  return LAYER_COLORS[n.layer] || '#409eff'
}

async function handleOpen() {
  await nextTick()
  loaded.value = false
  if (!props.row) return
  subgraph.value = traceUpstreamSubgraph(props.nodes, props.edges, props.row.targetId, props.row.procName)
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
  const data = toG6Data(subgraph.value)
  graph.value.data(data)
  graph.value.render()
  await new Promise(r => setTimeout(r, 100))
  graph.value.fitView(20)
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
  const edges = sg.edges.map((e, i) => {
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
    const node = subgraph.value?.nodes.find(n => n.id === evt.item.getID())
    selectedNode.value = node || null
  })
  graph.value.on('edge:click', evt => {
    const idx = Number(String(evt.item.getID()).replace('pe-', ''))
    const edge = subgraph.value?.edges[idx]
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
```

注意：G6 点击事件里 `evt.item.getID()`；Transform 节点 size 用数字（圆点效果配 `type: 'rect'` 会是小方块，可接受，或改 `type: 'circle'`——保持 rect 小方块即可，与图例"Transform 灰色小点"含义一致即可）。若 G6 报 quadratic 边 lineDash 警告属正常。

- [ ] **步骤 2：构建验证**

运行（frontend 目录）：`npm run build`
预期：编译通过

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/components/impact/ImpactPathDialog.vue
git commit -m "feat: add ImpactPathDialog (per-proc upstream path subgraph)"
```

---

### 任务 3：ImpactResultTabs.vue + xlsx 导出 + ImpactAnalysis.vue 集成

**文件：**
- 修改：`frontend/package.json`（加 xlsx 依赖）
- 创建：`frontend/src/components/impact/ImpactResultTabs.vue`
- 修改：`frontend/src/views/ImpactAnalysis.vue`（主区域替换，删除图谱/传播树/详情面板/筛选，保留 ImpactStats、报表影响表、ScriptSnippetModal）

- [ ] **步骤 1：安装 xlsx**

运行（frontend 目录）：`npm install xlsx@0.18.5`
预期：package.json dependencies 出现 `"xlsx": "^0.18.5"`，node_modules 安装成功

- [ ] **步骤 2：创建 ImpactResultTabs.vue**

props：`rows: Array`（全部层级行，字段级/表级统一结构）、`isFieldLevel: Boolean`。事件：`@viewScript(row)`、`@viewPath(row)`。

```vue
<template>
  <div class="impact-result-tabs">
    <div class="tabs-bar">
      <el-tabs v-model="activeTab">
        <el-tab-pane
          v-for="lv in levels"
          :key="lv"
          :name="String(lv)"
          :label="`第${levelLabel(lv)}层级（${rowsByLevel[lv].length}）`"
        >
          <el-table :data="rowsByLevel[lv]" border size="small" style="width: 100%">
            <el-table-column prop="procName" label="受影响脚本" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link type="primary" @click="$emit('viewScript', row)">{{ row.procName }}</el-link>
              </template>
            </el-table-column>
            <el-table-column prop="table" label="受影响表" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="isFieldLevel" prop="field" label="受影响字段" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="isFieldLevel" prop="reason" label="影响原因" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.reason !== '直接来源'" type="warning" size="small">{{ row.reason }}</el-tag>
                <el-tag v-else type="success" size="small">直接来源</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="$emit('viewPath', row)">链路图</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <el-button size="small" type="success" plain class="export-btn" @click="exportAll">导出 Excel</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as XLSX from 'xlsx'
import { levelLabel } from './impactListUtils'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  isFieldLevel: Boolean
})
defineEmits(['viewScript', 'viewPath'])

const rowsByLevel = computed(() => {
  const map = {}
  for (const r of props.rows) {
    const lv = r.level ?? 0
    if (!map[lv]) map[lv] = []
    map[lv].push(r)
  }
  return map
})
const levels = computed(() => Object.keys(rowsByLevel.value).map(Number).sort((a, b) => a - b))

const activeTab = ref('1')
watch(levels, (lvs) => {
  if (!lvs.length || lvs.map(String).includes(activeTab.value)) return
  activeTab.value = String(lvs[0])
}, { immediate: true })

function exportAll() {
  if (!props.rows.length) return
  const header = props.isFieldLevel
    ? ['层级', '受影响脚本', '受影响表', '受影响字段', '影响原因']
    : ['层级', '受影响脚本', '受影响表']
  const data = props.rows.map(r => props.isFieldLevel
    ? [`第${levelLabel(r.level ?? 0)}层级`, r.procName, r.table, r.field, r.reason]
    : [`第${levelLabel(r.level ?? 0)}层级`, r.procName, r.table])
  const ws = XLSX.utils.aoa_to_sheet([header, ...data])
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '受影响清单')
  const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  XLSX.writeFile(wb, `影响分析_${stamp}.xlsx`)
  ElMessage.success(`已导出 ${props.rows.length} 行`)
}
</script>

<style scoped>
.tabs-bar { position: relative; }
.export-btn { position: absolute; right: 0; top: 2px; z-index: 1; }
</style>
```

（导出文件名的"源标识"由父组件通过 `exportFileName` prop 传入更完整——实现时加 prop `exportFileName: String`，`XLSX.writeFile(wb, \`${props.exportFileName || '影响分析'}_${stamp}.xlsx\`)`。）

- [ ] **步骤 3：改造 ImpactAnalysis.vue**

**模板**：`<el-row>…</el-row>`（现 L18-132 整块，含图谱筛选工具栏、ImpactFlowGraph、传播树、右侧详情 el-col）替换为：

```vue
    <el-alert
      v-if="result && !impactRows.length"
      title="未分析到受影响对象"
      type="info"
      :closable="false"
      style="margin-bottom: 10px"
    />

    <ImpactResultTabs
      v-if="impactRows.length"
      :rows="impactRows"
      :isFieldLevel="isFieldLevel"
      :exportFileName="exportFileName"
      @viewScript="handleRowViewScript"
      @viewPath="pathDialogVisible = true; pathDialogRow = $event"
    />

    <div class="propagation-section" v-if="result?.reportImpacts?.length">
      <!-- 报表影响表格整段保留原样（现 L95-108） -->
      …原样保留…
    </div>

    <ImpactPathDialog
      v-if="pathDialogVisible"
      :visible="pathDialogVisible"
      :row="pathDialogRow"
      :nodes="pathDialogNodes"
      :edges="pathDialogEdges"
      :sourceId="sourceNodeId"
      @update:visible="pathDialogVisible = $event"
      @viewScript="handlePathEdgeViewScript"
    />

    <ScriptSnippetModal …原样保留… />
```

**script setup**：
- import 改动：删 `ImpactFlowGraph/ImpactNodeDetail/ImpactEdgeDetail/ImpactPropagationTree`，删 `import { analyze } from '@/api/impact'` 中的 `expand`（该文件本就只 import analyze，无需动）；新增 `import ImpactResultTabs from '@/components/impact/ImpactResultTabs.vue'`、`import ImpactPathDialog from '@/components/impact/ImpactPathDialog.vue'`、`import { buildFieldImpactRows, buildTableImpactData } from '@/components/impact/impactListUtils'`
- 删除状态：`graphRef/selectedNode/selectedEdge/showDetail/collapseTempTables/filterSchemas/filterTableKw/hideUnmatched/graphInitialNodes/graphInitialEdges/allNodesByDepth/allEdgesList/visibleNodeIds/DEFAULT_VISIBLE_DEPTH` 及 computed `availableSchemas/matchedNodeIds/preservedNodeIds`、函数 `clearFilter/updateVisibleGraph/buildWithoutTemp/handleNodeClick/handleEdgeClick/handleExpand/handleCollapse/handleViewScript`（若传播树删除后无其他引用）
- 新增状态与逻辑：

```javascript
const impactRows = ref([])
const pathDialogVisible = ref(false)
const pathDialogRow = ref(null)
const pathDialogNodes = ref([])
const pathDialogEdges = ref([])
const sourceNodeId = ref('')
const exportFileName = ref('影响分析')

// handleAnalyze 内，try 块替换为：
const res = await analyze(request)
result.value = res.data
const fieldLevel = !!(request.fieldName && request.fieldName !== '')

if (fieldLevel) {
  const nodes = res.data?.nodes || []
  const edges = res.data?.edges || []
  const startId = `${request.schema}.${request.tableShortName}.${request.fieldName}`.toLowerCase()
  sourceNodeId.value = nodes.some(n => n.id === startId) ? startId
    : (nodes.find(n => n.depth === 0 && n.nodeLevel === 'FIELD')?.id || startId)
  impactRows.value = buildFieldImpactRows(nodes, edges, sourceNodeId.value)
  pathDialogNodes.value = nodes
  pathDialogEdges.value = edges
  exportFileName.value = `影响分析_${request.schema}.${request.tableShortName}.${request.fieldName}`
} else {
  const built = buildTableImpactData(res.data?.sourceTable, res.data?.impactedByDepth || {})
  sourceNodeId.value = res.data?.sourceTable || ''
  impactRows.value = built.rows
  pathDialogNodes.value = built.nodes
  pathDialogEdges.value = built.edges
  exportFileName.value = `影响分析_${res.data?.sourceTable || ''}`
}
```

（`request.schema/tableShortName/fieldName` 字段名以 `ImpactFilter.vue` emit 的实际请求体为准，实现前 Read 核对。）

- 新增行事件处理（对接现有 ScriptSnippetModal 的 props 结构 scriptFile/impactSource/targetTable）：

```javascript
function handleRowViewScript(row) {
  scriptModalData.value = {
    name: row.table,
    scriptFile: row.procName,
    impactSource: row.table
  }
  scriptModalVisible.value = true
}
function handlePathEdgeViewScript(edge) {
  scriptModalData.value = {
    name: edge.target,
    scriptFile: edge.procName,
    impactSource: edge.source
  }
  scriptModalVisible.value = true
}
```

（`ScriptSnippetModal` 的实际 props 含义 Read 核对后适配：它在 ImpactAnalysis.vue 现有用法是 `scriptFile/impactSource/targetTable`，传播树的 handleViewScript 传 `{name, scriptFile, impactSource}`——按现有映射照抄。）

- [ ] **步骤 4：构建验证**

运行（frontend 目录）：`npm run build`
预期：编译通过，无 unresolved import

- [ ] **步骤 5：Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/components/impact/ImpactResultTabs.vue frontend/src/views/ImpactAnalysis.vue
git commit -m "feat: replace impact graph with level tabs table + path dialog + excel export"
```

---

### 任务 4：端到端验证（控制者执行，不分派子代理）

前置：后端服务运行中（8080），前端 `npm run dev` 启动。

1. **字段级**：分析 `FDM / f_ac_ctds_ldr_acct / acct_no / DOWNSTREAM`
   - Tab 出现且 `profit.acct_no` 在"第二层级"（vt_06 穿透）
   - 存在 `JOIN 条件`/`WHERE 条件` 原因行
   - 点行"链路图"：弹窗只含该行 procName 的边（+空 procName 边），条件边虚线 ON/WHERE
   - 点"脚本"链接：ScriptSnippetModal 打开
   - 导出：xlsx 可打开，行数 = 所有 Tab 行数合计
2. **表级**：分析 `FDM / f_ac_ctds_ldr_acct / DOWNSTREAM`（不填字段）
   - Tab 按 buildTableImpactData 层级；行=脚本+表；无字段/原因列
   - 链路弹窗表级可用（含临时表节点，橙色）
3. **边界**：分析一个不存在的表 → 空态提示，无 JS 报错
4. `npm run build` 最终确认

---

## 自检记录

- 规格覆盖：§2 布局→任务 3；§3 层级→任务 1（computeLongestDepth + 两处调用）；§4 行推导→任务 1；§5 弹窗→任务 2；§6 导出→任务 3；§7 边界（空态 el-alert/环防护 BFS 回退/空 procName 边保留回溯/tooltip）→ 任务 1-3 分布覆盖；§8 验证→任务 4
- 占位符扫描：报表影响表格"原样保留"指现有 L95-108 不改动（有明确位置），非待定；两处"Read 核对"是给实现者的核对指令（ImpactFilter 请求体字段、ScriptSnippetModal props 映射），配套了 fallback 写法
- 类型一致性：rows 结构 `{procName, table, field, reason, level, targetId}` 在任务 1 产出、任务 2（row.targetId/row.field 可选）和任务 3（表格列/导出）消费一致；`levelLabel` 任务 1 定义、任务 3 使用；`traceUpstreamSubgraph` 任务 1 定义、任务 2 使用；`buildTableImpactData` 返回 `{rows, nodes, edges, depthMap}` 任务 1 定义、任务 3 消费
