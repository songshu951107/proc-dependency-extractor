import fs from 'fs'
import { buildFieldImpactRows } from 'file:///d:/HB/work_zuoye/proc-dependency-extractor/frontend/src/components/impact/impactListUtils.js'

const dumpPath = 'C:/Users/cj199/AppData/Local/Temp/trae-agent-toolhost/analyze-dump.json'
const { data } = JSON.parse(fs.readFileSync(dumpPath, 'utf8').replace(/^\uFEFF/, ''))

const startId = 'fdm.f_ac_ctds_ldr_acct.acct_no'
const rows = buildFieldImpactRows(data.nodes, data.edges, startId)

// 复现 ImpactResultTabs 的 reasonFilter='值影响' 筛选
function filterReason(rows, reason) {
  return rows.filter(r => (r.reasons || []).includes(reason))
}

console.log('=== buildFieldImpactRows 全部行 ===')
console.log('总行数:', rows.length)
console.log('其中 temp=true:', rows.filter(r=>r.temp).length)
console.log('rows 里含 vt_a_g_dp_deposit_prod_struc_sum 的行:', rows.filter(r=>r.table.includes('vt_a_g_dp_deposit_prod_struc_sum')).length)
console.log('rows 里含 a_g_dp_deposit_prod_struc_sum 物理表行:', rows.filter(r=>r.table==='pid.a_g_dp_deposit_prod_struc_sum').length)

// 列出所有 temp=true 的行（补充的临时表行）
console.log('\n=== 所有临时表行(temp=true) ===')
for (const r of rows.filter(r=>r.temp)) {
  console.log(`  ${r.procName} -> ${r.table}.${r.field} reasons=[${r.reasons.join('|')}] lv=${r.level}`)
}

console.log('\n=== 值影响筛选后含 deposit !===')
const valueRows = filterReason(rows, '值影响')
const vtInValue = valueRows.filter(r=>r.table.includes('vt_a_g_dp_deposit_prod_struc_sum'))
console.log('值影响筛选后总行数:', valueRows.length)
console.log('值影响筛选后 deposit 临时表行:', vtInValue.length)
for (const r of vtInValue) console.log(`  ${r.table}.${r.field} reasons=[${r.reasons.join('|')}] lv=${r.level}`)

console.log('\n=== 值影响筛选后所有 temp=true 行 ===')
for (const r of valueRows.filter(r=>r.temp)) {
  console.log(`  ${r.procName} -> ${r.table}.${r.field} lv=${r.level}`)
}

// 检查是否无任何值影响行时触发智能回退到全部
const hasValue = rows.some(r=>(r.reasons||[]).includes('值影响'))
console.log('\n存在值影响行吗:', hasValue, '=> 默认筛选是否保留 值影响:', hasValue)