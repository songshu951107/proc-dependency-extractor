<template>
  <!-- TRANSFORM 节点 -->
  <div v-if="data.nodeLevel === 'TRANSFORM'" class="impact-node transform-node"
       :style="{ borderLeftColor: logicColor }">
    <div class="layer-badge" :style="{ background: logicColor }">
      {{ logicBadge }}
    </div>
    <div class="node-body">
      <div class="node-label" :title="data.expression">{{ expressionShort }}</div>
      <div v-if="data.logicType" class="logic-tag">{{ data.logicType }}</div>
    </div>
  </div>
  <!-- 表/字段节点 -->
  <div v-else class="impact-node" :style="{ borderLeftColor: color }">
    <div class="layer-badge" :style="{ background: color }">{{ layer || '?' }}</div>
    <div class="node-body">
      <div class="node-label">{{ label }}</div>
      <div v-if="matchedColumns && matchedColumns.length" class="matched-fields">
        <span v-for="col in matchedColumns" :key="col" class="field-tag">{{ col }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  id: { type: String, required: true },
  data: { type: Object, default: () => ({}) }
})

const LAYER_COLORS = {
  FDM: '#ef4444',
  ODM: '#f59e0b',
  CDM: '#3b82f6',
  TDM: '#10b981',
  PID: '#8b5cf6',
  UNKNOWN: '#94a3b8'
}

const LOGIC_COLORS = {
  DIRECT: '#94a3b8', LITERAL: '#64748b', ADDITION: '#3b82f6',
  SUBTRACTION: '#06b6d4', MULTIPLICATION: '#8b5cf6', DIVISION: '#a855f7',
  CASE: '#f59e0b', SUM: '#10b981', COUNT: '#22c55e', AVG: '#16a34a',
  MAX: '#15803d', MIN: '#166534', NVL: '#ef4444', CONCAT: '#ec4899',
  FUNCTION_OTHER: '#6366f1'
}
const LOGIC_BADGES = {
  ADDITION: '+', SUBTRACTION: '-', MULTIPLICATION: '×', DIVISION: '÷',
  SUM: 'Σ', COUNT: '#', AVG: 'Ā', MAX: '↑', MIN: '↓',
  CASE: '?', NVL: 'N', CONCAT: '∥', DIRECT: '·', LITERAL: '"', FUNCTION_OTHER: 'ƒ'
}

const label = computed(() => props.data.label || props.id)
const layer = computed(() => props.data.layer || 'UNKNOWN')
const matchedColumns = computed(() => props.data.matchedColumns || [])

const color = computed(() => {
  if (props.data.isTemp) return '#faad14'
  return LAYER_COLORS[props.data.layer] || LAYER_COLORS.UNKNOWN
})

const logicColor = computed(() => LOGIC_COLORS[props.data.logicType] || '#94a3b8')
const logicBadge = computed(() => LOGIC_BADGES[props.data.logicType] || 'T')
const expressionShort = computed(() => {
  const expr = props.data.expression || props.data.label || ''
  return expr.length > 30 ? expr.substring(0, 30) + '...' : expr
})
</script>

<style scoped>
.impact-node {
  display: flex;
  align-items: stretch;
  min-width: 180px;
  max-width: 280px;
  padding: 0;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-left: 4px solid #94a3b8;
  border-radius: 4px;
  font-size: 12px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.layer-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  color: #fff;
  font-weight: 700;
  font-size: 10px;
  letter-spacing: 0.5px;
  border-radius: 4px 0 0 4px;
  writing-mode: horizontal-tb;
}
.node-body {
  flex: 1;
  padding: 6px 10px;
  min-width: 0;
}
.node-label {
  font-weight: 600;
  color: #1f1f1f;
  word-break: break-all;
  line-height: 1.3;
}
.matched-fields {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}
.field-tag {
  display: inline-block;
  padding: 1px 5px;
  background: #fee;
  color: #c00;
  border-radius: 2px;
  font-size: 10px;
}
/* TRANSFORM 节点样式 */
.transform-node {
  border-left-style: solid;
  border-left-width: 6px;
  background: #fff;
}
.transform-node .node-label {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #475569;
}
.logic-tag {
  display: inline-block;
  margin-top: 2px;
  padding: 0 4px;
  background: #f1f5f9;
  color: #475569;
  border-radius: 2px;
  font-size: 10px;
  font-weight: 600;
}
</style>
