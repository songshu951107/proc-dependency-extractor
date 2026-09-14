<template>
  <div class="nl2sql-page">
    <h2>智能查数</h2>
    <div class="input-row">
      <el-input
        v-model="question"
        type="textarea"
        :rows="2"
        placeholder="用中文描述你想查的数据，如：查一下账户年利息前100条"
        @keydown.ctrl.enter="doGenerate"
      />
      <el-button type="primary" :loading="loading" @click="doGenerate" style="margin-left: 12px">
        生成 SQL
      </el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon style="margin-top: 12px" />

    <div v-if="result" class="result">
      <h3>生成结果</h3>
      <el-tag :type="statusType" style="margin-bottom: 8px">{{ statusText }}</el-tag>
      <el-alert
        v-if="result.errorMessage"
        type="warning" :closable="false" :title="result.errorMessage" show-icon
        style="margin-bottom: 8px"
      />
      <el-alert type="warning" :closable="false" title="SQL 未经执行验证，请在数据库客户端人工确认后执行" style="margin-bottom: 8px" />
      <pre v-if="result.sql" class="sql-block">{{ result.sql }}</pre>

      <template v-if="result.assumptions && result.assumptions.length">
        <h4>AI 假设</h4>
        <ul><li v-for="(a, i) in result.assumptions" :key="i">{{ a }}</li></ul>
      </template>

      <template v-if="result.lineageEvidence && result.lineageEvidence.length">
        <h4>血缘依据</h4>
        <ul>
          <li v-for="(h, i) in result.lineageEvidence" :key="i">
            {{ h.tableA }} 与 {{ h.tableB }}：
            <template v-if="h.relation === 'COMMON_COLUMN'">共同字段 {{ h.joinFields.join(', ') }}（可作 JOIN 条件）</template>
            <template v-else>曾同现于存储过程 {{ h.procName }}</template>
          </li>
        </ul>
      </template>

      <template v-if="result.candidates && result.candidates.length">
        <h4>召回候选表（{{ result.candidates.length }}）</h4>
        <el-table :data="result.candidates" size="small" max-height="240">
          <el-table-column prop="fullTableName" label="表名" min-width="220" />
          <el-table-column prop="source" label="召回来源" width="120" />
          <el-table-column prop="score" label="得分" width="80" />
        </el-table>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { generate } from '../api/nl2sql'
import { ElMessage } from 'element-plus'

const question = ref('')
const loading = ref(false)
const result = ref(null)
const error = ref('')

const statusType = computed(() => {
  if (!result.value) return 'info'
  if (result.value.validationStatus === 'VALIDATED') return 'success'
  if (result.value.validationStatus === 'FAILED') return 'warning'
  return 'danger'
})
const statusText = computed(() => {
  const map = { VALIDATED: '语法校验通过', FAILED: '语法校验未通过（已重试）', ERROR: '生成失败' }
  return map[result.value?.validationStatus] || '未知'
})

async function doGenerate() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const res = await generate({ question: question.value.trim() })
    result.value = res.data
  } catch (e) {
    error.value = '调用失败：' + (e.message || e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.nl2sql-page { padding: 16px; max-width: 1100px; margin: 0 auto; }
.input-row { display: flex; align-items: flex-start; }
.sql-block {
  background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px;
  overflow-x: auto; white-space: pre-wrap; font-size: 13px;
}
h3 { margin: 12px 0 8px; } h4 { margin: 12px 0 4px; color: #606266; }
</style>