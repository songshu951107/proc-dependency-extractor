<template>
  <div class="impact-result-tabs">
    <div class="tabs-bar">
      <div class="tabs-toolbar">
        <el-select
          v-model="schemaFilter"
          size="small"
          class="schema-filter"
          clearable
          filterable
          title="按受影响表的 Schema 筛选清单"
          placeholder="全部 Schema"
        >
          <el-option v-for="s in schemaOptions" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select
          v-if="isFieldLevel && !upstream"
          v-model="reasonFilter"
          size="small"
          class="reason-filter"
          title="按影响原因筛选清单：值影响=参与列值计算；行过滤=ON/WHERE 条件只影响行集"
        >
          <el-option label="值影响" value="值影响" />
          <el-option label="行过滤·JOIN" value="行过滤·JOIN" />
          <el-option label="行过滤·WHERE" value="行过滤·WHERE" />
          <el-option label="全部原因" value="" />
        </el-select>
        <el-button size="small" type="success" plain class="export-btn" @click="exportAll">导出 Excel</el-button>
      </div>
      <el-tabs v-model="activeTab" class="impact-tabs">
        <el-tab-pane
          v-for="lv in levels"
          :key="lv"
          :name="String(lv)"
          :label="`第${levelLabel(lv)}层级（${rowsByLevel[lv].length}）`"
        >
          <el-table :data="pagedRows(lv)" border size="small" style="width: 100%">
            <el-table-column prop="procName" :label="upstream ? '关联脚本' : '受影响脚本'" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link type="primary" @click="$emit('viewScript', row)">{{ row.procName }}</el-link>
              </template>
            </el-table-column>
            <el-table-column prop="table" :label="upstream ? '来源表' : '受影响表'" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.table }}
                <el-tag v-if="row.temp" type="info" size="small">临时表</el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="isFieldLevel" prop="field" :label="upstream ? '来源字段' : '受影响字段'" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="isFieldLevel" :label="upstream ? '关联原因' : '影响原因'" width="150" align="center">
              <template #default="{ row }">
                <el-tag v-for="r in row.reasons" :key="r" :type="r === '值影响' ? 'success' : 'warning'" size="small" style="margin: 2px">{{ r }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="$emit('viewPath', row)">链路图</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager-bar" v-if="rowsByLevel[lv].length > PAGE_SIZE">
            <el-pagination
              :total="rowsByLevel[lv].length"
              :page-size="PAGE_SIZE"
              :current-page="currentPage"
              layout="total, prev, pager, next, jumper"
              @current-change="currentPage = $event"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
      <el-alert
        v-if="isFieldLevel && !upstream && schemaRows.length && !filteredRows.length"
        type="warning"
        :closable="false"
        class="empty-reason-alert"
      >
        <template #title>
          当前筛选「{{ reasonFilter }}」无匹配行。该分析存在以下影响类型：
          <el-tag
            v-for="opt in availableReasons"
            :key="opt.reason"
            :type="opt.reason === '值影响' ? 'success' : 'warning'"
            size="small"
            class="reason-switch-tag"
            @click="reasonFilter = opt.reason"
          >
            {{ opt.reason }}（{{ opt.count }} 行）
          </el-tag>
          <el-button link type="primary" size="small" @click="reasonFilter = ''">查看全部</el-button>
        </template>
      </el-alert>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as XLSX from 'xlsx'
import { levelLabel, REASON_ORDER } from './impactListUtils'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  isFieldLevel: Boolean,
  upstream: Boolean,
  exportFileName: String
})
defineEmits(['viewScript', 'viewPath'])

// 影响原因筛选：默认只看值影响（纯净值血缘）；空串=全部。仅下游字段级清单有此维度
const reasonFilter = ref('值影响')

