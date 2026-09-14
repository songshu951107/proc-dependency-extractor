// 影响分析清单化展示的纯函数工具：层级计算（最长路径+临时表/Transform穿透）、清单行推导、链路回溯

export const REASON_ORDER = ['值影响', '行过滤·JOIN', '行过滤·WHERE']
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
 * 展示层级预算规则：最长路径层级（computeLongestDepth）在预算内直接用；
 * 超预算时取 min(最长路, 选深)——选深范围内最长路径长度。
 * 例：字段最长路=6、首达=4——选深度 5 时展示 5（选深内最长），
 * 选深度 10 时展示 6（最长路本身 ≤ 选深，不触发回落）。
 * 此前回落到 BFS 首达深度会导致多路径字段跳过中间层（如 5 跳路径存在
 * 却被压到首达 4 层）。fallbackDepth 保留用于 longest 为 null 的兜底。
 * @param longest 最长路径层级（无则 null）
 * @param fallbackDepth 后端 BFS 首达深度（无则 undefined，仅 longest=null 时兜底）
 * @param maxDepth 用户选择的深度预算
 * @return 展示层级
 */
export function capLevelToBudget(longest, fallbackDepth, maxDepth) {
  if (maxDepth == null || longest == null || longest <= maxDepth) return longest
  // 最长路超预算：取选深作为展示层级（选深范围内最长路径长度 ≤ 选深），
  // 而非回落到 BFS 首达深度（会跳过中间层，如 5 跳路径存在却压到首达 4 层）。
  // fallbackDepth 保留参数兼容性，但不再用于决定展示层级。
  return maxDepth
}

/**
 * 最长路径层级计算（规格 §3.2）。跳数口径 = 跨表传播段数：
 * 条件列 -FILTERS-> 表 -ROW_IMPACT-> 表内列 是 1 段行影响传播（表内列深度=表深度，不另计跳）；
 * 值边（Transform 链/列直连）每段 1 跳；临时表/Transform 节点穿透（+0）。
 * 环处理（Tarjan SCC + 缩点）：proc 读写同表等自引用模式形成正权环（列↔Transform），
 * 直接松弛不收敛。将每个强连通分量缩为超节点后在 DAG 上求最长路径：
 * 影响可经入口边进入环节点（深度=进入环的外部链深度），经出口边离开，不在环内绕圈；
 * 环成员同深度，传播链不被切断（与路径表回溯的简单路径跳数一致）。
 * @param nodes [{id, nodeLevel, temp?}]  @param edges [{source, target}]  @param startId 起点id
 * @param isTransparent (node) => bool  @return Map<id, depth>（不可达节点不在 Map 中）
 */
