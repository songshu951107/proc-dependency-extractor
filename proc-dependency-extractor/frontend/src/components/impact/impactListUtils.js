// 影响分析清单化展示的纯函数工具：层级计算（最长路径+临时表/Transform穿透）、清单行推导、链路回溯

const REASON_ORDER = ['直接来源', 'JOIN 条件', 'WHERE 条件']
const ROLE_TO_REASON = {
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
 * 字段级清单行推导（根因标签传播）。
 * 标签 = 起点进入链路那一跳的身份：起点出边无 role/未知 role → 直接来源，
 * join_condition → JOIN 条件，where_condition → WHERE 条件；
 * 起点→Transform（INPUT_TO）时标签授予该 Transform 的所有 OUTPUT_TO 目标字段。
 * BFS 继承传播：标签沿出边传给下游（经 Transform 穿透到其输出、经 DERIVED_FROM 直达），
 * 经过带角色的边（role=join_condition/where_condition）时标签跳变为该角色，无边角色
 * 的边继承当前标签——影响方式由路径上最近一次角色跳变决定；字段自身标签不变，起点不标记。
 * 行生成：到达边（DERIVED_FROM/OUTPUT_TO）触发行，行 key=(procName, targetId) 去重，
 * reasons = 字段标签集合（按 REASON_ORDER 排序），同一字段多原因聚合一行；
 * 无标签字段不生成行（可达判据）。level 仍取最长路径层级。
 * 补充行：仅产临时表的 proc（非临时交付物字段级血缘缺失）补临时表行（temp=true，
 * level 用非透明跳数），保证清单覆盖全部受影响脚本、与 stats 一致。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp }] 按 level/表/字段排序
 */