// Schema 筛选：按受影响表（row.table = schema.table）的 schema 维度过滤清单；空=全部
const schemaFilter = ref('')
const schemaOptions = computed(() => {
  const set = new Set()
  for (const r of props.rows) {
    const s = (r.table || '').split('.')[0]
    if (s) set.add(s)
  }
  return [...set].sort()
})
const schemaRows = computed(() => {
  if (!schemaFilter.value) return props.rows
  return props.rows.filter(r => (r.table || '').split('.')[0] === schemaFilter.value)
})
const filteredRows = computed(() => {
  if (!props.isFieldLevel || props.upstream || !reasonFilter.value) return schemaRows.value
  return schemaRows.value.filter(r => (r.reasons || []).includes(reasonFilter.value))
})

// 当前清单实际存在的影响类型及行数（用于空态提示与智能默认回退；按 Schema 筛选后口径）
const availableReasons = computed(() => {
  if (!props.isFieldLevel || props.upstream) return []
  const count = new Map()
  for (const r of schemaRows.value) {
    for (const reason of (r.reasons || [])) count.set(reason, (count.get(reason) || 0) + 1)
  }
  // 展示顺序与筛选下拉一致：值影响 → JOIN → WHERE，有数据的排前
  return REASON_ORDER.map(reason => ({ reason, count: count.get(reason) || 0 }))
    .filter(opt => opt.count > 0)
})

// 智能默认回退：清单中不存在"值影响"行（纯关联键字段，只产生行过滤影响）时，
// 默认筛选自动回退到"全部原因"，避免卡片下方一片空白（2026-08-25 c_agt_credit_card_acct.acct_no 问题）
watch(() => props.rows, (rows) => {
  if (!props.isFieldLevel || props.upstream || !rows?.length) return
  if (reasonFilter.value === '值影响' && !rows.some(r => (r.reasons || []).includes('值影响'))) {
    reasonFilter.value = ''
  }
}, { immediate: true })

const rowsByLevel = computed(() => {
  const map = {}
  for (const r of filteredRows.value) {
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

// 分页：每层级每页 20 行；切换层级或筛选时回到第 1 页
const PAGE_SIZE = 20
const currentPage = ref(1)
watch([activeTab, reasonFilter, schemaFilter], () => { currentPage.value = 1 })
function pagedRows(lv) {
  const rows = rowsByLevel.value[lv] || []
  const start = (currentPage.value - 1) * PAGE_SIZE
  return rows.slice(start, start + PAGE_SIZE)
}

function exportAll() {
  if (!filteredRows.value.length) return
  const header = props.isFieldLevel
    ? (props.upstream
        ? ['层级', '关联脚本', '来源表', '来源字段', '关联原因']
        : ['层级', '受影响脚本', '受影响表', '受影响字段', '影响原因'])
    : ['层级', '受影响脚本', '受影响表']
  const data = filteredRows.value.map(r => {
    const table = r.temp ? `${r.table}(临时表)` : r.table
    return props.isFieldLevel
      ? [`第${levelLabel(r.level ?? 0)}层级`, r.procName, table, r.field, r.reasons.join('+')]
      : [`第${levelLabel(r.level ?? 0)}层级`, r.procName, table]
  })
  const ws = XLSX.utils.aoa_to_sheet([header, ...data])
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, props.upstream ? '来源清单' : '受影响清单')
  const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  XLSX.writeFile(wb, `${props.exportFileName || '影响分析'}_${stamp}.xlsx`)
  const parts = []
  if (schemaFilter.value) parts.push(`Schema：${schemaFilter.value}`)
  if (props.isFieldLevel && !props.upstream) parts.push(`原因：${reasonFilter.value || '全部'}`)
  ElMessage.success(`已导出 ${filteredRows.value.length} 行${parts.length ? `（${parts.join('，')}）` : ''}`)
}
</script>

<style scoped>
/* 工具条独占一行置于层级 tab 上方（右对齐），层级 tab 独占整行，
   消除层级较多（如第9/10层级）时 tab 与筛选框的重叠 */
.tabs-toolbar { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.schema-filter { width: 120px; }
.reason-filter { width: 130px; }
.pager-bar { display: flex; justify-content: flex-end; margin-top: 8px; }
.empty-reason-alert { margin-top: 8px; }
.reason-switch-tag { cursor: pointer; margin: 0 4px; }
</style>
