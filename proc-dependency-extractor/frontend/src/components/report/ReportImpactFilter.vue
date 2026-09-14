<template>
  <div class="report-filter">
    <el-form :inline="true" :model="form" @submit.prevent>
      <el-form-item label="查询类型">
        <el-select v-model="form.queryType" style="width: 100px" @change="onQueryTypeChange">
          <el-option label="表级" value="table" />
          <el-option label="字段级" value="field" />
          <el-option label="口径" value="caliber" />
          <el-option label="溯源" value="trace" />
        </el-select>
      </el-form-item>

      <template v-if="form.queryType === 'table' || form.queryType === 'field'">
        <el-form-item label="Schema">
          <el-select v-model="form.schema" placeholder="选择/输入" style="width: 100px"
                     filterable allow-create default-first-option
                     @change="onSchemaChange">
            <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名">
          <el-select v-model="form.table" placeholder="选择/输入" style="width: 220px"
                     filterable allow-create default-first-option
                     :disabled="!form.schema"
                     @change="onTableChange">
            <el-option v-for="t in tables" :key="t.fullName" :label="t.shortName" :value="t.shortName" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.queryType === 'field'" label="字段">
          <el-select v-model="form.field" placeholder="选择/输入字段名" style="width: 140px"
                     filterable allow-create default-first-option
                     :disabled="!form.table"
                     :loading="fieldLoading">
            <el-option v-for="f in fields" :key="f" :label="f" :value="f" />
          </el-select>
        </el-form-item>
      </template>

      <template v-else-if="form.queryType === 'caliber'">
        <el-form-item label="列头汉字">
          <el-input v-model="form.headerText" placeholder="如 客户名称" style="width: 200px" clearable />
        </el-form-item>
      </template>

      <template v-else-if="form.queryType === 'trace'">
        <el-form-item label="报表">
          <el-select v-model="form.reportFile" placeholder="选择报表" style="width: 260px"
                     filterable
                     :loading="reportLoading"
                     @change="onReportChange">
            <el-option v-for="r in reports" :key="r.file" :label="r.name" :value="r.file" />
          </el-select>
        </el-form-item>
        <el-form-item label="表头列">
          <el-select v-model="form.headerText" placeholder="选择表头列" style="width: 200px"
                     filterable
                     :disabled="!form.reportFile"
                     :loading="headerLoading">
            <el-option v-for="h in reportHeaders" :key="h" :label="h" :value="h" />
          </el-select>
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
import { getReportFields, getReportList, getReportHeaders } from '../../api/report'

const props = defineProps({ loading: Boolean })
const emit = defineEmits(['query'])

const form = ref({
  queryType: 'table',
  schema: '',
  table: '',
  field: '',
  headerText: '',
  reportFile: ''
})

const schemas = ref([])
const tables = ref([])
const fields = ref([])
const fieldLoading = ref(false)
// 溯源查询：报表列表 + 表头列列表
const reports = ref([])
const reportHeaders = ref([])
const reportLoading = ref(false)
const headerLoading = ref(false)

onMounted(async () => {
  try {
    const res = await getSchemas()
    schemas.value = res.data || []
  } catch (e) { /* ignore */ }
  // 预加载报表列表，用于溯源查询
  loadReports()
})

// Schema 变更：清空表名、字段，并重新加载表清单
function onSchemaChange() {
  form.value.table = ''
  form.value.field = ''
  tables.value = []
  fields.value = []
  if (form.value.schema) {
    loadTables()
  }
}

// 表名变更：清空字段，并加载字段清单
function onTableChange() {
  form.value.field = ''
  fields.value = []
  if (form.value.table) {
    loadFields()
  }
}

// 报表变更：清空表头列，并加载表头列清单
function onReportChange() {
  form.value.headerText = ''
  reportHeaders.value = []
  if (form.value.reportFile) {
    loadReportHeaders()
  }
}

// 切换查询类型时清空已选字段（避免跨场景残留）
function onQueryTypeChange() {
  form.value.field = ''
  form.value.headerText = ''
}

async function loadTables() {
  if (!form.value.schema) return
  try {
    const res = await getTables(form.value.schema)
    tables.value = res.data || []
  } catch (e) { /* ignore */ }
}

async function loadFields() {
  if (!form.value.schema || !form.value.table) return
  fieldLoading.value = true
  try {
    const res = await getReportFields(form.value.schema, form.value.table)
    fields.value = res.data || []
  } catch (e) {
    fields.value = []
  } finally {
    fieldLoading.value = false
  }
}

async function loadReports() {
  reportLoading.value = true
  try {
    // 分页接口默认只返回第 1 页 10 条，溯源下拉需要全量列表：传大 pageSize 一次取全
    const res = await getReportList({ pageNum: 1, pageSize: 100000 })
    // getReportList 返回分页对象 {records, total, size, current, pages}
    reports.value = res.data?.records || res.data || []
  } catch (e) {
    reports.value = []
  } finally {
    reportLoading.value = false
  }
}

async function loadReportHeaders() {
  if (!form.value.reportFile) return
  headerLoading.value = true
  try {
    const res = await getReportHeaders(form.value.reportFile)
    reportHeaders.value = res.data || []
  } catch (e) {
    reportHeaders.value = []
  } finally {
    headerLoading.value = false
  }
}

function handleQuery() {
  emit('query', { ...form.value })
}
</script>

<style scoped>
.report-filter { padding: 10px; background: #fff; border-radius: 4px; margin-bottom: 10px; }
</style>