export function buildFieldImpactRows(nodes, edges, startFieldId) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  const depth = computeLongestDepth(nodes, edges, startFieldId,
    n => n.nodeLevel === 'TRANSFORM' || isTempFieldName(n.id))

  // 出边索引 + Transform 输出索引
  const outEdges = new Map()
  for (const e of edges) {
    if (!outEdges.has(e.source)) outEdges.set(e.source, [])
    outEdges.get(e.source).push(e)
  }
  const transformOutputs = new Map()
  for (const e of edges) {
    if (e.edgeType === 'OUTPUT_TO') {
      if (!transformOutputs.has(e.source)) transformOutputs.set(e.source, [])
      transformOutputs.get(e.source).push(e.target)
    }
  }
  const reasonOf = (e) => ROLE_TO_REASON[e.role] || '直接来源'

  const reasonMap = new Map() // fieldId -> Set<reason>
  const visited = new Set()   // `${fieldId}|${reason}`
  const queue = []
  const push = (fieldId, reason) => {
    if (fieldId === startFieldId) return
    const key = `${fieldId}|${reason}`
    if (visited.has(key)) return
    visited.add(key)
    if (!reasonMap.has(fieldId)) reasonMap.set(fieldId, new Set())
    reasonMap.get(fieldId).add(reason)
    queue.push([fieldId, reason])
  }
  // 初始标签：起点出边
  for (const e of (outEdges.get(startFieldId) || [])) {
    if (e.edgeType === 'INPUT_TO') {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'TRANSFORM') continue
      for (const out of (transformOutputs.get(e.target) || [])) push(out, reasonOf(e))
    } else {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'FIELD') continue
      push(e.target, reasonOf(e))
    }
  }
  // 继承传播：边带角色时标签跳变为该角色（影响方式由最近一次角色跳变决定），无边角色时继承
  while (queue.length) {
    const [cur, reason] = queue.shift()
    for (const e of (outEdges.get(cur) || [])) {
      const nextReason = ROLE_TO_REASON[e.role] || reason
      if (e.edgeType === 'INPUT_TO') {
        for (const out of (transformOutputs.get(e.target) || [])) push(out, nextReason)
      } else if (e.edgeType === 'DERIVED_FROM' || !e.edgeType) {
        const t = nodeMap.get(e.target)
        if (t && t.nodeLevel === 'FIELD') push(e.target, nextReason)
      }
    }
  }

  // 行生成（非临时表交付物）
  const rows = []
  const seen = new Set()
  for (const e of edges) {
    if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO') continue
    const t = nodeMap.get(e.target)
    if (!t || t.nodeLevel !== 'FIELD' || t.id === startFieldId) continue
    if (isTempFieldName(t.id) || !reasonMap.has(t.id)) continue
    const key = `${e.procName || '-'}|${t.id}`
    if (seen.has(key)) continue
    seen.add(key)
    const parts = t.id.split('.')
    rows.push({
      procName: e.procName || '-',
      table: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : t.id,
      field: parts[parts.length - 1],
      reasons: REASON_ORDER.filter(r => reasonMap.get(t.id).has(r)),
      level: depth.get(t.id),
      targetId: t.id,
      temp: false
    })
  }
  // 补充行：仅产临时表的 proc（其非临时交付物字段级血缘缺失，链路止于临时表）。
  // 只为"无非临时行"的 proc 生成临时表行，避免临时中转节点大量入表；层级用非透明跳数。
  const procsWithRows = new Set(rows.map(r => r.procName))
  if (rows.length) {
    const rawDepth = computeLongestDepth(nodes, edges, startFieldId, () => false)
    for (const e of edges) {
      if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO') continue
      const procName = e.procName || '-'
      if (procsWithRows.has(procName)) continue
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'FIELD' || t.id === startFieldId) continue
      if (!isTempFieldName(t.id) || !reasonMap.has(t.id)) continue
      const key = `${procName}|${t.id}`
      if (seen.has(key)) continue
      seen.add(key)
      const parts = t.id.split('.')
      rows.push({
        procName,
        table: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : t.id,
        field: parts[parts.length - 1],
        reasons: REASON_ORDER.filter(r => reasonMap.get(t.id).has(r)),
        level: rawDepth.get(t.id),
        targetId: t.id,
        temp: true
      })
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
 * @param sourceLayer 源表层级（可选；源表不在 impactedByDepth 中时用于补充节点）
 * @return { rows: [{procName, table, level, targetId}], nodes, edges, depthMap }
 *   nodes/edges 为含临时表的全图（节点含 temp/scriptFile/shortName），行只含非临时表
 */
export function buildTableImpactData(sourceTable, impactedByDepth, sourceLayer) {
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
  // 后端 impactedByDepth 不含源表（Cypher WHERE src <> downstream），但 allEdges 含以源表为起点的边，
  // 需补充源表节点，否则弹窗链路图缺源表且首跳边引用悬空
  if (!tableInfoMap[sourceTable]) {
    allNodes.unshift({
      id: sourceTable,
      label: sourceTable,
      layer: sourceLayer || 'UNKNOWN',
      temp: false,
      scriptFile: undefined,
      nodeLevel: 'TABLE'
    })
  }
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
 * 字段级来源清单行推导（buildFieldImpactRows 的 UPSTREAM 镜像版）。
 * 从起点沿血缘反向传播：入边 OUTPUT_TO 进入链路 Transform，其每个 INPUT_TO 输入字段
 * 为来源候选；入边 DERIVED_FROM 的源字段为来源候选。标签规则与下游一致——
 * 边带角色时跳变为该角色（JOIN/WHERE 条件），否则继承下游字段的标签。
 * level = 反向最长路径的非透明跳数（临时表/Transform 穿透）。
 * 行 key=(procName, 来源字段) 去重，reasons 聚合；仅产临时来源的 proc 补临时行。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp, upstream: true }]
 */
