import fs from 'fs'

const dump = JSON.parse(fs.readFileSync('C:/Users/cj199/AppData/Local/Temp/trae-agent-toolhost/analyze-dump.json', 'utf8').replace(/^\uFEFF/, '')).data
const startId = 'fdm.f_ac_ctds_ldr_acct.acct_no'
const filters = dump.edges.filter(e => e.edgeType === 'FILTERS')
console.log('FILTERS 边总数:', filters.length)
const bySource = new Map()
for (const e of filters) bySource.set(e.source, (bySource.get(e.source) || 0) + 1)
console.log('按 source 分组:')
for (const [s, c] of bySource) console.log(`  ${s} (${c} 条) ${s === startId ? '<- 起点列(第一轮通道)' : '<- 非起点列(需要中间行影响通道)'}`)
// vt_01.term_typ 的 FILTERS 边
const tt = filters.filter(e => e.source.includes('prod_struc_sum_01'))
console.log('\nvt_prod_struc_sum_01 列的 FILTERS 边:', tt.length)
