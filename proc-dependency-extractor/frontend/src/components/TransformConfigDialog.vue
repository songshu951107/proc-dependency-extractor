<template>
  <el-dialog v-model="visible" title="映射配置管理" width="960px" top="4vh"
             :close-on-click-modal="false" @opened="fetchConfig">
    <!-- 表对列表 -->
    <div class="section-head">
      <span class="section-title">现有表对（{{ tableRows.length }}）</span>
      <el-button size="small" type="primary" @click="openAdd">新增表对</el-button>
    </div>
    <el-table :data="tableRows" border size="small" row-key="oldTable"
              :expand-row-keys="expandedKeys" max-height="260">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="mapping-expand">
            <div v-if="Object.keys(row.columnMapping).length === 0" class="muted">
              无列映射（仅表名替换）
            </div>
            <div v-for="(v, k) in row.columnMapping" :key="k" class="mapping-line">
              <code>{{ k }}</code> → <code>{{ v }}</code>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="旧表（F 层）" min-width="220">
        <template #default="{ row }">{{ row.oldDb ? row.oldDb + '.' : '' }}{{ row.oldTable }}</template>
      </el-table-column>
      <el-table-column label="新表（O 层）" min-width="220">
        <template #default="{ row }">{{ row.newDb ? row.newDb + '.' : '' }}{{ row.newTable }}</template>
      </el-table-column>
      <el-table-column label="列映射数" width="90">
        <template #default="{ row }">{{ Object.keys(row.columnMapping).length }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑表对 -->
    <el-divider />
    <div class="section-head">
      <span class="section-title">{{ editMode === 'add' ? '新增表对' : (editMode === 'edit' ? '编辑表对' : '表对表单') }}</span>
    </div>
    <el-form inline size="small">
      <el-form-item label="旧表(F)"><el-input v-model="form.oldTable" style="width:200px"
        placeholder="F_PT_XXX" :disabled="editMode === 'edit'" /></el-form-item>
      <el-form-item label="新表(O)"><el-input v-model="form.newTable" style="width:200px"
        placeholder="O_XXX（可空）" /></el-form-item>
      <el-form-item label="旧库"><el-input v-model="form.oldDb" style="width:90px" placeholder="FDM" /></el-form-item>
      <el-form-item label="新库"><el-input v-model="form.newDb" style="width:90px" placeholder="ODM" /></el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="doSave">
          {{ editMode === 'edit' ? '保存修改' : '添加' }}
        </el-button>
        <el-button v-if="editMode === 'edit'" @click="resetForm">取消</el-button>
      </el-form-item>
    </el-form>
    <div class="muted">列映射可直接在「批量导入」中由脚本自动推导，也可后续人工补充。</div>

    <!-- 批量导入 -->
    <el-divider />
    <div class="section-head">
      <span class="section-title">批量导入（按 T 层源列自动推导列映射）</span>
    </div>
    <el-form inline size="small">
      <el-form-item label="旧表(F)"><el-input v-model="imp.oldTable" style="width:180px" placeholder="必填" /></el-form-item>
      <el-form-item label="新表(O)"><el-input v-model="imp.newTable" style="width:180px" placeholder="必填" /></el-form-item>
      <el-form-item label="旧库"><el-input v-model="imp.oldDb" style="width:80px" /></el-form-item>
      <el-form-item label="新库"><el-input v-model="imp.newDb" style="width:80px" /></el-form-item>
    </el-form>
    <el-upload :auto-upload="false" multiple :file-list="imp.fileList"
               :on-change="onPick" :on-remove="onRemove" accept=".sql,.txt,.py"
               drag style="width:100%">
      <div style="padding:8px 0">
        <el-button size="small" type="primary">选择文件（可多选）</el-button>
        <div class="muted" style="margin-top:6px">
          一次拖入 4 类文件即可：O/F 表建表语句（或表结构）、O/F 表从 T 层加工的脚本，后端自动识别
        </div>
      </div>
    </el-upload>
    <div style="margin-top:8px">
      <el-button type="primary" size="small" :loading="parsing"
                 :disabled="!imp.fileList.length" @click="doPreview">解析预览</el-button>
    </div>

    <!-- 预览结果 -->
    <template v-if="preview">
      <el-alert v-if="preview.matched_count === 0" type="error" :closable="false" style="margin-top:10px"
                title="未推导出任何列映射，请检查脚本中的 INSERT INTO 目标表与 SELECT 列" />
      <el-alert v-else type="success" :closable="false" style="margin-top:10px"
                :title="`推导出 ${preview.matched_count} 条列映射；请人工确认后导入`" />
      <div v-if="preview.recognized && preview.recognized.length" class="muted" style="margin-top:6px">
        文件识别：<div v-for="r in preview.recognized" :key="r">· {{ r }}</div>
      </div>
      <el-table :data="previewRows" border size="small" max-height="220" style="margin-top:10px">
        <el-table-column prop="f" label="F 表列" min-width="160" />
        <el-table-column prop="o" label="O 表列" min-width="160" />
      </el-table>
      <template v-if="preview.unmatched_f.length || preview.unmatched_o.length">
        <div class="section-title" style="margin-top:8px">未匹配清单（人工处理，不阻断导入）</div>
        <el-table :data="unmatchedRows" border size="small" max-height="180">
          <el-table-column prop="side" label="侧" width="50" />
          <el-table-column prop="target_col" label="目标列" min-width="140" />
          <el-table-column prop="reason" label="原因" min-width="240" />
          <el-table-column prop="expr" label="原始片段" min-width="200" show-overflow-tooltip />
        </el-table>
      </template>
      <div style="margin-top:10px">
        <el-button type="success" :loading="applying" :disabled="preview.matched_count === 0"
                   @click="doApply">确认导入该表对</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getConfig, saveConfigEntry, deleteConfigEntry,
         importPreview, applyConfigEntries } from '@/api/transformConfig'

