<template>
  <el-card class="impact-filter" shadow="never">
    <el-form :inline="true" :model="form" label-width="80px">
      <el-form-item label="Schema">
        <el-select v-model="form.schema" placeholder="选择 Schema" @change="onSchemaChange" style="width: 120px">
          <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="表名">
        <el-select
          v-model="form.tableShortName"
          placeholder="选择表"
          filterable
          @change="onTableChange"
          style="width: 280px"
        >
          <el-option
            v-for="t in tables"
            :key="t.fullName"
            :label="t.shortName"
            :value="t.shortName"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="字段名">
        <el-select
          v-model="form.fieldName"
          placeholder="可选"
          filterable
          clearable
          style="width: 180px"
        >
          <el-option v-for="f in fields" :key="f" :label="f" :value="f" />
        </el-select>
      </el-form-item>
      <el-form-item label="方向">
        <el-select v-model="form.direction" style="width: 100px">
          <el-option label="下游" value="DOWNSTREAM" />
          <el-option label="上游" value="UPSTREAM" />
          <el-option label="双向" value="BOTH" />
        </el-select>
      </el-form-item>
      <el-form-item label="深度">
        <el-select v-model="form.maxDepth" style="width: 80px">
          <el-option :value="1" label="1" />
          <el-option :value="3" label="3" />
          <el-option :value="5" label="5" />
          <el-option :value="10" label="10" />
          <el-option :value="20" label="全部" />
        </el-select>
      </el-form-item>
      <el-form-item label="分析报表影响">
        <el-switch v-model="form.analyzeReportImpact" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onAnalyze" :loading="loading">分析</el-button>
        <el-button @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getSchemas, getTables, getFields } from '@/api/impact'

const props = defineProps({
  loading: Boolean
})
const emit = defineEmits(['analyze'])

const schemas = ref([])
const tables = ref([])
const fields = ref([])

const form = reactive({
  schema: '',
  tableShortName: '',
  fieldName: '',
  direction: 'DOWNSTREAM',
  maxDepth: 5,
  analyzeReportImpact: false
})

onMounted(async () => {
  try {
    const res = await getSchemas()
    schemas.value = res.data || []
  } catch (e) {
    console.error('加载 schemas 失败', e)
  }
})

const onSchemaChange = async () => {
  form.tableShortName = ''
  form.fieldName = ''
  fields.value = []
  if (!form.schema) return
  try {
    const res = await getTables(form.schema)
    tables.value = res.data || []
  } catch (e) {
    console.error('加载表列表失败', e)
  }
}

const onTableChange = async () => {
  form.fieldName = ''
  if (!form.tableShortName) return
  const table = tables.value.find(t => t.shortName === form.tableShortName)
  if (!table) return
  try {
    const res = await getFields(table.fullName)
    fields.value = res.data || []
  } catch (e) {
    console.error('加载字段列表失败', e)
  }
}

const onAnalyze = () => {
  emit('analyze', { ...form })
}

const onReset = () => {
  form.schema = ''
  form.tableShortName = ''
  form.fieldName = ''
  form.direction = 'DOWNSTREAM'
  form.maxDepth = 5
  form.analyzeReportImpact = false
  tables.value = []
  fields.value = []
}
</script>

<style scoped>
.impact-filter {
  margin-bottom: 10px;
}
</style>
