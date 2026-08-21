<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="dialogTitle"
    width="60%"
    top="8vh"
  >
    <el-table :data="detailRows" border size="small" style="width: 100%" max-height="520">
      <!-- 表维度 -->
      <template v-if="type === 'tables'">
        <el-table-column label="层级" width="110" align="center">
          <template #default="{ row }">第{{ levelLabel(row.level) }}层级</template>
        </el-table-column>
        <el-table-column prop="table" :label="upstream ? '来源表' : '受影响表'" min-width="260" show-overflow-tooltip />
        <el-table-column label="涉及脚本数" width="110" align="center">
          <template #default="{ row }">{{ row.procCount }}</template>
        </el-table-column>
        <el-table-column v-if="isFieldLevel" label="字段数" width="90" align="center">
          <template #default="{ row }">{{ row.fieldCount }}</template>
        </el-table-column>
      </template>

      <!-- 字段维度 -->
      <template v-else-if="type === 'fields'">
        <el-table-column label="层级" width="110" align="center">
          <template #default="{ row }">第{{ levelLabel(row.level) }}层级</template>
        </el-table-column>
        <el-table-column prop="table" :label="upstream ? '来源表' : '受影响表'" min-width="240" show-overflow-tooltip />
        <el-table-column prop="field" :label="upstream ? '来源字段' : '受影响字段'" min-width="140" show-overflow-tooltip />
        <el-table-column :label="upstream ? '关联原因' : '影响原因'" width="170" align="center">
          <template #default="{ row }">
            <el-tag v-for="r in row.reasons" :key="r" :type="r === '值影响' ? 'success' : 'warning'" size="small" style="margin: 2px">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="涉及脚本数" width="110" align="center">
          <template #default="{ row }">{{ row.procCount }}</template>
        </el-table-column>
      </template>

      <!-- proc 维度 -->
      <template v-else-if="type === 'procs'">
        <el-table-column prop="procName" label="脚本" min-width="280" show-overflow-tooltip />
        <el-table-column :label="upstream ? '来源表数' : '受影响表数'" width="120" align="center">
          <template #default="{ row }">{{ row.tableCount }}</template>
        </el-table-column>
        <el-table-column v-if="isFieldLevel" :label="upstream ? '来源字段数' : '受影响字段数'" width="130" align="center">
          <template #default="{ row }">{{ row.fieldCount }}</template>
        </el-table-column>
      </template>

      <!-- 报表维度 -->
      <template v-else-if="type === 'reports'">
        <el-table-column prop="depth" label="深度" width="70" align="center" />
        <el-table-column prop="table" label="血缘链路上的表" min-width="200" show-overflow-tooltip />
        <el-table-column prop="reportFile" label="报表文件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="reportName" label="报表名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="datasetName" label="数据集" width="90" />
        <el-table-column prop="connection" label="数据库" width="100" />
      </template>
    </el-table>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'
import { buildStatsDetailRows, levelLabel } from './impactListUtils'

const props = defineProps({
  visible: Boolean,
  // 'tables' | 'fields' | 'procs' | 'reports'
  type: { type: String, default: 'tables' },
  rows: { type: Array, default: () => [] },
  reportRows: { type: Array, default: () => [] },
  isFieldLevel: Boolean,
  upstream: Boolean
})
defineEmits(['update:visible'])

const detailRows = computed(() =>
  props.type === 'reports' ? props.reportRows : buildStatsDetailRows(props.type, props.rows)
)

const dialogTitle = computed(() => {
  const map = {
    tables: props.upstream ? '来源表清单' : '受影响表清单',
    fields: props.upstream ? '来源字段清单' : '受影响字段清单',
    procs: '涉及 proc 清单',
    reports: '受影响报表清单'
  }
  return map[props.type] || '明细清单'
})
</script>
