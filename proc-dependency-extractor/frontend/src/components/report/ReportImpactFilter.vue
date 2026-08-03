<template>
  <div class="report-filter">
    <el-form :inline="true" :model="form" @submit.prevent>
      <el-form-item label="查询类型">
        <el-select v-model="form.queryType" style="width: 120px">
          <el-option label="表级" value="table" />
          <el-option label="字段级" value="field" />
          <el-option label="口径" value="trace" />
          <el-option label="跨链" value="cross" />
        </el-select>
      </el-form-item>

      <template v-if="form.queryType !== 'trace'">
        <el-form-item label="Schema">
          <el-select v-model="form.schema" placeholder="选择" style="width: 120px"
                     @change="loadTables">
            <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名">
          <el-select v-model="form.table" filterable placeholder="选择" style="width: 280px">
            <el-option v-for="t in tables" :key="t.fullName" :label="t.shortName" :value="t.shortName" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.queryType === 'field' || form.queryType === 'cross'" label="字段">
          <el-input v-model="form.field" placeholder="字段名" style="width: 160px" />
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="报表文件">
          <el-input v-model="form.reportFile" placeholder="如 公司类存款明细表.cpt" style="width: 240px" />
        </el-form-item>
        <el-form-item label="数据集">
          <el-input v-model="form.datasetName" placeholder="ds1" style="width: 100px" />
        </el-form-item>
        <el-form-item label="列名">
          <el-input v-model="form.columnName" placeholder="STAT_DT" style="width: 160px" />
        </el-form-item>
      </template>

      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getSchemas, getTables } from '../../api/impact'

const props = defineProps({ loading: Boolean })
const emit = defineEmits(['query'])

const form = ref({
  queryType: 'table',
  schema: '',
  table: '',
  field: '',
  reportFile: '',
  datasetName: '',
  columnName: ''
})

const schemas = ref([])
const tables = ref([])

onMounted(async () => {
  try {
    const res = await getSchemas()
    schemas.value = res.data || []
  } catch (e) { /* ignore */ }
})

async function loadTables() {
  if (!form.value.schema) return
  try {
    const res = await getTables(form.value.schema)
    tables.value = res.data || []
  } catch (e) { /* ignore */ }
}

function handleQuery() {
  emit('query', { ...form.value })
}
</script>

<style scoped>
.report-filter { padding: 10px; background: #fff; border-radius: 4px; margin-bottom: 10px; }
</style>
