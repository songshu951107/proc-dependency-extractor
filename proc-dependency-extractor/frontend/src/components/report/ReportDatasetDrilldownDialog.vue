<template>
  <el-dialog :model-value="modelValue" :title="'数据集详情 · ' + (report?.name || '')"
             width="860px" @update:model-value="v => emit('update:modelValue', v)">
    <div style="height: 460px; display: flex">
      <!-- 左列：一级数据集列表 + 缩进的二级 tab -->
      <div style="width: 200px; flex-shrink: 0; border-right: 1px solid #e8e8e8;
                  display: flex; flex-direction: column">
        <div style="font-weight: bold; padding: 6px 4px 4px; font-size: 13px">数据集列表</div>
        <div style="flex: 1; overflow-y: auto">
          <div
            v-for="(ds, i) in detailList" :key="i"
            style="cursor:pointer; padding: 6px 10px; border-radius: 4px;"
            :style="activeDataset === i ? { background: '#f0f2ff', color: '#4B3FE3' } : {}"
            @click="activeDataset = i">
            <div style="font-weight: 500">{{ ds.datasetName }}</div>
            <div style="font-size: 12px; color: #999">{{ ds.connection }}</div>
          </div>
        </div>

        <!-- 二级 tab（相对一级缩进展示） -->
        <div style="flex-shrink: 0; padding: 6px 0; border-top: 1px solid #f0f0f0">
          <div
            v-for="sec in sections" :key="sec.name"
            style="cursor:pointer; padding: 6px 24px; border-radius: 4px; font-size: 13px; white-space: nowrap;"
            :style="current && sectionTab === sec.name
              ? { background: '#f0f2ff', color: '#4B3FE3', fontWeight: 500 }
              : { color: '#666' }"
            @click="sectionTab = sec.name">
            <span style="color: #bbb; margin-right: 6px">└</span>{{ sec.label }}
          </div>
        </div>
      </div>

      <!-- 右列：渲染当前选中的 tab 内容 -->
      <div style="flex: 1; padding-left: 12px; overflow-y: auto">
        <div v-if="!current">
          <el-empty description="该报表无可展示数据集" />
        </div>
        <template v-else>
          <!-- 数据集信息 -->
          <template v-if="sectionTab === 'info'">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="数据集名">{{ current.datasetName }}</el-descriptions-item>
              <el-descriptions-item label="连接数据库">{{ current.connection }}</el-descriptions-item>
              <el-descriptions-item label="涉及表数">{{ current.tableCount }}</el-descriptions-item>
            </el-descriptions>
          </template>

          <!-- 源表 -->
          <template v-else-if="sectionTab === 'tables'">
            <template v-if="current.sourceTables.length">
              <el-tag v-for="(t, i) in current.sourceTables" :key="i" size="small"
                      style="margin: 0 6px 6px 0">
                {{ t.fullName }} {{ t.alias ? '(' + t.alias + ')' : '' }}
              </el-tag>
            </template>
            <el-empty v-else description="无源表" />
          </template>

          <!-- 字段映射 -->
          <template v-else-if="sectionTab === 'fields'">
            <el-table :data="current.fieldMappings" border size="small" max-height="380">
              <el-table-column prop="reportHeader" label="报表表头" show-overflow-tooltip />
              <el-table-column prop="selectAlias" label="别名" />
              <el-table-column label="源表 | 源字段">
                <template #default="{ row }">{{ row.sourceTable }}.{{ row.sourceField }}</template>
              </el-table-column>
              <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
            </el-table>
          </template>

          <!-- SQL 详情 -->
          <template v-else-if="sectionTab === 'sql'">
            <div style="display: flex; justify-content: flex-end; margin-bottom: 6px">
              <el-button size="small" @click="copySql">复制</el-button>
            </div>
            <pre style="margin: 0; padding: 12px; background: #f5f7fa; border: 1px solid #e8e8e8;
                 border-radius: 4px; font-size: 12px; max-height: 380px; overflow: auto;
                 white-space: pre-wrap; word-break: break-all">{{ current.sqlText }}</pre>
          </template>
        </template>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReportDatasets } from '../../api/report'

const props = defineProps({ modelValue: { type: Boolean, default: false }, report: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])

const sections = [
  { name: 'info', label: '数据集信息' },
  { name: 'tables', label: '源表' },
  { name: 'fields', label: '字段映射' },
  { name: 'sql', label: 'SQL 详情' }
]

const detailList = ref([])
const activeDataset = ref(0)
const sectionTab = ref('info')
const current = computed(() => detailList.value && activeDataset.value < detailList.value.length
  ? detailList.value[activeDataset.value] : null)

watch(() => props.modelValue, async v => {
  if (!v || !props.report) return
  try {
    const res = await getReportDatasets(props.report.file)
    detailList.value = res.data || []
    activeDataset.value = 0
    sectionTab.value = 'info'
  } catch (e) { ElMessage.error('加载数据集详情失败') }
})

async function copySql() {
  if (current.value?.sqlText) {
    try { await navigator.clipboard.writeText(current.value.sqlText); ElMessage.success('已复制') }
    catch { ElMessage.error('复制失败') }
  }
}
</script>