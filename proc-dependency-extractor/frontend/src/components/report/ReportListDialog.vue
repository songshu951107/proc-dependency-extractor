<template>
  <el-dialog :model-value="modelValue" title="报表清单" width="720px"
             @update:model-value="v => emit('update:modelValue', v)">
    <el-input v-model="kw" placeholder="按报表名搜索" clearable style="margin-bottom: 12px"
              @input="reload()" />
    <el-table :data="rows" v-loading="loading" border size="small" max-height="420">
      <el-table-column label="序号" width="70" align="center">
        <template #default="{ $index }">{{ (page - 1) * size + $index + 1 }}</template>
      </el-table-column>
      <el-table-column label="报表名" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="emit('select-report', row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="文件所在路径" min-width="260" show-overflow-tooltip prop="filePath" />
      <el-table-column label="解析时间" width="170" prop="parseTime" />
    </el-table>
    <el-pagination background layout="total, prev, pager, next"
                   :total="total" :current-page="page" :page-size="size"
                   @current-change="p => { page = p; reload() }" style="margin-top: 12px" />
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getReportList } from '../../api/report'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue', 'select-report'])

const kw = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function reload() {
  loading.value = true
  try {
    const res = await getReportList({ pageNum: page.value, pageSize: size.value, name: kw.value || undefined })
    const d = res.data || {}
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally { loading.value = false }
}

watch(() => props.modelValue, v => { if (v) { page.value = 1; reload() } })
watch(kw, () => { page.value = 1 })
</script>