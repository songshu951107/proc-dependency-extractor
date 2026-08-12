<template>
  <div class="field-mapping">
    <el-empty v-if="!bindings.length" description="选择报表查看字段映射" />
    <el-table v-else :data="bindings" border size="small" height="600">
      <el-table-column prop="headerText" label="报表表头" width="140" />
      <el-table-column prop="datasetName" label="数据集" width="80" />
      <el-table-column prop="sourceTable" label="源表" width="240" />
      <el-table-column prop="sourceField" label="源字段" width="120" />
      <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
defineProps({ bindings: { type: Array, default: () => [] } })
</script>

<style scoped>
.field-mapping { background: #fff; padding: 10px; border-radius: 4px; }
</style>
