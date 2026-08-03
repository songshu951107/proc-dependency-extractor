<template>
  <div class="report-impact-view">
    <ReportImpactFilter :loading="loading" @query="handleQuery" />

    <el-row :gutter="10">
      <el-col :span="8">
        <div class="section-title">报表清单</div>
        <ReportList :reports="reports" @select="handleSelectReport" />
      </el-col>
      <el-col :span="16">
        <div class="section-title">字段映射详情</div>
        <FieldMappingTable :bindings="currentBindings" />
      </el-col>
    </el-row>

    <div v-if="fieldSources.length" class="field-source-section">
      <div class="section-title">字段来源链路（口径查询）</div>
      <el-table :data="fieldSources" border size="small">
        <el-table-column prop="sourceTable" label="源表" />
        <el-table-column prop="sourceField" label="源字段" />
        <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
        <el-table-column prop="connection" label="数据库" />
        <el-table-column prop="datasetName" label="数据集" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import ReportImpactFilter from '../components/report/ReportImpactFilter.vue'
import ReportList from '../components/report/ReportList.vue'
import FieldMappingTable from '../components/report/FieldMappingTable.vue'
import {
  findReportsByTable, findReportsByField, traceFieldSource, analyzeCrossImpact,
  getReportDetail
} from '../api/report'

const loading = ref(false)
const reports = ref([])
const currentBindings = ref([])
const fieldSources = ref([])

async function handleQuery(form) {
  loading.value = true
  reports.value = []
  currentBindings.value = []
  fieldSources.value = []
  try {
    let res
    if (form.queryType === 'table') {
      res = await findReportsByTable(form.schema, form.table)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'field') {
      res = await findReportsByField(form.schema, form.table, form.field)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'trace') {
      res = await traceFieldSource(form.reportFile, form.datasetName, form.columnName)
      fieldSources.value = res.data?.fieldSources || []
    } else if (form.queryType === 'cross') {
      res = await analyzeCrossImpact(form.schema, form.table, form.field)
      reports.value = res.data?.reports || []
    }
  } catch (e) {
    ElMessage.error('查询失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

async function handleSelectReport(report) {
  try {
    const res = await getReportDetail(report.file)
    currentBindings.value = res.data?.bindings || []
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}
</script>

<style scoped>
.report-impact-view { padding: 10px; }
.section-title { font-weight: bold; margin-bottom: 8px; padding: 4px 8px; background: #f5f7fa; }
.field-source-section { margin-top: 15px; }
</style>
