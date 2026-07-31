<template>
  <div class="impact-node" :style="{ borderLeftColor: color }">
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

const label = computed(() => props.data.label || props.id)
const layer = computed(() => props.data.layer || 'UNKNOWN')
const matchedColumns = computed(() => props.data.matchedColumns || [])

const color = computed(() => {
  if (props.data.isTemp) return '#faad14'
  return LAYER_COLORS[props.data.layer] || LAYER_COLORS.UNKNOWN
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
</style>
