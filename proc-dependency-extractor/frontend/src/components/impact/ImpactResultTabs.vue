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
            <el-table-column prop="procName" label="受影响脚本" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link type="primary" @click="$emit('viewScript', row)">{{ row.procName }}</el-link>
              </template>
            </el-table-column>
            <el-table-column prop="table" label="受影响表" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="isFieldLevel" prop="field" label="受影响字段" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="isFieldLevel" prop="reason" label="影响原因" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.reason !== '直接来源'" type="warning" size="small">{{ row.reason }}</el-tag>
                <el-tag v-else type="success" size="small">直接来源</el-tag>
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
      <el-button size="small" type="success" plain class="export-btn" @click="exportAll">导出 Excel</el-button>
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
  exportFileName: String
})
defineEmits(['viewScript', 'viewPath'])

const rowsByLevel = computed(() => {
  const map = {}
  for (const r of props.rows) {
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
  if (!props.rows.length) return
  const header = props.isFieldLevel
    ? ['层级', '受影响脚本', '受影响表', '受影响字段', '影响原因']
    : ['层级', '受影响脚本', '受影响表']
  const data = props.rows.map(r => props.isFieldLevel
    ? [`第${levelLabel(r.level ?? 0)}层级`, r.procName, r.table, r.field, r.reason]
    : [`第${levelLabel(r.level ?? 0)}层级`, r.procName, r.table])
  const ws = XLSX.utils.aoa_to_sheet([header, ...data])
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '受影响清单')
  const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  XLSX.writeFile(wb, `${props.exportFileName || '影响分析'}_${stamp}.xlsx`)
  ElMessage.success(`已导出 ${props.rows.length} 行`)
}
</script>

<style scoped>
.tabs-bar { position: relative; }
.export-btn { position: absolute; right: 0; top: 2px; z-index: 1; }
</style>