export function buildFieldSourceRows(nodes, edges, startFieldId) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  // 反向图最长深度：层级 = 距起点的上游跳数
  const revEdges = edges.map(e => ({ ...e, source: e.target, target: e.source }))
  const depth = computeLongestDepth(nodes, revEdges, startFieldId,
    n => n.nodeLevel === 'TRANSFORM' || isTempFieldName(n.id))

  // 入边索引 + Transform 输入索引
  const inEdges = new Map()
  for (const e of edges) {
    if (!inEdges.has(e.target)) inEdges.set(e.target, [])
    inEdges.get(e.target).push(e)
  }
  const transformInputs = new Map()
  for (const e of edges) {
    if (e.edgeType !== 'INPUT_TO') continue
    if (!transformInputs.has(e.target)) transformInputs.set(e.target, [])
    transformInputs.get(e.target).push({ colKey: e.source, role: e.role })
  }

  const chainTransforms = new Set() // 位于起点上游链路上的 Transform
  const reasonMap = new Map()
  const visited = new Set()
  const queue = []
  const push = (fieldId, reason) => {
    if (fieldId === startFieldId) return
    const key = `${fieldId}|${reason}`
    if (visited.has(key)) return
    visited.add(key)
    if (!reasonMap.has(fieldId)) reasonMap.set(fieldId, new Set())
    reasonMap.get(fieldId).add(reason)
    queue.push([fieldId, reason])
  }
  // 初始：起点的入边（OUTPUT_TO 来自链路 Transform；DERIVED_FROM 来自直接下游…上游字段）
  for (const e of (inEdges.get(startFieldId) || [])) {
    if (e.edgeType === 'OUTPUT_TO') {
      chainTransforms.add(e.source)
      for (const inp of (transformInputs.get(e.source) || [])) {
        if (nodeMap.has(inp.colKey)) push(inp.colKey, ROLE_TO_REASON[inp.role] || '直接来源')
      }
    } else if (e.edgeType === 'DERIVED_FROM') {
      const s = nodeMap.get(e.source)
      if (s && s.nodeLevel === 'FIELD') push(e.source, ROLE_TO_REASON[e.role] || '直接来源')
    }
  }
  // 继承传播：沿入边继续向上游
  while (queue.length) {
    const [cur, reason] = queue.shift()
    for (const e of (inEdges.get(cur) || [])) {
      if (e.edgeType === 'OUTPUT_TO') {
        chainTransforms.add(e.source)
        for (const inp of (transformInputs.get(e.source) || [])) {
          push(inp.colKey, ROLE_TO_REASON[inp.role] || reason)
        }
      } else if (e.edgeType === 'DERIVED_FROM') {
        const s = nodeMap.get(e.source)
        if (s && s.nodeLevel === 'FIELD') push(e.source, ROLE_TO_REASON[e.role] || reason)
      }
    }
  }

  // 行生成：到达边 = INPUT_TO（来源字段进入链路 Transform）或 DERIVED_FROM（直连链路字段）
  // 虚拟字面量节点（__literal__.<transformId>）不是真实来源字段，排除
  const rows = []
  const seen = new Set()
  for (const e of edges) {
    if (e.edgeType !== 'INPUT_TO' && e.edgeType !== 'DERIVED_FROM') continue
    const s = nodeMap.get(e.source)
    if (!s || s.nodeLevel !== 'FIELD' || s.id === startFieldId) continue
    if (s.id.startsWith('__literal__.')) continue
    if (!reasonMap.has(s.id)) continue // 不在起点的上游闭包内
    if (e.edgeType === 'INPUT_TO' && !chainTransforms.has(e.target)) continue
    if (e.edgeType === 'DERIVED_FROM' && e.target !== startFieldId && !reasonMap.has(e.target)) continue
    if (isTempFieldName(s.id)) continue
    const key = `${e.procName || '-'}|${s.id}`
    if (seen.has(key)) continue
    seen.add(key)
    const parts = s.id.split('.')
    rows.push({
      procName: e.procName || '-',
      table: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : s.id,
      field: parts[parts.length - 1],
      reasons: REASON_ORDER.filter(r => reasonMap.get(s.id).has(r)),
      level: depth.get(s.id),
      targetId: s.id,
      temp: false,
      upstream: true
    })
  }
  // 补充行：仅临时表来源的 proc
  const procsWithRows = new Set(rows.map(r => r.procName))
  if (rows.length) {
    const rawDepth = computeLongestDepth(nodes, revEdges, startFieldId, () => false)
    for (const e of edges) {
      if (e.edgeType !== 'INPUT_TO' && e.edgeType !== 'DERIVED_FROM') continue
      const procName = e.procName || '-'
      if (procsWithRows.has(procName)) continue
      const s = nodeMap.get(e.source)
      if (!s || s.nodeLevel !== 'FIELD' || s.id === startFieldId) continue
      if (s.id.startsWith('__literal__.')) continue
      if (!isTempFieldName(s.id) || !reasonMap.has(s.id)) continue
      const key = `${procName}|${s.id}`
      if (seen.has(key)) continue
      seen.add(key)
      const parts = s.id.split('.')
      rows.push({
        procName,
        table: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : s.id,
        field: parts[parts.length - 1],
        reasons: REASON_ORDER.filter(r => reasonMap.get(s.id).has(r)),
        level: rawDepth.get(s.id),
        targetId: s.id,
        temp: true,
        upstream: true
      })
    }
  }
  rows.sort((a, b) => (a.level ?? 99) - (b.level ?? 99)
    || a.table.localeCompare(b.table) || a.field.localeCompare(b.field))
  return rows
}

