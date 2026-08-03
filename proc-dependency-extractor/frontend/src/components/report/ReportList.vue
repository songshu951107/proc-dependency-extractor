<template>
  <div class="report-list">
    <div v-if="!reports.length" class="empty">暂无报表数据</div>
    <el-card v-for="r in reports" :key="r.file" class="report-card"
             shadow="hover" @click="handleClick(r)">
      <div class="card-header">
        <span class="title">{{ r.name }}</span>
        <el-tag size="small" type="info">{{ r.datasetName }}</el-tag>
      </div>
      <div class="card-body">
        <div v-if="r.connection" class="row"><span class="label">数据源:</span> {{ r.connection }}</div>
        <div v-if="r.selectAlias" class="row"><span class="label">别名:</span> {{ r.selectAlias }}</div>
        <div v-if="r.sourceExpr" class="row"><span class="label">表达式:</span> {{ r.sourceExpr }}</div>
        <div v-if="r.cellRefs && r.cellRefs.length" class="row">
          <span class="label">单元格:</span>
          <el-tag v-for="c in r.cellRefs" :key="c" size="small" style="margin-right: 4px">{{ c }}</el-tag>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
defineProps({ reports: { type: Array, default: () => [] } })
const emit = defineEmits(['select'])

function handleClick(report) {
  emit('select', report)
}
</script>

<style scoped>
.report-list { max-height: 600px; overflow-y: auto; }
.report-card { margin-bottom: 8px; cursor: pointer; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.card-header .title { font-weight: bold; }
.card-body .row { font-size: 12px; color: #666; margin: 2px 0; }
.card-body .label { color: #999; }
.empty { text-align: center; color: #999; padding: 40px; }
</style>
