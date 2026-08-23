// 影响分析清单化展示的纯函数工具：层级计算（最长路径+临时表/Transform穿透）、清单行推导、链路回溯

const REASON_ORDER = ['值影响', '行过滤·JOIN', '行过滤·WHERE']
const FILTERS_ROLE_TO_REASON = {
  join_condition: '行过滤·JOIN',
  where_condition: '行过滤·WHERE'
}
const VALUE_REASON = '值影响'

// 字段 id（schema.table.field）的表名部分是否临时表（vt_ 前缀，与解析器建临时表命名一致）
export function isTempFieldName(nodeId) {
  const parts = (nodeId || '').split('.')
  return parts.length >= 2 && parts[1].toLowerCase().startsWith('vt_')
}

// 临时表判定（优先后端下发的权威标记 node.isTemp——来自 MySQL target_is_temp，
// 覆盖不带 vt_ 前缀的 proc 内 CREATE 临时表；标记缺失时回退名字前缀兜底）
export function isTempNode(node) {
  if (!node) return false
  return node.isTemp === true || isTempFieldName(node.id)
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
 * 字段级清单行推导（双通道标签传播）。
 * 值通道标签 = 值影响：起点→Transform（INPUT_TO）时授予该 Transform 的所有 OUTPUT_TO
 * 目标字段，经 DERIVED_FROM 直达的字段继承当前标签（值边 INPUT_TO 的 role 恒为 value）。
 * 行通道标签 = 行过滤·JOIN / 行过滤·WHERE：起点出边 FILTERS（角色取自边 role）经
 * ROW_IMPACT 展开到受影响表的所有列；下游继续传播时再遇 FILTERS 按角色重新打标。
 * 行生成：到达边（DERIVED_FROM/OUTPUT_TO/ROW_IMPACT）触发行，行 key=(procName, targetId) 去重，
 * reasons = 字段标签集合（按 REASON_ORDER 排序），同一字段多原因聚合一行；
 * 无标签字段不生成行（可达判据）。level 仍取最长路径层级。
 * 补充行：仅产临时表的 proc（非临时交付物字段级血缘缺失）补临时表行（temp=true，
 * level 用非透明跳数），保证清单覆盖全部受影响脚本、与 stats 一致。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp }] 按 level/表/字段排序
 */
export function buildFieldImpactRows(nodes, edges, startFieldId) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  const depth = computeLongestDepth(nodes, edges, startFieldId,
    n => n.nodeLevel === 'TRANSFORM' || isTempNode(n))

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
  const reasonOf = (e) => FILTERS_ROLE_TO_REASON[e.role] || VALUE_REASON

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
  // 初始标签：起点出边（FILTERS 行通道 + 值通道）
  for (const e of (outEdges.get(startFieldId) || [])) {
    if (e.edgeType === 'FILTERS') {
      const reason = FILTERS_ROLE_TO_REASON[e.role] || '行过滤·WHERE'
      for (const re of (outEdges.get(e.target) || [])) {
        if (re.edgeType === 'ROW_IMPACT') push(re.target, reason)
      }
    } else if (e.edgeType === 'INPUT_TO') {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'TRANSFORM') continue
      for (const out of (transformOutputs.get(e.target) || [])) push(out, reasonOf(e))
    } else {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'FIELD') continue
      push(e.target, reasonOf(e))
    }
  }
  // 继承传播：FILTERS 行通道按角色打标；值边继承当前标签
  while (queue.length) {
    const [cur, reason] = queue.shift()
    for (const e of (outEdges.get(cur) || [])) {
      if (e.edgeType === 'FILTERS') {
        const rowReason = FILTERS_ROLE_TO_REASON[e.role] || reason
        for (const re of (outEdges.get(e.target) || [])) {
          if (re.edgeType === 'ROW_IMPACT') push(re.target, rowReason)
        }
      } else if (e.edgeType === 'INPUT_TO') {
        for (const out of (transformOutputs.get(e.target) || [])) push(out, reason)
      } else if (e.edgeType === 'DERIVED_FROM' || !e.edgeType) {
        const t = nodeMap.get(e.target)
        if (t && t.nodeLevel === 'FIELD') push(e.target, reason)
      }
    }
  }

  // 行生成（非临时表交付物）
  const rows = []
  const seen = new Set()
  for (const e of edges) {
    if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO' && e.edgeType !== 'ROW_IMPACT') continue
    const t = nodeMap.get(e.target)
    if (!t || t.nodeLevel !== 'FIELD' || t.id === startFieldId) continue
    if (isTempNode(t) || !reasonMap.has(t.id)) continue
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
      if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO' && e.edgeType !== 'ROW_IMPACT') continue
      const procName = e.procName || '-'
      if (procsWithRows.has(procName)) continue
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'FIELD' || t.id === startFieldId) continue
      if (!isTempNode(t) || !reasonMap.has(t.id)) continue
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
 * 为来源候选；入边 DERIVED_FROM 的源字段为来源候选。值来源恒标"值影响"——
 * 条件字段已走行血缘 FILTERS 通道，不混入值溯源清单。
 * level = 反向最长路径的非透明跳数（临时表/Transform 穿透）。
 * 行 key=(procName, 来源字段) 去重，reasons 聚合；仅产临时来源的 proc 补临时行。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp, upstream: true }]
 */
