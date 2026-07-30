<template>
  <div class="propagation-tree">
    <!-- 源表卡片 -->
    <div class="depth-section">
      <div class="depth-label">源表</div>
      <div class="tree-cards">
        <div class="impact-card">
          <div class="type-badge" :style="{ background: NODE_COLORS.SOURCE }"></div>
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
            :key="item.table"
            class="impact-card"
          >
            <div
              class="type-badge"
              :style="{ background: NODE_COLORS[item.type] || '#d9d9d9' }"
            ></div>
            <div class="card-content">
              <span class="table-name">{{ item.table }}</span>
              <span v-if="item.impactSource" class="impact-source">
                ← 来自 {{ item.impactSource }}
              </span>
            </div>
            <button
              class="code-btn"
              @click="$emit('viewScript', item)"
              :disabled="!item.procName"
              :title="item.procName ? '查看代码片段' : '无关联存储过程'"
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
  impactedByDepth: { type: Object, default: () => ({}) },
  collapsedTempTables: { type: Boolean, default: false }
})

defineEmits(['viewScript'])

const NODE_COLORS = {
  SOURCE: '#52c41a',
  TEMP: '#faad14',
  TARGET: '#f5222d',
  FIELD: '#1890ff'
}

// 实际展示的数据：折叠临时表时重算层级
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

/**
 * 折叠临时表后重算层级和 impactSource
 * 算法：
 * 1. 基于原始 impactSource 构建邻接表
 * 2. 识别非 TEMP 节点集合
 * 3. 从 sourceTable BFS，跳过 TEMP 节点，找最近的非 TEMP 祖先
 */
function rebuildWithoutTemp(impactedByDepth, sourceTable) {
  // 1. 构建原始邻接表
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push(t.table)
      }
    }
  }

  // 2. 构建表信息查找表 + 识别非 TEMP 节点
  const tableInfoMap = {}
  const nonTempTables = new Set([sourceTable])
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.table] = { type: t.type, procName: t.procName }
      if (t.type !== 'TEMP') nonTempTables.add(t.table)
    }
  }

  // 3. BFS：跳过 TEMP，连接最近的非 TEMP 祖先
  const depthMap = { [sourceTable]: 0 }
  const impactSourceMap = {}
  const queue = [sourceTable]

  while (queue.length) {
    const current = queue.shift()
    const currentDepth = depthMap[current]
    const neighbors = adjacency[current] || []

    for (const neighbor of neighbors) {
      // 直接是非 TEMP 节点：正常连接
      if (nonTempTables.has(neighbor) && !(neighbor in depthMap)) {
        depthMap[neighbor] = currentDepth + 1
        impactSourceMap[neighbor] = current
        queue.push(neighbor)
      }
      // 是 TEMP 节点：递归找 TEMP 的下游非 TEMP 节点
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

  // 4. 重新构建 impactedByDepth
  const result = {}
  for (const [table, depth] of Object.entries(depthMap)) {
    if (depth === 0) continue
    if (!result[depth]) result[depth] = []
    result[depth].push({
      table,
      depth,
      impactSource: impactSourceMap[table],
      type: tableInfoMap[table]?.type || 'UNKNOWN',
      procName: tableInfoMap[table]?.procName || null
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