export function computeLongestDepth(nodes, edges, startId, isTransparent) {
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  // 起点可达子图（出边索引）
  const outEdges = new Map()
  for (const e of edges) {
    if (!outEdges.has(e.source)) outEdges.set(e.source, [])
    outEdges.get(e.source).push(e)
  }
  const reachable = new Set([startId])
  const queue = [startId]
  while (queue.length) {
    const cur = queue.shift()
    for (const e of (outEdges.get(cur) || [])) {
      if (nodeMap.has(e.target) && !reachable.has(e.target)) { reachable.add(e.target); queue.push(e.target) }
    }
  }
  // Tarjan SCC（仅可达子图）
  let index = 0
  const idx = new Map(), low = new Map(), onStack = new Set(), stack = []
  const sccOf = new Map()
  const strongconnect = v => {
    idx.set(v, index); low.set(v, index); index++
    stack.push(v); onStack.add(v)
    for (const e of (outEdges.get(v) || [])) {
      const w = e.target
      if (!reachable.has(w)) continue
      if (!idx.has(w)) { strongconnect(w); low.set(v, Math.min(low.get(v), low.get(w))) }
      else if (onStack.has(w)) low.set(v, Math.min(low.get(v), idx.get(w)))
    }
    if (low.get(v) === idx.get(v)) {
      let w
      do { w = stack.pop(); onStack.delete(w); sccOf.set(w, v) } while (w !== v)
    }
  }
  for (const v of reachable) if (!idx.has(v)) strongconnect(v)

  // 缩点 DAG：超节点 id = 代表元；入边代价 = 分量内含非透明节点 ? 1 : 0（全透明分量穿透 +0）
  const sccOpaque = new Map() // sccId -> bool（含非透明节点）
  for (const id of reachable) {
    const s = sccOf.get(id)
    if (!sccOpaque.has(s)) sccOpaque.set(s, false)
    if (!isTransparent(nodeMap.get(id))) sccOpaque.set(s, true)
  }
  const sccOut = new Map() // sccId -> [{to, inc}]
  for (const e of edges) {
    if (!reachable.has(e.source) || !nodeMap.has(e.target) || !reachable.has(e.target)) continue
    const su = sccOf.get(e.source), sv = sccOf.get(e.target)
    if (su === sv) continue // 环内边（含自环）：缩点后丢弃，不绕圈
    if (!sccOut.has(su)) sccOut.set(su, [])
    const inc = e.edgeType === 'ROW_IMPACT' ? 0 : (sccOpaque.get(sv) ? 1 : 0)
    sccOut.get(su).push({ to: sv, inc })
  }
  // 缩点 DAG 最长路径（无环，松弛必收敛）
  const sccDepth = new Map([[sccOf.get(startId), 0]])
  let changed = true
  let round = 0
  const maxRounds = reachable.size + 1
  while (changed && round < maxRounds) {
    changed = false
    round++
    for (const [su, outs] of sccOut) {
      const ds = sccDepth.get(su)
      if (ds === undefined) continue
      for (const { to, inc } of outs) {
        const cand = ds + inc
        if (cand > (sccDepth.get(to) ?? -1)) { sccDepth.set(to, cand); changed = true }
      }
    }
  }
  if (round >= maxRounds && changed) {
    // 安全阀：缩点后理论无环；若仍不收敛（异常数据）回退 BFS 最浅深度
    return bfsShallowDepth(nodeMap, edges, startId, isTransparent)
  }
  // 展开：环成员同深度
  const depth = new Map()
  for (const id of reachable) depth.set(id, sccDepth.get(sccOf.get(id)) ?? 0)
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
      const inc = e.edgeType === 'ROW_IMPACT' ? 0 : (isTransparent(tNode) ? 0 : 1)
      depth.set(e.target, depth.get(cur) + inc)
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
 * 无标签字段不生成行（可达判据）。level 取最长路径层级，超预算（maxDepth）时
 * 回落到后端 BFS 首达深度（capLevelToBudget）。
 * 补充行：仅产临时表的 proc（非临时交付物字段级血缘缺失）补临时表行（temp=true，
 * level 用非透明跳数），保证清单覆盖全部受影响脚本、与 stats 一致。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp }] 按 level/表/字段排序
 */
