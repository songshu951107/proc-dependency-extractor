import fs from 'fs'
import { buildFieldImpactRows } from 'file:///d:/HB/work_zuoye/proc-dependency-extractor/frontend/src/components/impact/impactListUtils.js'

const dumpPath = 'C:/Users/cj199/AppData/Local/Temp/trae-agent-toolhost/analyze-dump.json'
const { data } = JSON.parse(fs.readFileSync(dumpPath, 'utf8').replace(/^\uFEFF/, ''))
const startId = 'fdm.f_ac_ctds_ldr_acct.acct_no'

const rows = buildFieldImpactRows(data.nodes, data.edges, startId)
console.log('总行数:', rows.length)
console.log('临时表行数:', rows.filter(r => r.temp).length)
console.log('非临时表行数:', rows.filter(r => !r.temp).length)
console.log('deposit_prod_struc_sum 相关行:', rows.filter(r => r.table.includes('deposit_prod_struc_sum')).length)

console.log('\n=== 含 deposit_prod_struc_sum 的行 ===')
for (const r of rows.filter(r => r.table.includes('deposit_prod_struc_sum'))) {
  console.log(`  proc=${r.procName} table=${r.table} field=${r.field} reasons=${r.reasons.join('|')} level=${r.level} temp=${r.temp}`)
}

console.log('\n=== 前 25 行概览 ===')
for (const r of rows.slice(0, 25)) {
  console.log(`  level=${r.level} temp=${r.temp} proc=${r.procName} table=${r.table}.${r.field} reasons=${r.reasons.join('|')}`)
}

// 检查 vt_00 是否在结果中
const vt = rows.filter(r => r.table.includes('vt_a_g_dp_deposit_prod_struc_sum'))
console.log('\n=== vt_a_g_dp_deposit_prod_struc_sum 临时表行 ===')
for (const r of vt) console.log(`  table=${r.table} field=${r.field} reasons=${r.reasons.join('|')} level=${r.level}`)