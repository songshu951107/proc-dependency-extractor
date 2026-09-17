<template>
  <el-dialog :model-value="modelValue" :title="'数据集详情 · ' + (report?.name || '')"
             width="860px" @update:model-value="v => emit('update:modelValue', v)">
    <el-row :gutter="12" style="height: 480px">
      <el-col :span="6" style="border-right: 1px solid #e8e8e8; padding-right: 8px; overflow-y: auto">
        <div
          v-for="(ds, i) in detailList" :key="i"
          style="cursor:pointer; padding: 8px 10px; border-radius: 4px;"
          :style="active === i ? { background: '#f0f2ff', color: '#4B3FE3' } : {}"
          @click="active = i">
          <div style="font-weight:500">{{ ds.datasetName }}</div>
          <div style="font-size:12px; color:#999">{{ ds.connection }}</div>
        </div>
      </el-col>
      <el-col :span="18">
        <div v-if="!current">
          <el-empty description="该报表无可展示数据集" />
        </div>
        <template v-else>
          <h4 style="margin:4px 0 8px">数据集信息</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="数据集名">{{ current.datasetName }}</el-descriptions-item>
            <el-descriptions-item label="连接数据库">{{ current.connection }}</el-descriptions-item>
            <el-descriptions-item label="涉及表数">{{ current.tableCount }}</el-descriptions-item>
          </el-descriptions>

          <h4 style="margin:12px 0 8px">源表</h4>
          <el-tag v-for="(t, i) in current.sourceTables" :key="i" size="small" style="margin:0 6px 6px 0">
            {{ t.fullName }} {{ t.alias ? '(' + t.alias + ')' : '' }}
          </el-tag>

          <h4 style="margin:12px 0 8px">字段映射</h4>
          <el-table :data="current.fieldMappings" border size="small" max-height="120">
            <el-table-column prop="selectAlias" label="别名" />
            <el-table-column label="源表 | 源字段">
              <template #default="{ row }">{{ row.sourceTable }}.{{ row.sourceField }}</template>
            </el-table-column>
            <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
          </el-table>

          <h4 style="margin:12px 0 8px">SQL 详情</h4>
          <div style="display:flex; justify-content:flex-end; margin-bottom:6px">
            <el-button size="small" @click="copySql">复制</el-button>
          </div>
          <pre style="margin:0; padding:12px; background:#f5f7fa; border:1px solid #e8e8e8;
               border-radius:4px; font-size:12px; max-height:180px; overflow:auto; white-space:pre-wrap; word-break:break-all">{{ current.sqlText }}</pre>
        </template>
      </el-col>
    </el-row>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReportDatasets } from '../../api/report'

const props = defineProps({ modelValue: { type: Boolean, default: false }, report: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])

const detailList = ref([])
const active = ref(0)
const current = computed(() => detailList.value && active.value < detailList.value.length
  ? detailList.value[active.value] : null)

watch(() => props.modelValue, async v => {
  if (!v || !props.report) return
  try {
    const res = await getReportDatasets(props.report.file)
    detailList.value = res.data || []
    active.value = 0
  } catch (e) { ElMessage.error('加载数据集详情失败') }
})

async function copySql() {
  if (current.value?.sqlText) {
    try { await navigator.clipboard.writeText(current.value.sqlText); ElMessage.success('已复制') }
    catch { ElMessage.error('复制失败') }
  }
}
</script>