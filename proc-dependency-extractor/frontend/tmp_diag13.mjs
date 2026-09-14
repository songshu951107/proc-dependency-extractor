// 打印 2 跳链路子图的边明细
import { readFileSync } from 'node:fs'
import { traceUpstreamSubgraph } from './src/components/impact/impactListUtils.js'
const raw = JSON.parse(readFileSync('../tmp_prev_src.json', 'utf8').replace(/^\uFEFF/, ''))
const sg = traceUpstreamSubgraph(raw.data.nodes, raw.data.edges, 'cdm.c_agt_deposit_acct_inf.acct_no')
console.log('nodes:'); for (const n of sg.nodes) console.log(' ', n.nodeLevel, n.id)
console.log('edges:'); for (const e of sg.edges) console.log(' ', e.edgeType, (e.role || ''), '|', e.source, '->', e.target, '| proc=', e.procName || '-')