/**
 * 正向可达子图（traceUpstreamSubgraph 的镜像）：从 fromId 沿边正向可达，
 * 只走 procName 匹配的边。用于 UPSTREAM 行的链路弹窗——展示来源字段在该脚本中的流向。
 */
export function traceDownstreamSubgraph(nodes, edges, fromId, procName) {
  const procOk = e => !procName || !e.procName || e.procName === procName
  const outEdges = new Map()
  for (const e of edges) {
    if (!procOk(e)) continue
    if (!outEdges.has(e.source)) outEdges.set(e.source, [])
    outEdges.get(e.source).push(e)
  }
  const keep = new Set([fromId])
  const q = [fromId]
  while (q.length) {
    const cur = q.shift()
    for (const e of (outEdges.get(cur) || [])) {
      if (!keep.has(e.target)) { keep.add(e.target); q.push(e.target) }
    }
  }
  return {
    nodes: nodes.filter(n => keep.has(n.id)),
    edges: edges.filter(e => procOk(e) && keep.has(e.source) && keep.has(e.target))
  }
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

/**
 * 统计卡点击明细行推导：从清单行聚合出表/字段/proc 维度的去重清单。
 * 层级沿用"最大层级"规则（同对象多路径取最深）；表维度排除临时表；
 * proc 维度的表数同样排除临时表。
 * @param {'tables'|'fields'|'procs'} type 明细维度
 * @param {Array} rows 清单行（impactRows）
 */
export function buildStatsDetailRows(type, rows) {
  if (type === 'tables') {
    const map = new Map()
    for (const r of rows) {
      if (r.temp) continue
      if (!map.has(r.table)) map.set(r.table, { table: r.table, level: 0, procs: new Set(), fields: new Set() })
      const t = map.get(r.table)
      t.level = Math.max(t.level, r.level ?? 0)
      if (r.procName && r.procName !== '-') t.procs.add(r.procName)
      if (r.targetId) t.fields.add(r.targetId)
    }
    return [...map.values()]
      .map(t => ({ table: t.table, level: t.level, procCount: t.procs.size, fieldCount: t.fields.size }))
      .sort((a, b) => a.level - b.level || a.table.localeCompare(b.table))
  }
  if (type === 'fields') {
    const map = new Map()
    for (const r of rows) {
      if (!r.targetId || !r.field) continue // 表级行无字段维度，跳过
      if (!map.has(r.targetId)) map.set(r.targetId, { table: r.table, field: r.field, level: 0, reasons: new Set(), procs: new Set() })
      const f = map.get(r.targetId)
      f.level = Math.max(f.level, r.level ?? 0)
      for (const x of r.reasons || []) f.reasons.add(x)
      if (r.procName && r.procName !== '-') f.procs.add(r.procName)
    }
    return [...map.values()]
      .map(f => ({ table: f.table, field: f.field, level: f.level, reasons: REASON_ORDER.filter(x => f.reasons.has(x)), procCount: f.procs.size }))
      .sort((a, b) => a.level - b.level || a.table.localeCompare(b.table) || a.field.localeCompare(b.field))
  }
  if (type === 'procs') {
    const map = new Map()
    for (const r of rows) {
      const p = r.procName
      if (!p || p === '-') continue
      if (!map.has(p)) map.set(p, { procName: p, tables: new Set(), fields: new Set() })
      const pr = map.get(p)
      if (!r.temp) pr.tables.add(r.table)
      if (r.targetId) pr.fields.add(r.targetId)
    }
    return [...map.values()]
      .map(pr => ({ procName: pr.procName, tableCount: pr.tables.size, fieldCount: pr.fields.size }))
      .sort((a, b) => b.tableCount - a.tableCount || a.procName.localeCompare(b.procName))
  }
  return []
}