export function buildFieldSourceRows(nodes, edges, startFieldId) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  // 反向图最长深度：层级 = 距起点的上游跳数
  const revEdges = edges.map(e => ({ ...e, source: e.target, target: e.source }))
  const depth = computeLongestDepth(nodes, revEdges, startFieldId,
    n => n.nodeLevel === 'TRANSFORM' || isTempNode(n))

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
        if (nodeMap.has(inp.colKey)) push(inp.colKey, VALUE_REASON)
      }
    } else if (e.edgeType === 'DERIVED_FROM') {
      const s = nodeMap.get(e.source)
      if (s && s.nodeLevel === 'FIELD') push(e.source, VALUE_REASON)
    }
  }
  // 继承传播：沿入边继续向上游
  while (queue.length) {
    const [cur, reason] = queue.shift()
    for (const e of (inEdges.get(cur) || [])) {
      if (e.edgeType === 'OUTPUT_TO') {
        chainTransforms.add(e.source)
        for (const inp of (transformInputs.get(e.source) || [])) {
          push(inp.colKey, reason)
        }
      } else if (e.edgeType === 'DERIVED_FROM') {
        const s = nodeMap.get(e.source)
        if (s && s.nodeLevel === 'FIELD') push(e.source, reason)
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
      if (!isTempNode(s) || !reasonMap.has(s.id)) continue
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
 * 逐跳路径回溯（方案A·跨跳明细，双通道）：从目标列回溯到起点列的物理步序列，
 * 支持 值通道（Column←Transform←Column / DERIVED_FROM 列直连，临时表列穿透 d+0）
 * 与 行通道（Column←ROW_IMPACT←Table←FILTERS←条件列）。不按 procName 过滤——跨 proc 跳完整展示。
 * 跳号按 depth 推进编：depth 递增的步闭合一跳，穿透步（d 不变）归入下一跳，
 * 与结果区"第 N 层级"对齐（如 0→1 行过滤、1→2 行影响展开、2→3 值传播）。
 * @param {Array} nodes 全量节点（FIELD 带 depth；TABLE 节点 depth=1）
 * @param {Array} edges 全量边
 * @param {string} targetId 目标列 id
 * @param {string} sourceId 起点列 id
 * @param {number} maxSteps 单路径物理步上限（防异常环/爆炸）
 * @return {Array<Array<{hop, from, to, type, procName?, expression?, role?, clause?, dF, dT}>>}
 *         路径数组（≤5 条，按步数升序），每条按起点→目标排列；type: FILTERS/ROW_IMPACT/VALUE/DIRECT
 */
export function traceHopPaths(nodes, edges, targetId, sourceId, maxSteps = 16) {
  if (!targetId || !sourceId || targetId === sourceId) return []
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  const inByTarget = new Map()
  for (const e of edges) {
    if (!inByTarget.has(e.target)) inByTarget.set(e.target, [])
    inByTarget.get(e.target).push(e)
  }
  const MAX_PATHS = 5
  const paths = []

  function dfs(nodeId, steps, visited) {
    if (paths.length >= MAX_PATHS) return
    if (nodeId === sourceId) {
      paths.push(steps.slice().reverse())
      return
    }
    if (steps.length >= maxSteps || visited.has(nodeId)) return
    const n = nodeById.get(nodeId)
    if (!n) return
    visited.add(nodeId)
    const dT = n.depth
    const push = (from, type, extra) => {
      const dF = nodeById.get(from)?.depth
      // 前驱深度校验：递减（dT-1，含 行链条件列→表）/ 穿透（dT，临时表列中转）；null 宽松放行（由成功条件裁剪）
      const ok = dF == null || dT == null || dF === dT - 1 || dF === dT
      if (!ok) return
      steps.push({ from, to: nodeId, type, dF: dF ?? null, dT: dT ?? null, ...extra })
      dfs(from, steps, visited)
      steps.pop()
    }
    if (n.nodeLevel === 'TABLE') {
      // 表只走 FILTERS 入边（条件列）
      for (const fe of (inByTarget.get(nodeId) || [])) {
        if (fe.edgeType !== 'FILTERS') continue
        if (nodeById.get(fe.source)?.nodeLevel === 'FIELD') {
          push(fe.source, 'FILTERS', { role: fe.role, clause: fe.clause, procName: fe.procName })
        }
      }
    } else {
      // 列：值前驱（Transform 中转 / 列直连）+ 行前驱（ROW_IMPACT←表）
      for (const e of (inByTarget.get(nodeId) || [])) {
        const sn = nodeById.get(e.source)
        if (!sn) continue
        if (sn.nodeLevel === 'TRANSFORM') {
          for (const te of (inByTarget.get(e.source) || [])) {
            if (nodeById.get(te.source)?.nodeLevel === 'FIELD') {
              push(te.source, 'VALUE', { procName: sn.procName || e.procName, expression: sn.expression || sn.label })
            }
          }
        } else if (sn.nodeLevel === 'FIELD') {
          push(e.source, 'DIRECT', { procName: e.procName })
        } else if (sn.nodeLevel === 'TABLE' && e.edgeType === 'ROW_IMPACT') {
          push(e.source, 'ROW_IMPACT', { role: e.role, procName: e.procName })
        }
      }
    }
    visited.delete(nodeId)
  }

  dfs(targetId, [], new Set())
  // 跳号：depth 推进（dT > dF）的步闭合一跳；穿透步（dT == dF）沿用后继跳号
  const numbered = paths
    .sort((a, b) => a.length - b.length)
    .map(p => {
      let hop = 0
      let lastClose = -1
      return p.map(s => {
        if (s.dF != null && s.dT != null && s.dT > s.dF && s.dT > lastClose) { hop++; lastClose = s.dT }
        return { ...s, hop: Math.max(hop, 1) }
      })
    })
  // 临时表中转折叠：值通道步（VALUE/DIRECT）的到达列为临时表列时，与后继值通道步合并，
  // 路径表只呈现 非临时表 → 非临时表；表达式/proc 去重合并保留计算信息。
  // 起点/终点为临时表时保留（分析对象本身）；FILTERS/ROW_IMPACT 行链步不折叠（行影响锚点）。
  return numbered.map(p => {
    const out = []
    const valueChan = t => t === 'VALUE' || t === 'DIRECT'
    for (const s of p) {
      const last = out[out.length - 1]
      if (last && valueChan(last.type) && valueChan(s.type)
        && last.to !== targetId && isTempNode(nodeById.get(last.to))) {
        const exprs = [...new Set([last.expression, s.expression].filter(Boolean))]
        const procs = [...new Set([last.procName, s.procName].filter(Boolean))]
        out[out.length - 1] = {
          ...s,
          from: last.from,
          dF: last.dF,
          type: last.type === 'VALUE' || s.type === 'VALUE' ? 'VALUE' : 'DIRECT',
          procName: procs.join('；') || null,
          expression: exprs.length ? exprs.join('；') : null
        }
      } else {
        out.push(s)
      }
    }
    return out
  })
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
