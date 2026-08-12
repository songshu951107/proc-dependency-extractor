<template>
  <el-card class="impact-stats" shadow="never" v-if="stats">
    <div class="stats-row">
      <div class="stat-item">
        <el-statistic title="影响表数" :value="stats.affectedTableCount || 0" />
      </div>
      <div class="stat-item">
        <el-statistic title="影响字段数" :value="stats.affectedFieldCount || 0" />
      </div>
      <div class="stat-item">
        <el-statistic title="涉及 proc 数" :value="stats.affectedProcCount || 0" />
      </div>
      <div class="stat-item">
        <el-statistic title="最大深度" :value="stats.maxDepthReached || 0" />
      </div>
      <div class="stat-item" v-if="stats.affectedReportCount != null">
        <el-statistic title="影响报表数量" :value="stats.affectedReportCount" />
      </div>
    </div>
  </el-card>
</template>

<script setup>
defineProps({
  stats: Object
})
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
</style>
