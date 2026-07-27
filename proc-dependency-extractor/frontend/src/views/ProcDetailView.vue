<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <div>
            <el-button link @click="goBack">
              <el-icon><ArrowLeft /></el-icon>&nbsp;返回
            </el-button>
            <span style="font-size: 16px; font-weight: bold; margin-left: 10px;">
              {{ procName }} - 依赖表详情
            </span>
          </div>
          <div>
            <el-tag type="success" style="margin-right: 8px;">依赖表: {{ depCount }}</el-tag>
            <el-tag type="warning">临时表: {{ tempCount }}</el-tag>
          </div>
        </div>
      </template>

      <!-- 过滤 -->
      <el-form :inline="true" style="margin-bottom: 10px;">
        <el-form-item label="表名">
          <el-input
            v-model="filterText"
            placeholder="输入表名或schema过滤"
            clearable
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="filterType">
            <el-radio-button label="all">全部</el-radio-button>
            <el-radio-button label="dep">仅依赖表</el-radio-button>
            <el-radio-button label="temp">仅临时表</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table
        :data="filteredData"
        v-loading="loading"
        border
        stripe
        style="width: 100%"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="schemaName" label="Schema" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.schemaName" size="small">{{ row.schemaName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="schemaVarSrc" label="Schema来源" width="180" show-overflow-tooltip />
        <el-table-column prop="tableName" label="表名" min-width="240" show-overflow-tooltip />
        <el-table-column prop="fullTableName" label="完整表名" min-width="280" show-overflow-tooltip />
        <el-table-column prop="operationType" label="操作类型" width="160" align="center">
          <template #default="{ row }">
            <el-tag :type="opTagType(row.operationType)" size="small">{{ row.operationType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="isTempTable" label="临时表" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isTempTable === 1" type="warning" size="small">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="extractTime" label="提取时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { listDependencies } from '../api/proc'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const tableData = ref([])
const filterText = ref('')
const filterType = ref('all')

const procName = computed(() => route.params.procName)

const depCount = computed(() => tableData.value.filter(r => r.isTempTable === 0).length)
const tempCount = computed(() => tableData.value.filter(r => r.isTempTable === 1).length)

const filteredData = computed(() => {
  let list = tableData.value
  if (filterType.value === 'dep') {
    list = list.filter(r => r.isTempTable === 0)
  } else if (filterType.value === 'temp') {
    list = list.filter(r => r.isTempTable === 1)
  }
  if (filterText.value) {
    const kw = filterText.value.toUpperCase()
    list = list.filter(r =>
      (r.tableName && r.tableName.toUpperCase().includes(kw)) ||
      (r.schemaName && r.schemaName.toUpperCase().includes(kw)) ||
      (r.fullTableName && r.fullTableName.toUpperCase().includes(kw))
    )
  }
  return list
})

function opTagType(op) {
  if (!op) return 'info'
  const u = op.toUpperCase()
  if (u.includes('INSERT')) return 'success'
  if (u.includes('DELETE')) return 'danger'
  if (u.includes('FROM')) return 'primary'
  if (u.includes('JOIN')) return ''
  if (u.includes('TEMPORARY')) return 'warning'
  return 'info'
}

async function fetchData() {
  loading.value = true
  try {
    const res = await listDependencies(procName.value)
    tableData.value = res.data || []
  } catch (e) {
    // 拦截器已处理
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push('/procs')
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
</style>
