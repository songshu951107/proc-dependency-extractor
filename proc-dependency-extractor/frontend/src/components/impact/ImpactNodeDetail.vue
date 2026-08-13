<template>
  <div class="node-detail">
    <!-- TRANSFORM 节点详情 -->
    <template v-if="node.nodeLevel === 'TRANSFORM'">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="类型">
          <el-tag type="warning" size="small">TRANSFORM</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="logic_type">
          <el-tag :color="logicColor" size="small" effect="dark">{{ node.logicType || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="表达式">
          <code class="expression-code">{{ node.expression }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="SQL 提示" v-if="node.sqlHint">
          <code class="hint-code">{{ node.sqlHint }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="存储过程" v-if="node.procName">
          {{ node.procName }}
        </el-descriptions-item>
        <el-descriptions-item label="目标字段" v-if="node.id">
          {{ node.id }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="inputFields.length" style="margin-top: 10px">
        <div style="font-weight: bold; margin-bottom: 5px">输入字段</div>
        <div v-for="(inp, i) in inputFields" :key="i" class="input-item">
          <el-tag :type="inp.role === 'condition' ? 'warning' : 'success'" size="small">
            {{ inp.role }}
          </el-tag>
          <code>{{ inp.colKey }}</code>
          <span v-if="inp.alias" style="color: #999; margin-left: 6px">({{ inp.alias }})</span>
        </div>
      </div>
    </template>

    <!-- 表/字段节点详情 -->
    <template v-else>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="ID">{{ node.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ node.label }}</el-descriptions-item>
        <el-descriptions-item label="类型" v-if="node.type">
          <el-tag :type="typeTagType(node.type)" size="small">{{ node.type }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="层级">{{ node.nodeLevel }}</el-descriptions-item>
        <el-descriptions-item label="深度">{{ node.depth ?? '-' }}</el-descriptions-item>
      </el-descriptions>
    </template>
    <div style="margin-top: 10px; text-align: center">
      <el-button size="small" type="primary" @click="$emit('expand', node)">展开下游</el-button>
      <el-button size="small" @click="$emit('collapse', node)">收起下游</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  node: Object,
  edges: { type: Array, default: () => [] }
})
defineEmits(['expand', 'collapse'])

const typeTagType = (type) => {
  if (type === 'SOURCE') return 'success'
  if (type === 'TEMP') return 'warning'
  if (type === 'TARGET') return 'danger'
  return 'info'
}

const inputFields = computed(() => {
  if (!props.node || props.node.nodeLevel !== 'TRANSFORM') return []
  return props.edges
    .filter(e => e.target === props.node.id && e.edgeType === 'INPUT_TO')
    .map(e => ({
      colKey: e.source,
      role: e.role || 'value',
      alias: e.sourceAlias
    }))
})

const LOGIC_COLORS = {
  DIRECT: '#94a3b8', LITERAL: '#64748b', ADDITION: '#3b82f6',
  SUBTRACTION: '#06b6d4', MULTIPLICATION: '#8b5cf6', DIVISION: '#a855f7',
  CASE: '#f59e0b', SUM: '#10b981', COUNT: '#22c55e', AVG: '#16a34a',
  MAX: '#15803d', MIN: '#166534', NVL: '#ef4444', CONCAT: '#ec4899',
  FUNCTION_OTHER: '#6366f1'
}
const logicColor = computed(() => LOGIC_COLORS[props.node?.logicType] || '#94a3b8')
</script>

<style scoped>
.expression-code {
  display: block;
  padding: 6px 8px;
  background: #f5f5f5;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.hint-code {
  display: block;
  padding: 4px 8px;
  background: #fffbe6;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #d48806;
}
.input-item {
  padding: 4px 8px;
  margin-bottom: 4px;
  background: #f5f5f5;
  border-radius: 2px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
