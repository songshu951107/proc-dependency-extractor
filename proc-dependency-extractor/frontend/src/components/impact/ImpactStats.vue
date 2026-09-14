<template>
  <el-card class="impact-stats" shadow="never" v-if="stats">
    <div class="stats-row">
      <div
        class="stat-item"
        :class="{ clickable: clickable('affectedTableCount') }"
        @click="handleClick('tables', stats.affectedTableCount)"
      >
        <el-statistic :title="upstream ? '来源表数' : '影响表数'" :value="stats.affectedTableCount || 0" />
      </div>
      <div
        v-if="stats.affectedFieldCount !== null && stats.affectedFieldCount !== undefined"
        class="stat-item"
        :class="{ clickable: clickable('affectedFieldCount') }"
        @click="handleClick('fields', stats.affectedFieldCount)"
      >
        <el-statistic :title="upstream ? '来源字段数' : '影响字段数'" :value="stats.affectedFieldCount || 0" />
      </div>
      <div
        class="stat-item"
        :class="{ clickable: clickable('affectedProcCount') }"
        @click="handleClick('procs', stats.affectedProcCount)"
      >
        <el-statistic title="涉及 proc 数" :value="stats.affectedProcCount || 0" />
      </div>
      <div class="stat-item">
        <el-statistic :title="upstream ? '最大来源层级' : '最大层级'" :value="stats.maxDepthReached || 0" />
      </div>
      <div
        v-if="stats.showReports"
        class="stat-item"
        :class="{ clickable: clickable('affectedReportCount') }"
        @click="handleClick('reports', stats.affectedReportCount)"
      >
        <el-statistic title="影响报表数量" :value="stats.affectedReportCount" />
      </div>
    </div>
  </el-card>
</template>

<script setup>
const props = defineProps({
  stats: Object,
  upstream: Boolean
})
const emit = defineEmits(['statClick'])

// 数值有效（非 null 且 > 0）才可点击，值为 0 无明细可看
function clickable(key) {
  const v = props.stats?.[key]
  return typeof v === 'number' && v > 0
}

function handleClick(type, value) {
  if (typeof value !== 'number' || value <= 0) return
  emit('statClick', type)
}
</script>

<style scoped>
.impact-stats {
  margin-bottom: 10px;
}
.stats-row {
  display: flex;
  gap: 20px;
}
.stat-item {
  flex: 1;
  min-width: 0;
}
.stat-item.clickable {
  cursor: pointer;
  border-radius: 4px;
  transition: background-color 0.2s;
}
.stat-item.clickable:hover {
  background-color: var(--el-fill-color-light, #f5f7fa);
}
.stat-item.clickable:hover :deep(.el-statistic__number) {
  color: var(--el-color-primary, #409eff);
}
</style>