const emit = defineEmits(['changed'])

const visible = ref(false)
const tableRows = ref([])
const expandedKeys = ref([])

// 新增/编辑表单
const editMode = ref('hidden')   // hidden | add | edit
const saving = ref(false)
const form = ref({ oldTable: '', newTable: '', oldDb: '', newDb: '' })

// 批量导入（单入口多文件，后端自动识别）
const imp = ref({ oldTable: '', newTable: '', oldDb: '', newDb: '', fileList: [] })
const parsing = ref(false)
const applying = ref(false)
const preview = ref(null)

const previewRows = computed(() =>
  Object.entries(preview.value?.column_mapping || {}).map(([f, o]) => ({ f, o })))
const unmatchedRows = computed(() => [
  ...(preview.value?.unmatched_f || []).map((u) => ({ ...u, side: 'F' })),
  ...(preview.value?.unmatched_o || []).map((u) => ({ ...u, side: 'O' })),
])

function open() {
  visible.value = true
}
defineExpose({ open })

async function fetchConfig() {
  const { data } = await getConfig()
  // 后端直接返回表对映射：{ F表名: {new_table, old_db, ...} }
  const tables = data || {}
  tableRows.value = Object.entries(tables).map(([oldTable, conf]) => ({
    oldTable,
    newTable: conf.new_table || '',
    oldDb: conf.old_db || '',
    newDb: conf.new_db || '',
    columnMapping: conf.column_mapping || {},
  }))
}

function resetForm() {
  form.value = { oldTable: '', newTable: '', oldDb: '', newDb: '' }
  editMode.value = 'hidden'
}
function openAdd() {
  resetForm()
  editMode.value = 'add'
}
function openEdit(row) {
  form.value = { oldTable: row.oldTable, newTable: row.newTable,
                 oldDb: row.oldDb, newDb: row.newDb }
  editMode.value = 'edit'
}

async function doSave() {
  if (!form.value.oldTable || !form.value.newTable) {
    ElMessage.warning('旧表与新表必填')
    return
  }
  saving.value = true
  try {
    const payload = { old_table: form.value.oldTable, new_table: form.value.newTable,
                      old_db: form.value.oldDb, new_db: form.value.newDb }
    if (editMode.value === 'edit') {
      // 编辑仅改表名/库名；已存在的列映射等在保存后端按 upsert 合并
      const cur = tableRows.value.find((r) => r.oldTable === form.value.oldTable)
      if (cur && Object.keys(cur.columnMapping).length) {
        payload.column_mapping = cur.columnMapping
      }
    }
    const { data } = await saveConfigEntry(payload)
    ElMessage.success(data)
    resetForm()
    fetchConfig()
  } finally {
    saving.value = false
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除表对 ${row.oldTable} → ${row.newTable} 吗？删除后转换引擎不再替换该表。`,
      '删除确认', { type: 'warning' })
  } catch { return }
  const { data } = await deleteConfigEntry(row.oldTable)
  ElMessage.success(data)
  fetchConfig()
}

function onPick(file, fileList) {
  imp.value.fileList = fileList
}
function onRemove(file, fileList) {
  imp.value.fileList = fileList
}

async function doPreview() {
  if (!imp.value.oldTable || !imp.value.newTable) {
    ElMessage.warning('请先填写旧表/新表名')
    return
  }
  parsing.value = true
  preview.value = null
  try {
    const { data } = await importPreview({
      files: imp.value.fileList.map((f) => f.raw),
      oldTable: imp.value.oldTable, newTable: imp.value.newTable,
      oldDb: imp.value.oldDb, newDb: imp.value.newDb,
    })
    preview.value = data
  } finally {
    parsing.value = false
  }
}

async function doApply() {
  if (!preview.value) return
  applying.value = true
  try {
    const entry = {
      old_table: preview.value.old_table,
      new_table: preview.value.new_table,
      column_mapping: preview.value.column_mapping,
    }
    if (preview.value.old_db) entry.old_db = preview.value.old_db
    if (preview.value.new_db) entry.new_db = preview.value.new_db
    const { data } = await applyConfigEntries([entry])
    ElMessage.success(data)
    preview.value = null
    fetchConfig()
    emit('changed')
  } finally {
    applying.value = false
  }
}
</script>

<style scoped>
.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.section-title { font-weight: 600; }
.mapping-expand { padding: 4px 16px; max-height: 200px; overflow: auto; }
.mapping-line { line-height: 1.8; }
.muted { color: #999; font-size: 12px; margin: 4px 0; }
.el-upload { text-align: left; margin: 3px 0; }
</style>
