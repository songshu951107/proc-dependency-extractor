// 复现预览页 toEdgesAfter 的跨层检测逻辑，确认哪些边被标记为跨层
import { readFileSync } from 'node:fs'
import { traceUpstreamSubgraph } from './src/components/impact/impactListUtils.js'

const raw = JSON.parse(readFileSync('../tmp_prev_src.json', 'utf8').replace(/^\uFEFF/, ''))
const sg = traceUpstreamSubgraph(raw.data.nodes, raw.data.edges, 'cdm.c_agt_deposit_acct_inf.acct_no')
const sourceId = 'fdm.f_ac_ctds_ldr_acct.acct_no'

function edgeVisual(e) {
  const isRowImpact = e.edgeType === 'ROW_IMPACT'
  const isCond = !isRowImpact && (e.role === 'join_condition' || e.role === 'where_condition')
  const kind = isRowImpact ? 'row' : (isCond ? (e.role === 'join_condition' ? 'join' : 'where') : 'value')
  return { kind }
}
const groups = new Map()
for (const e of sg.edges) {
  const v = edgeVisual(e)
  const k = e.source + '\u0001' + e.target + '\u0001' + v.kind
  if (!groups.has(k)) groups.set(k, { source: e.source, target: e.target, kind: v.kind })
}
const merged = [...groups.values()]

const adj = new Map()
for (const m of merged) {
  if (!adj.has(m.source)) adj.set(m.source, [])
  adj.get(m.source).push(m.target)
}
const rank = new Map()
const longest = id => {
  if (rank.has(id)) return rank.get(id)
  rank.set(id, 0)
  let r = 0
  for (const t of (adj.get(id) || [])) r = Math.max(r, longest(t) + 1)
  rank.set(id, r)
  return r
}
for (const n of sg.nodes) longest(n.id)

console.log('=== 各节点最长路径层级 ===')
for (const [id, r] of rank) console.log(' ', r, id)
console.log('\n=== 跨层边检测（span>=2）===')
for (const m of merged) {
  const rs = rank.get(m.source) ?? 0, rt = rank.get(m.target) ?? 0
  const span = Math.abs(rt - rs)
  const mark = (m.source !== m.target && span >= 2) ? ' ★跨层→绕行' : ''
  console.log(`  [span=${span}] ${m.source} -> ${m.target} (${m.kind})${mark}`)
}
