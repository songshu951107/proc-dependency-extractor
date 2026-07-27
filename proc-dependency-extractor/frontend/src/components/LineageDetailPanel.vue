<template>
  <el-drawer
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="title"
    size="400px"
    direction="rtl"
  >
    <!-- 节点详情 -->
    <div v-if="type === 'node' && node">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="表名">{{ node.id }}</el-descriptions-item>
        <el-descriptions-item label="完整名">{{ node.fullTableName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Schema">{{ node.schemaName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="tagType">{{ node.nodeType }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider>上下游关系</el-divider>
      <el-button size="small" @click="$emit('trace-upstream', node.id)">追溯上游</el-button>
      <el-button size="small" @click="$emit('trace-downstream', node.id)">追溯下游</el-button>
    </div>

    <!-- 边详情 -->
    <div v-else-if="type === 'edge' && edge">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="源表">{{ edge.source }}</el-descriptions-item>
        <el-descriptions-item label="目标表">{{ edge.target }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ edge.operation }}</el-descriptions-item>
        <el-descriptions-item label="SQL 块序号">{{ edge.sqlBlockSeq }}</el-descriptions-item>
      </el-descriptions>

      <el-divider>JOIN / WHERE 详情</el-divider>
      <el-collapse v-if="edge.filters && edge.filters.length">
        <el-collapse-item
          v-for="(f, i) in edge.filters"
          :key="i"
          :title="`${f.filterType}: ${f.filterClause}`"
        >
          <div>
            <el-tag size="small" :type="f.filterType === 'JOIN' ? 'success' : 'warning'">
              {{ f.filterType }}
            </el-tag>
            <pre style="margin:8px 0;background:#f5f7fa;padding:8px;font-size:12px">{{ f.filterClause }}</pre>
            <el-tag v-for="field in (f.fields || [])" :key="field" size="small" style="margin:2px">
              {{ field }}
            </el-tag>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-else description="无 JOIN/WHERE 条件" :image-size="60" />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  type: String,
  node: Object,
  edge: Object
})

defineEmits(['update:visible', 'trace-upstream', 'trace-downstream'])

const title = computed(() => {
  if (props.type === 'node') return '节点详情'
  if (props.type === 'edge') return '边详情'
  return '详情'
})

const tagType = computed(() => {
  const map = { SOURCE: 'success', TEMP: 'warning', TARGET: 'danger' }
  return map[props.node?.nodeType] || 'info'
})
</script>
