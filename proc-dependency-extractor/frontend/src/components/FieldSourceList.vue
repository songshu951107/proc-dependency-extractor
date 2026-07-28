<template>
  <div class="field-source-list">
    <!-- 目标表选择 -->
    <div class="header">
      <el-select
        v-model="selectedTable"
        placeholder="选择目标表"
        @change="loadFieldMapping"
        style="width: 320px"
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
      <el-checkbox v-model="recursive" @change="loadFieldMapping">
        递归追溯临时表来源
      </el-checkbox>
    </div>

    <!-- 字段列表 -->
    <el-table
      :data="flatRows"
      :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
      row-key="rowKey"
      :load="lazyLoad"
      lazy
      border
      v-loading="loading"
      style="margin-top: 12px"
    >
      <el-table-column prop="targetField" label="目标字段" width="180" />
      <el-table-column prop="sourceExpr" label="来源表达式" min-width="240" show-overflow-tooltip />
      <el-table-column label="来源表" width="260">
        <template #default="{ row }">
          <el-tag :type="tagType(row.sourceTableType)" size="small">
            {{ row.sourceTable || '-' }}
          </el-tag>
          <el-button
            v-if="row.sourceTable && row.sourceTable.startsWith('VT_') && !row.expanded"
            link
            type="primary"
            size="small"
            style="margin-left: 8px"
            @click="expandRow(row)"
          >展开临时表来源</el-button>
        </template>
      </el-table-column>
      <el-table-column label="最终来源" min-width="200">
        <template #default="{ row }">
          <span v-if="row.ultimateSource">
            {{ row.ultimateSource.sourceTable }}.{{ row.ultimateSource.sourceField }}
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getFieldMapping } from '../api/fieldMapping'
import { ElMessage } from 'element-plus'

const props = defineProps({
  procName: String,
  targetTables: { type: Array, default: () => [] }
})

const selectedTable = ref('')
const recursive = ref(true)
const loading = ref(false)
const fieldData = ref(null)

const tableOptions = computed(() => props.targetTables)

const flatRows = computed(() => {
  if (!fieldData.value || !fieldData.value.fields) return []
  return fieldData.value.fields.map((f) => ({
    rowKey: `${selectedTable.value}.${f.targetField}`,
    targetField: f.targetField,
    sourceExpr: f.directMappings?.[0]?.sourceExpr || '-',
    sourceTable: f.directMappings?.[0]?.sourceTable || null,
    sourceTableType: f.directMappings?.[0]?.sourceTableType || null,
    ultimateSource: f.ultimateSource,
    hasChildren: f.directMappings?.[0]?.sourceTable?.startsWith('VT_') || false,
    expanded: false,
    _fieldItem: f
  }))
})

function tagType(type) {
  const map = { SOURCE: 'success', TEMP: 'warning', TARGET: 'danger' }
  return map[type] || 'info'
}

async function loadFieldMapping() {
  if (!selectedTable.value) return
  loading.value = true
  try {
    const res = await getFieldMapping(props.procName, selectedTable.value, recursive.value)
    fieldData.value = res.data || res
  } catch (e) {
    ElMessage.error('加载字段映射失败')
    fieldData.value = null
  } finally {
    loading.value = false
  }
}

async function lazyLoad(row, treeNode, resolve) {
  if (!row.sourceTable || !row.sourceTable.startsWith('VT_')) {
    resolve([])
    return
  }
  try {
    const res = await getFieldMapping(props.procName, row.sourceTable, true)
    const data = res.data || res
    const children = (data.fields || []).map((f) => ({
      rowKey: `${row.sourceTable}.${f.targetField}.${Math.random()}`,
      targetField: f.targetField,
      sourceExpr: f.directMappings?.[0]?.sourceExpr || '-',
      sourceTable: f.directMappings?.[0]?.sourceTable || null,
      sourceTableType: f.directMappings?.[0]?.sourceTableType || null,
      ultimateSource: f.ultimateSource,
      hasChildren: f.directMappings?.[0]?.sourceTable?.startsWith('VT_') || false
    }))
    resolve(children)
  } catch (e) {
    resolve([])
  }
}

function expandRow(row) {
  row.expanded = true
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
  padding: 12px;
}
.header {
  display: flex;
  align-items: center;
  gap: 16px;
}
</style>