export function buildFieldImpactRows(nodes, edges, startFieldId, maxDepth) {
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

  // 纯值通道可达集合：起点沿值边(INPUT_TO/OUTPUT_TO/DERIVED_FROM 等非行边)能到达的节点，
  // 用于约束"值影响"标签——只有值通道能连回起点的字段才计入值影响，
  // 避免孤悬的值边(来源值链回不到起点)导致"原因列标了值影响、路径表却无对应传播段"。
  const valueReach = new Set([startFieldId])
  const vq = [startFieldId]
  while (vq.length) {
    const c = vq.pop()
    for (const e of (outEdges.get(c) || [])) {
      if (e.edgeType === 'FILTERS' || e.edgeType === 'ROW_IMPACT') continue
      if (valueReach.has(e.target)) continue
      valueReach.add(e.target)
      vq.push(e.target)
    }
  }

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
      reasons: REASON_ORDER.filter(r => reasonMap.get(t.id).has(r) && (r !== VALUE_REASON || valueReach.has(t.id))),
      level: capLevelToBudget(depth.get(t.id), t.depth, maxDepth),
      targetId: t.id,
      temp: false
    })
  }
  // 补充行：仅产临时表的 proc（其非临时交付物字段级血缘缺失，链路止于临时表）。
  // 只为"无非临时行"的 proc 生成临时表行，避免临时中转节点大量入表；
  // level 与物理行同口径（折叠临时表/Transform 的跳数）——此前用不折叠 rawDepth，
  // 导致卡片最大层级(11)与表格层级tab(5)口径不一致
  const procsWithRows = new Set(rows.map(r => r.procName))
  if (rows.length) {
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
        reasons: REASON_ORDER.filter(r => reasonMap.get(t.id).has(r) && (r !== VALUE_REASON || valueReach.has(t.id))),
        level: capLevelToBudget(depth.get(t.id), t.depth, maxDepth),
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
 * 最长路径层级超预算（maxDepth）时回落到 impactedByDepth 的 BFS 首达层键（capLevelToBudget）。
 * @param impactedByDepth 后端 result.impactedByDepth（depth键 → 该层首达表）
 * @param sourceLayer 源表层级（可选；源表不在 impactedByDepth 中时用于补充节点）
 * @param maxDepth 用户选择的深度预算（可选）
 * @return { rows: [{procName, table, level, targetId}], nodes, edges, depthMap }
 *   nodes/edges 为含临时表的全图（节点含 temp/scriptFile/shortName），行只含非临时表
 */
export function buildTableImpactData(sourceTable, impactedByDepth, sourceLayer, maxDepth) {
  // 全图邻接与信息 + 首达层索引（表名 → BFS 首达深度键，预算回落的 fallback）
  const tableInfoMap = {}
  const firstArrival = new Map()
  const allEdges = []
  for (const [depthKey, tables] of Object.entries(impactedByDepth || {})) {
    const d = Number(depthKey)
    for (const t of tables) {
      tableInfoMap[t.name] = t
      if (!firstArrival.has(t.name) || d < firstArrival.get(t.name)) firstArrival.set(t.name, d)
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
    rows.push({ procName, table: e.target, level: capLevelToBudget(depthMap.get(e.target), firstArrival.get(e.target), maxDepth), targetId: e.target })
  }
  rows.sort((a, b) => a.level - b.level || a.table.localeCompare(b.table))
  return { rows, nodes: allNodes, edges: allEdges, depthMap }
}

/**
 * 字段级来源清单行推导（buildFieldImpactRows 的 UPSTREAM 镜像版）。
 * 从起点沿血缘反向传播：入边 OUTPUT_TO 进入链路 Transform，其每个 INPUT_TO 输入字段
 * 为来源候选；入边 DERIVED_FROM 的源字段为来源候选。值来源恒标"值影响"——
 * 条件字段已走行血缘 FILTERS 通道，不混入值溯源清单。
 * level = 反向最长路径的非透明跳数（临时表/Transform 穿透），超预算（maxDepth）时
 * 回落到后端 BFS 首达深度（capLevelToBudget）。
 * 行 key=(procName, 来源字段) 去重，reasons 聚合；仅产临时来源的 proc 补临时行。
 * @return [{ procName, table, field, reasons: string[], level, targetId, temp, upstream: true }]
 */
export function buildFieldSourceRows(nodes, edges, startFieldId, maxDepth) {
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
      level: capLevelToBudget(depth.get(s.id), s.depth, maxDepth),
      targetId: s.id,
      temp: false,
      upstream: true
    })
  }
  // 补充行：仅临时表来源的 proc（level 同样用折叠口径，与下游清单一致）
  const procsWithRows = new Set(rows.map(r => r.procName))
  if (rows.length) {
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
        level: capLevelToBudget(depth.get(s.id), s.depth, maxDepth),
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
 * 与 行通道（Table←FILTERS←条件列）。不按 procName 过滤——跨 proc 跳完整展示。
 * 展示形态：每跳恰好一行，仅 行过滤 / 值传播 两类标签，不展示临时表列——
 *   到达临时节点（非终点）的步与后继步合并（值穿透折叠为物理列→物理列；
 *   行通道穿透整体折叠为 起点列→首个物理到达列，clause+expression 合并）；
 *   行影响展开步改写为 起点列→表内列 的行过滤步（expanded=true，语义同清单行 reason）。
 * 跳号 = 折叠后步序号（穿透临时表口径），与结果区"第 N 层级"对齐。
 * @param {Array} nodes 全量节点（FIELD 带 depth；TABLE 节点 depth=1）
 * @param {Array} edges 全量边
 * @param {string} targetId 目标列 id
 * @param {string} sourceId 起点列 id
 * @param {number} maxSteps 单路径物理步上限（防异常环/爆炸）
 * @param {number} preferLevel 优先展示的跳数（=清单行 level）：该行归入第 N 层级
 *        的依据是 N 跳路径，须优先展示；短路径过多时按行数升序会将其截掉
 * @param {number} maxDepth 深度预算（用户选深）：超预算的路径不展示——超预算字段的
 *        行已回落到首达层级，其"长路前缀+短路下游"拼接出的超预算路径与展示层级不符
 * @return {Array<Array<{hop, from, to, type, procName?, expression?, role?, clause?, expanded?, dF, dT}>>}
 *         路径数组（≤5 条，仅预算内路径，优先 preferLevel 跳，其余按行数升序），每条按起点→目标排列；type: FILTERS/VALUE/DIRECT
 */
export function traceHopPaths(nodes, edges, targetId, sourceId, maxSteps = 16, preferLevel, maxDepth) {
  if (!targetId || !sourceId || targetId === sourceId) return []
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  // 深度校验统一折叠口径（computeLongestDepth，与清单 level 同源）：后端原始 depth 是
  // BFS 首达层，值+行混合链路（第二轮行影响边）下不沿路径单调，直接校验会误剪
  // "值到达列→FILTERS→表→ROW_IMPACT"路径，导致 路径表行数 < 层级跳数
  const foldedDepth = computeLongestDepth(nodes, edges, sourceId,
    n => n.nodeLevel === 'TRANSFORM' || isTempNode(n))
  const inByTarget = new Map()
  for (const e of edges) {
    if (!inByTarget.has(e.target)) inByTarget.set(e.target, [])
    inByTarget.get(e.target).push(e)
  }
  const MAX_PATHS = 5
  // 收集上限须大于展示条数：短路径数量超过展示上限时会截断更长（更深层级）的路径，
  // 导致路径表最大跳数 < 层级（先收集全部再按折叠后行数排序截取）
  const COLLECT_LIMIT = 300
  const paths = []
  // 防爆炸安全阀（防异常环/指数级简单路径）：DFS 访问次数上限
  let dfsOps = 0
  const DFS_OPS_CAP = 200_000
  // 物理步预算随层级放大：深层路径经 临时表/Transform 穿透后物理步数可达展示行数的 2~3 倍，
  // 固定 maxSteps=16 会截断第 8+ 层的最长路径（出现 层级8/路径表7行 的错位）
  const stepBudget = Math.max(maxSteps, (preferLevel || 0) * 3)
  // 折叠跳数预算 = 清单行的展示层级：DFS 期间按折叠口径（FILTERS+ROW_IMPACT 合并为 1 行、
  // 临时表中转穿透 +0）增量累计，超过预算的分支直接剪掉。
  // 必要性：此前只在收集后过滤，DFS 的"最深前驱优先"会先烧完 ops 上限、只捞到长路，
  // 导致"最长路超预算→回落首达层级"的字段（如 depth=3/level=10）路径表为空——
  // 其预算内的短路（3 跳）根本未被搜索到。剪枝后搜索空间收敛到预算内，短路立即可得。
  const hopBudget = preferLevel != null && preferLevel > 0 ? preferLevel : Infinity
  // 临时表中转判定：路径中的临时节点（非目标）在折叠时并入下一行，不计跳
  const isTempMid = id => id !== targetId && isTempNode(nodeById.get(id))

  // 增量折叠：把新步 N（from→nodeId）prepend 到既有路径前，返回折叠后总跳数与该路径
  // 最前端折叠步的类型（供下一步继续判定合并）。与下方 folding 段同规则：
  // A) 前一步为 FILTERS 且新步为 ROW_IMPACT → 合并为 1 行（行影响一段）；
  // B) 新步落在临时表中转节点 → 并入前一步（穿透 +0）。
  function foldPrepend(hops, frontType, type, nodeId) {
    if (hops === 0) return { hops: 1, frontType: type }
    if (type === 'FILTERS' && frontType === 'ROW_IMPACT') return { hops, frontType: 'FILTERS' }
    if (isTempMid(nodeId)) return { hops, frontType: type === 'FILTERS' ? 'FILTERS' : frontType }
    return { hops: hops + 1, frontType: type }
  }

  function dfs(nodeId, steps, visited, foldedHops, frontType) {
    if (dfsOps++ > DFS_OPS_CAP) return
    if (paths.length >= COLLECT_LIMIT) return
    if (nodeId === sourceId) {
      paths.push(steps.slice().reverse())
      return
    }
    if (steps.length >= stepBudget || visited.has(nodeId)) return
    const n = nodeById.get(nodeId)
    if (!n) return
    visited.add(nodeId)
    const dT = foldedDepth.get(nodeId) ?? n.depth
    const push = (from, type, extra) => {
      // 折叠跳数剪枝：超预算的（前缀）路径不参与展示——其展示层级已回落到首达深度，
      // 拼接出的超预算长路与层级 tab 不符。剪枝同时把搜索空间压到预算内，
      // 避免 ops 上限被深层分支耗尽而搜不到预算内短路。
      const folded = foldPrepend(foldedHops, frontType, type, nodeId)
      if (folded.hops > hopBudget) return
      const dF = foldedDepth.get(from) ?? nodeById.get(from)?.depth
      steps.push({ from, to: nodeId, type, dF: dF ?? null, dT: dT ?? null, ...extra })
      dfs(from, steps, visited, folded.hops, folded.frontType)
      steps.pop()
    }
    // 前驱候选统一收集后按深度降序探索：更深前驱的路径更长，
    // 优先探索保证与层级对齐的最长路径在收集上限内被发现
    // （否则热点表的海量浅层条件列短路径先灌满 COLLECT_LIMIT，深层路径被截掉）
    const cands = []
    if (n.nodeLevel === 'TABLE') {
      // 表只走 FILTERS 入边（条件列）
      for (const fe of (inByTarget.get(nodeId) || [])) {
        if (fe.edgeType !== 'FILTERS') continue
        if (nodeById.get(fe.source)?.nodeLevel === 'FIELD') {
          cands.push({ from: fe.source, type: 'FILTERS', extra: { role: fe.role, clause: fe.clause, procName: fe.procName } })
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
              cands.push({ from: te.source, type: 'VALUE', extra: { procName: sn.procName || e.procName, expression: sn.expression || sn.label } })
            }
          }
        } else if (sn.nodeLevel === 'FIELD') {
          cands.push({ from: e.source, type: 'DIRECT', extra: { procName: e.procName } })
        } else if (sn.nodeLevel === 'TABLE' && e.edgeType === 'ROW_IMPACT') {
          cands.push({ from: e.source, type: 'ROW_IMPACT', extra: { role: e.role, procName: e.procName } })
        }
      }
    }
    // 值通道(DIRECT/VALUE)优先于行通道(ROW_IMPACT)探索——dep_avg 同时有
    // DIRECT 前驱(inf_.dep_avg, depth=5)和 ROW_IMPACT 前驱(TABLE, depth=6)，
    // 纯 depth 降序会让行通道先搜，海量 FILTERS 路径灌满 COLLECT_LIMIT，
    // 值路径分支根本未被探索。同类型内仍按 depth 降序，保持最长路径优先。
    cands.sort((a, b) => {
      const aRow = a.type === 'ROW_IMPACT' ? 1 : 0
      const bRow = b.type === 'ROW_IMPACT' ? 1 : 0
      if (aRow !== bRow) return aRow - bRow
      return (foldedDepth.get(b.from) ?? -1) - (foldedDepth.get(a.from) ?? -1)
    })
    for (const c of cands) push(c.from, c.type, c.extra)
    visited.delete(nodeId)
  }

  dfs(targetId, [], new Set(), 0, null)
  // 展示形态：每跳恰好一行（= 跨表传播一段），仅 行过滤 / 值传播 两类标签，不展示临时表列，
  // 跳数与清单层级 tab 同口径（穿透临时表 + 行展开不占跳）。
  // 合并规则：
  //   1) 行影响一段：FILTERS（到物理表）+ 后继 ROW_IMPACT（表内列展开）合并为
  //      起点列→表内列 的一行（clause 承 JOIN/WHERE 条件）——行影响从条件列到达表内列是一段传播；
  //   2) 临时表穿透：到达临时节点（非终点）的步并入后继步——
  //      值穿透（值步→临时列→值步）折叠为 物理列→物理列；
  //      行通道穿透（FILTERS→临时表→展开→值穿透）整体折叠为 起点列→首个物理到达列，
  //      clause 承条件、expression 合并值表达式。
  // 跳号 = 折叠后步序号，与层级 tab 对齐。
  // 注：合并规则与 DFS 内 foldPrepend（剪枝用的增量折叠）必须保持一致
  const folded = paths.map(p => {
    const folded = []
    for (const s of p) {
      const last = folded[folded.length - 1]
      if (last && last.type === 'FILTERS' && s.type === 'ROW_IMPACT') {
        // 行影响一段：条件列→表→表内列 合并为一行（表节点不出现在路径表中）
        folded[folded.length - 1] = {
          ...s,
          from: last.from,
          dF: last.dF,
          type: 'FILTERS',
          role: last.role || s.role,
          clause: last.clause || s.clause,
          procName: last.procName || s.procName,
          expanded: true
        }
      } else if (last && isTempMid(last.to)) {
        const exprs = [...new Set([last.expression, s.expression].filter(Boolean))]
        if (!exprs.length && (last.type === 'DIRECT' || s.type === 'DIRECT')) exprs.push('直接取值')
        const procs = [...new Set([last.procName, s.procName].filter(Boolean))]
        folded[folded.length - 1] = {
          ...s,
          from: last.from,
          dF: last.dF,
          type: last.type === 'FILTERS' ? 'FILTERS' : s.type,
          role: last.role || s.role,
          clause: last.clause || s.clause,
          expression: exprs.join('；') || null,
          procName: procs.join('；') || null
        }
      } else {
        folded.push({ ...s })
      }
    }
    // 兜底：孤立 ROW_IMPACT 步（前驱非 FILTERS，理论不出现）改写为行过滤行
    return folded.map((s, i) => {
      const hop = i + 1
      if (s.type !== 'ROW_IMPACT') return { ...s, hop }
      return { ...s, from: sourceId, type: 'FILTERS', expanded: true, hop }
    })
  })
  // 先折叠再过滤排序截取：
  // 1) 预算过滤：maxDepth 内的路径才展示——超预算字段的最长路（长路前缀+短路下游拼接）
  //    与回落后的展示层级不符，只保留预算内路径；
  // 2) 优先展示跳数 == preferLevel（清单行 level）的路径——
  //    该行归入第 N 层级的依据是 N 跳路径；短路径过多时纯升序会将其截掉，
  //    造成"3 跳直达却归第 4 层"的困惑。无匹配时回退按行数升序。
  const filtered = folded.filter(p => maxDepth == null || p.length <= maxDepth)
  const sorted = filtered
    .sort((a, b) => {
      if (preferLevel != null) {
        const am = a.length === preferLevel ? 0 : 1
        const bm = b.length === preferLevel ? 0 : 1
        if (am !== bm) return am - bm
      }
      return a.length - b.length
    })
  let result = sorted.slice(0, MAX_PATHS)
  // 类型多样性补底：若结果全为行过滤路径而候选中存在含值步骤(VALUE/DIRECT)的路径，
  // 用最短的值路径替换最后一条——确保原因列标了"值影响"时路径表至少展示一条值传播路径，
  // 避免值路径被海量行路径挤出 MAX_PATHS。
  const hasValueStep = p => p.some(s => s.type === 'VALUE' || s.type === 'DIRECT')
  if (result.length >= 2 && !result.some(hasValueStep)) {
    const valuePath = filtered.filter(hasValueStep)
      .sort((a, b) => {
        if (preferLevel != null) {
          const am = a.length === preferLevel ? 0 : 1
          const bm = b.length === preferLevel ? 0 : 1
          if (am !== bm) return am - bm
        }
        return a.length - b.length
      })[0]
    if (valuePath) result[result.length - 1] = valuePath
  }
  return result
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
