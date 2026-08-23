<template>
  <div class="impact-result-tabs">
    <div class="tabs-bar">
      <el-tabs v-model="activeTab">
        <el-tab-pane
          v-for="lv in levels"
          :key="lv"
          :name="String(lv)"
          :label="`第${levelLabel(lv)}层级（${rowsByLevel[lv].length}）`"
        >
          <el-table :data="rowsByLevel[lv]" border size="small" style="width: 100%">
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
        </el-tab-pane>
      </el-tabs>
      <div class="tabs-toolbar">
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
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as XLSX from 'xlsx'
import { levelLabel } from './impactListUtils'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  isFieldLevel: Boolean,
  upstream: Boolean,
  exportFileName: String
})
defineEmits(['viewScript', 'viewPath'])

// 影响原因筛选：默认只看值影响（纯净值血缘）；空串=全部。仅下游字段级清单有此维度
const reasonFilter = ref('值影响')
const filteredRows = computed(() => {
  if (!props.isFieldLevel || props.upstream || !reasonFilter.value) return props.rows
  return props.rows.filter(r => (r.reasons || []).includes(reasonFilter.value))
})

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
  ElMessage.success(`已导出 ${filteredRows.value.length} 行（当前筛选：${reasonFilter.value || '全部'}）`)
}
</script>

<style scoped>
.tabs-bar { position: relative; }
.tabs-toolbar { position: absolute; right: 0; top: 2px; z-index: 1; display: flex; align-items: center; gap: 8px; }
.reason-filter { width: 130px; }
</style>
