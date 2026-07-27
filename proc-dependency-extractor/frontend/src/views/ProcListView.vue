<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-size: 16px; font-weight: bold;">存储过程列表</span>
          <el-button type="primary" @click="goUpload">
            <el-icon><Upload /></el-icon>&nbsp;导入脚本
          </el-button>
        </div>
      </template>

      <!-- 搜索 -->
      <el-form :inline="true" style="margin-bottom: 10px;">
        <el-form-item label="存储过程名">
          <el-input
            v-model="query.procName"
            placeholder="支持模糊查询"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>&nbsp;查询
          </el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        border
        stripe
        style="width: 100%"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="procName" label="存储过程名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="procDesc" label="功能说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="updateTarget" label="更新对象" min-width="200" show-overflow-tooltip />
        <el-table-column prop="tableCount" label="依赖表数" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="success">{{ row.tableCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tempTableCount" label="临时表数" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="warning">{{ row.tempTableCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="extractTime" label="提取时间" width="170" />
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="viewDetail(row)">
              <el-icon><View /></el-icon>&nbsp;依赖详情
            </el-button>
            <el-button size="small" type="danger" link @click="handleDelete(row)">
              <el-icon><Delete /></el-icon>&nbsp;删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="margin-top: 15px; text-align: right;">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Search, View, Delete } from '@element-plus/icons-vue'
import { listProcs, deleteProc } from '../api/proc'

const router = useRouter()
const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  procName: ''
})

async function fetchData() {
  loading.value = true
  try {
    const res = await listProcs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      procName: query.procName
    })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    // 拦截器已处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchData()
}

function handleReset() {
  query.procName = ''
  query.pageNum = 1
  fetchData()
}

function viewDetail(row) {
  router.push(`/procs/${row.procName}`)
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除存储过程【${row.procName}】及其依赖关系吗?`,
      '删除确认',
      { type: 'warning' }
    )
    await deleteProc(row.procName)
    ElMessage.success('删除成功')
    fetchData()
  } catch (e) {
    // 取消或失败
  }
}

function goUpload() {
  router.push('/')
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
</style>
