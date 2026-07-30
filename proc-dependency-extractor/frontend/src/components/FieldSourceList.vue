<template>
  <div class="field-source-list">
    <!-- 头部：目标表 + SQL 块选择 + 递归选项 -->
    <div class="header">
      <el-select
        v-model="selectedTable"
        placeholder="选择目标表"
        @change="loadFieldMapping"
        style="width: 280px"
      >
        <el-option
          v-for="t in tableOptions"
          :key="t.name"
          :label="t.name"
          :value="t.name"
        >
          <span>{{ t.name }}</span>
          <el-tag
            size="small"
            :type="tagType(t.type)"
            style="margin-left: 8px"
          >{{ t.type }}</el-tag>
        </el-option>
      </el-select>
      <el-select
        v-model="selectedBlockSeq"
        placeholder="SQL 块"
        @change="filterByBlock"
        style="width: 220px"
        :disabled="!fieldData || !fieldData.fields?.length"
      >
        <el-option label="全部 SQL 块" :value="'all'" />
        <el-option
          v-for="b in blockOptions"
          :key="b.seq"
          :label="`Block${b.seq} (${b.sourceTable || '-'})`"
          :value="b.seq"
        />
      </el-select>
      <el-checkbox v-model="recursive" @change="loadFieldMapping">
        递归追溯临时表来源
      </el-checkbox>
      <span v-if="fieldData" class="meta">
        共 {{ fieldData.fields?.length || 0 }} 字段
        <span v-if="selectedBlockSeq !== 'all'">
          · 当前 Block{{ selectedBlockSeq }}: {{ filteredFields.length }} 字段
        </span>
      </span>
    </div>

    <!-- 字段列表：按 SQL 块筛选后展示，每个字段只出现一次 -->
    <el-table
      :data="flatRows"
      border
      v-loading="loading"
      style="width: 100%"
    >
      <el-table-column prop="targetField" label="目标字段" width="180" />
      <el-table-column prop="sourceExpr" label="来源表达式" min-width="280" show-overflow-tooltip />
      <el-table-column label="来源表" width="280">
        <template #default="{ row }">
          <el-tag :type="tagType(row.sourceTableType)" size="small">
            {{ row.sourceTable || '-' }}
          </el-tag>
          <el-button
            v-if="isTempTable(row.sourceTable)"
            link
            type="primary"
            size="small"
            style="margin-left: 8px"
            @click="showLineage(row.sourceTable)"
          >查看血缘</el-button>
        </template>
      </el-table-column>
      <el-table-column label="最终来源（溯源到非临时表）" min-width="240">
        <template #default="{ row }">
          <span v-if="row.ultimateSource">
            <el-tag :type="tagType(row.ultimateSource.sourceTableType)" size="small">
              {{ row.ultimateSource.sourceTable }}
            </el-tag>
            .{{ row.ultimateSource.sourceField }}
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 临时表血缘图弹窗 -->
    <FieldLineageDialog
      v-model:visible="lineageDialogVisible"
      :proc-name="procName"
      :table-name="lineageTableName"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getFieldMapping } from '../api/fieldMapping'
import { ElMessage } from 'element-plus'
import FieldLineageDialog from './FieldLineageDialog.vue'

const props = defineProps({
  procName: String,
  targetTables: { type: Array, default: () => [] }
})

const selectedTable = ref('')
const selectedBlockSeq = ref('all')
const recursive = ref(true)
const loading = ref(false)
const fieldData = ref(null)

const lineageDialogVisible = ref(false)
const lineageTableName = ref('')

const tableOptions = computed(() => props.targetTables)

/**
 * 从 fieldData 中提取所有出现过的 SQL 块（去重 + 排序）
 * 每个 DirectMapping 已带 sqlBlockSeq 字段
 */
const blockOptions = computed(() => {
  if (!fieldData.value || !fieldData.value.fields) return []
  const seqSet = new Map()
  fieldData.value.fields.forEach((f) => {
    ;(f.directMappings || []).forEach((dm) => {
      if (dm.sqlBlockSeq != null && !seqSet.has(dm.sqlBlockSeq)) {
        seqSet.set(dm.sqlBlockSeq, {
          seq: dm.sqlBlockSeq,
          sourceTable: dm.sourceTable
        })
      }
    })
  })
  return Array.from(seqSet.values()).sort((a, b) => a.seq - b.seq)
})

/**
 * 按 SQL 块筛选后的字段列表
 * - selectedBlockSeq = 'all' 时返回全部字段（每个字段取第一个来源展示）
 * - 选中具体块时，只返回该块的字段，且每个字段只取该块的来源
 *
 * ultimateSource 只有当当前来源是临时表（VT_ 开头）时才有意义：
 * - 若当前来源是 VT_，使用字段级别的 ultimateSource（追溯该临时表的最终来源）
 * - 若当前来源为空或非 VT_，ultimateSource 置为 null（避免"无来源表但有最终来源"的歧义）
 */
const filteredFields = computed(() => {
  if (!fieldData.value || !fieldData.value.fields) return []
  if (selectedBlockSeq.value === 'all') {
    // 全部：每个字段取第一个来源
    return fieldData.value.fields.map((f) => {
      const dm = f.directMappings?.[0] || {}
      const isTemp = isTempTable(dm.sourceTable)
      return {
        targetField: f.targetField,
        sourceExpr: dm.sourceExpr || '-',
        sourceTable: dm.sourceTable || null,
        sourceTableType: dm.sourceTableType || null,
        ultimateSource: isTemp ? f.ultimateSource : null
      }
    })
  }
  // 按块筛选：每个字段只取该块的来源
  const seq = Number(selectedBlockSeq.value)
  const result = []
  fieldData.value.fields.forEach((f) => {
    const dm = (f.directMappings || []).find((d) => d.sqlBlockSeq === seq)
    if (dm) {
      const isTemp = isTempTable(dm.sourceTable)
      result.push({
        targetField: f.targetField,
        sourceExpr: dm.sourceExpr || '-',
        sourceTable: dm.sourceTable || null,
        sourceTableType: dm.sourceTableType || null,
        ultimateSource: isTemp ? f.ultimateSource : null
      })
    }
  })
  return result
})

const flatRows = computed(() => filteredFields.value)

function tagType(type) {
  const map = { SOURCE: 'success', TEMP: 'warning', TARGET: 'danger' }
  return map[type] || 'info'
}

function isTempTable(name) {
  return name && name.startsWith('VT_')
}

async function loadFieldMapping() {
  if (!selectedTable.value) return
  loading.value = true
  selectedBlockSeq.value = 'all'
  try {
    const res = await getFieldMapping(props.procName, selectedTable.value, recursive.value)
    fieldData.value = res.data || res
    // 默认选第一个块（若只有一个块则保持 all）
    if (blockOptions.value.length > 1) {
      selectedBlockSeq.value = blockOptions.value[0].seq
    }
  } catch (e) {
    ElMessage.error('加载字段映射失败')
    fieldData.value = null
  } finally {
    loading.value = false
  }
}

function filterByBlock() {
  // 仅触发响应式重新计算，无需额外操作
}

function showLineage(tableName) {
  lineageTableName.value = tableName
  lineageDialogVisible.value = true
}

watch(
  () => props.targetTables,
  (list) => {
    if (list && list.length && !selectedTable.value) {
      const target = list.find((t) => t.type === 'TARGET') || list[0]
      selectedTable.value = target.name
      loadFieldMapping()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.field-source-list {
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
  overflow: auto;
}
.header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.header .meta {
  color: #909399;
  font-size: 13px;
}
</style>
