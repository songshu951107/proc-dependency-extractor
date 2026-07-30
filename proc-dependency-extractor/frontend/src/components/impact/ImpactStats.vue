<template>
  <el-card class="impact-stats" shadow="never" v-if="stats">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-statistic title="影响表数" :value="stats.affectedTableCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="影响字段数" :value="stats.affectedFieldCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="涉及 proc 数" :value="stats.affectedProcCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="最大深度" :value="stats.maxDepthReached || 0" />
      </el-col>
    </el-row>
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
</style>
