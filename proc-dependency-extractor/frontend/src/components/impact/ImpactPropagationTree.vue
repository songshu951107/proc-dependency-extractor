<template>
  <div class="propagation-tree">
    <!-- 源表卡片 -->
    <div class="depth-section">
      <div class="depth-label">源表</div>
      <div class="tree-cards">
        <div class="impact-card">
          <div class="type-badge" :style="{ background: getSourceColor() }"></div>
          <div class="card-content">
            <span class="table-name">{{ sourceTable }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 各层级 -->
    <template v-for="depth in sortedDepths" :key="depth">
      <div class="depth-section">
        <div class="depth-arrow">↓</div>
        <div class="depth-label">Level {{ depth }}（{{ displayData[depth].length }} 张表）</div>
        <div class="tree-cards">
          <div
            v-for="item in displayData[depth]"
            :key="item.name"
            class="impact-card"
          >
            <div
              class="type-badge"
              :style="{ background: getLayerColor(item) }"
            ></div>
            <div class="card-content">
              <span class="table-name">{{ item.shortName || item.name }}</span>
              <span v-if="item.impactSource" class="impact-source">
                ← 来自 {{ item.impactSource }}
              </span>
            </div>
            <button
              class="code-btn"
              @click="$emit('viewScript', item)"
              :disabled="!item.scriptFile"
              :title="item.scriptFile ? '查看代码片段' : '无关联脚本'"
            >
              代码
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- 空数据提示 -->
    <el-empty
      v-if="sortedDepths.length === 0"
      description="无下游影响"
      :image-size="60"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  sourceTable: { type: String, default: '' },
  sourceLayer: { type: String, default: '' },
  impactedByDepth: { type: Object, default: () => ({}) },
  collapsedTempTables: { type: Boolean, default: false }
})

defineEmits(['viewScript'])

const LAYER_COLORS = {
  FDM: '#ef4444',
  ODM: '#f59e0b',
  CDM: '#3b82f6',
  TDM: '#10b981',
  PID: '#8b5cf6',
  UNKNOWN: '#94a3b8',
  SOURCE: '#52c41a',
  TEMP: '#faad14'
}

function getLayerColor(item) {
  if (item.temp) return LAYER_COLORS.TEMP
  return LAYER_COLORS[item.layer] || LAYER_COLORS.UNKNOWN
}

function getSourceColor() {
  return LAYER_COLORS[props.sourceLayer] || LAYER_COLORS.SOURCE
}

const displayData = computed(() => {
  if (!props.impactedByDepth) return {}
  if (!props.collapsedTempTables) return props.impactedByDepth
  return rebuildWithoutTemp(props.impactedByDepth, props.sourceTable)
})

const sortedDepths = computed(() => {
  return Object.keys(displayData.value)
    .map(Number)
    .sort((a, b) => a - b)
})

function rebuildWithoutTemp(impactedByDepth, sourceTable) {
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push(t.name)
      }
    }
  }

  const tableInfoMap = {}
  const nonTempTables = new Set([sourceTable])
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.name] = { layer: t.layer, temp: t.temp, scriptFile: t.scriptFile }
      if (!t.temp) nonTempTables.add(t.name)
    }
  }

  const depthMap = { [sourceTable]: 0 }
  const impactSourceMap = {}
  const queue = [sourceTable]

  while (queue.length) {
    const current = queue.shift()
    const currentDepth = depthMap[current]
    const neighbors = adjacency[current] || []

    for (const neighbor of neighbors) {
      if (nonTempTables.has(neighbor) && !(neighbor in depthMap)) {
        depthMap[neighbor] = currentDepth + 1
        impactSourceMap[neighbor] = current
        queue.push(neighbor)
      }
      if (!nonTempTables.has(neighbor)) {
        const tempQueue = [neighbor]
        const visited = new Set([neighbor])
        while (tempQueue.length) {
          const tempNode = tempQueue.shift()
          const tempNeighbors = adjacency[tempNode] || []
          for (const tn of tempNeighbors) {
            if (visited.has(tn)) continue
            visited.add(tn)
            if (nonTempTables.has(tn) && !(tn in depthMap)) {
              depthMap[tn] = currentDepth + 1
              impactSourceMap[tn] = current
              queue.push(tn)
            } else if (!nonTempTables.has(tn)) {
              tempQueue.push(tn)
            }
          }
        }
      }
    }
  }

  const result = {}
  for (const [table, depth] of Object.entries(depthMap)) {
    if (depth === 0) continue
    if (!result[depth]) result[depth] = []
    result[depth].push({
      name: table,
      depth,
      impactSource: impactSourceMap[table],
      layer: tableInfoMap[table]?.layer || 'UNKNOWN',
      temp: tableInfoMap[table]?.temp || false,
      scriptFile: tableInfoMap[table]?.scriptFile || null
    })
  }
  return result
}
</script>

<style scoped>
.propagation-tree {
  padding: 8px 0;
}
.depth-section {
  margin-bottom: 12px;
}
.depth-arrow {
  color: #1890ff;
  font-size: 20px;
  margin-bottom: 4px;
}
.depth-label {
  font-size: 12px;
  color: #8c8c8c;
  margin-bottom: 6px;
}
.tree-cards {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.impact-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 280px;
  padding: 8px 12px;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  background: #fafafa;
}
.type-badge {
  width: 6px;
  height: 24px;
  border-radius: 3px;
  flex-shrink: 0;
}
.card-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.table-name {
  font-weight: 600;
  font-size: 13px;
  word-break: break-all;
}
.impact-source {
  font-size: 11px;
  color: #8c8c8c;
  margin-top: 2px;
}
.code-btn {
  margin-left: auto;
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  flex-shrink: 0;
}
.code-btn:hover:not(:disabled) {
  border-color: #1890ff;
  color: #1890ff;
}
.code-btn:disabled {
  color: #bfbfbf;
  cursor: not-allowed;
}
</style>
