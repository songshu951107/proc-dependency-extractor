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
