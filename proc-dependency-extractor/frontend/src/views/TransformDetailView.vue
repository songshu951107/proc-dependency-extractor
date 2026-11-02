<template>
  <div class="page">
    <!-- 概要条 -->
    <el-alert v-if="convertedMissing" type="error" show-icon
              :closable="false" style="margin-bottom:12px"
              :title="report?.error || '转换产物缺失，仅展示转换前脚本与报告'" />
    <el-alert v-if="report?.residues?.length" type="warning" show-icon
              :closable="false" style="margin-bottom:12px"
              :title="`存在 ${report.residues.length} 处残留，请人工排查（黄色高亮行）`" />
    <el-descriptions v-if="report" :column="4" size="small" border style="margin-bottom:12px">
      <el-descriptions-item label="映射">{{ mapText }}</el-descriptions-item>
      <el-descriptions-item label="转换点">{{ report.summary.total }}</el-descriptions-item>
      <el-descriptions-item label="残留">{{ report.summary.residues }}</el-descriptions-item>
      <el-descriptions-item label="py_compile">{{ report.py_compile }}</el-descriptions-item>
    </el-descriptions>

    <div class="layout">
      <!-- 左：转换点列表 -->
      <div class="edits-pane">
        <el-radio-group v-model="filterType" size="small" style="margin-bottom:8px">
          <el-radio-button value="">全部({{ edits.length }})</el-radio-button>
          <el-radio-button v-for="t in typeNames" :key="t.key" :value="t.key">
            {{ t.label }}({{ t.count }})
          </el-radio-button>
        </el-radio-group>
        <div v-for="(e, i) in shownEdits" :key="i" class="edit-item"
             :class="{ active: activeEdit === e }" @click="locate(e)">
          <el-tag size="small" :type="typeTag(e.type)" :effect="typeEffect(e.type)">{{ typeLabel(e.type) }}</el-tag>
          <span class="ln">L{{ e.line }}</span>
          <span class="diff-text">{{ e.old }} → {{ e.new }}</span>
        </div>
      </div>
      <!-- 右：全文视图 -->
      <div class="code-pane">
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="转换前" name="source" />
          <el-tab-pane label="转换后" name="converted" />
        </el-tabs>
        <div class="code" ref="codeBox">
          <div v-for="(line, idx) in lines" :key="idx" class="code-line"
               :class="rowClass(idx + 1)">
            <span class="no">{{ idx + 1 }}</span>
            <span class="txt">{{ line || ' ' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { getReport, getScriptFile } from '@/api/transform'

const route = useRoute()
const name = decodeURIComponent(route.params.name)

const report = ref(null)
const sourceLines = ref([])
const convertedLines = ref([])
const convertedMissing = ref(false)
const tab = ref('source')
const filterType = ref('')
const activeEdit = ref(null)
const codeBox = ref(null)

// 后端 transform_table_mapping.py 实际发出的 6 种 edit type 全量映射
// join_inject_failed 用 danger+dark（实底红）与 table_ref 的浅色 danger 区分；
// 6 类型 > el-tag 5 内置色，故用 effect 维度避免颜色冲突
const TYPE = {
  table_ref: { label: '表引用', tag: 'danger' },
  rename: { label: '列改名', tag: 'primary' },
  join_inject: { label: 'JOIN注入', tag: 'warning' },
  join_ref: { label: 'JOIN引用', tag: 'success' },
  join_inject_failed: { label: '注入失败', tag: 'danger', effect: 'dark' },
  subquery_prop: { label: '子查询下推', tag: 'info' },
}
const typeLabel = (t) => TYPE[t]?.label || t
const typeTag = (t) => TYPE[t]?.tag || 'info'
const typeEffect = (t) => TYPE[t]?.effect || 'light'
// 基于实际 edits 中出现过的 type 动态枚举筛选按钮；未在 TYPE 中的新类型
// 以 key 原文作 label 兜底，免疫后端新增类型
const typeNames = computed(() => {
  const c = {}
  edits.value.forEach((e) => { c[e.type] = (c[e.type] || 0) + 1 })
  return Object.keys(c).map((k) => ({ key: k, label: TYPE[k]?.label || k, count: c[k] }))
})

const edits = computed(() => report.value?.edits || [])
const shownEdits = computed(() =>
  filterType.value ? edits.value.filter((e) => e.type === filterType.value) : edits.value)
const lines = computed(() => (tab.value === 'source' ? sourceLines : convertedLines).value)

// 映射快照文案：含 {FDM}.F_X 花括号，用 JS 计算字符串再插值，避免模板里 {{ 转义问题
const mapText = computed(() => report.value
  ? `{${report.value.config.old_db}}.${report.value.config.old_table} → {${report.value.config.new_db}}.${report.value.config.new_table}`
  : '')

// 行高亮：拆成 editLines（蓝/marked）与 residueLines（黄/residue-mark）两个 Set
// 转换前视图：只有 edits 的 line 行加 marked
// 转换后视图：edits 的 new_line 行加 marked，residues 的 line 行加 residue-mark
// 同一行既是 edit 又是 residue 时，CSS 中 residue-mark 后定义，黄色优先
const editLines = computed(() => {
  const s = new Set()
  if (tab.value === 'source') edits.value.forEach((e) => s.add(e.line))
  else edits.value.forEach((e) => e.new_line && s.add(e.new_line))
  return s
})
const residueLines = computed(() => {
  const s = new Set()
  if (tab.value === 'converted') {
    (report.value?.residues || []).forEach((r) => s.add(r.line))
  }
  return s
})
const rowClass = (ln) => {
  const cls = []
  if (editLines.value.has(ln)) cls.push('marked')
  if (residueLines.value.has(ln)) cls.push('residue-mark')
  return cls.join(' ')
}

// 定位：转换前用 e.line，转换后用 e.new_line（规格 §5.1 契约）
function locate(e) {
  activeEdit.value = e
  const target = tab.value === 'source' ? e.line : (e.new_line || e.line)
  const box = codeBox.value
  const row = box?.querySelector(`.code-line:nth-child(${target})`)
  if (row) {
    box.scrollTop = row.offsetTop - box.clientHeight / 3
    row.classList.add('flash')
    setTimeout(() => row.classList.remove('flash'), 1600)
  }
}
// tab 切换后需等 Vue 应用完 lines 的 DOM 补丁再查行元素，否则查到的是旧 tab
// 的行（转换后行数更多时目标行在旧 DOM 不存在），且重渲染会抹掉 flash class
async function onTabChange() {
  if (!activeEdit.value) return
  await nextTick()
  locate(activeEdit.value)
}

// request.js 响应拦截器返回 response.data（即 Result 对象）；
// 各项 value.data 已是 Result.data（业务数据），不要再 .data
// converted 缺失（FAILED 脚本无产物，后端 400）时降级展示 report+source，
// 由顶部 el-alert 提示；report/source 失败仍交给拦截器 toast
onMounted(async () => {
  const [repR, sR, cR] = await Promise.allSettled([
    getReport(name), getScriptFile(name, 'source'), getScriptFile(name, 'converted'),
  ])
  if (repR.status === 'fulfilled') report.value = repR.value.data
  if (sR.status === 'fulfilled') sourceLines.value = (sR.value.data || '').split('\n')
  if (cR.status === 'fulfilled') {
    convertedLines.value = (cR.value.data || '').split('\n')
  } else {
    convertedMissing.value = true
  }
})
</script>

<style scoped>
.layout { display: flex; gap: 12px; height: calc(100vh - 260px); }
.edits-pane { width: 420px; overflow: auto; border: 1px solid #ebeef5; padding: 8px; }
.edit-item { padding: 6px 8px; cursor: pointer; border-radius: 4px; font-size: 12px; }
.edit-item:hover, .edit-item.active { background: #ecf5ff; }
.edit-item .ln { color: #909399; margin: 0 8px; }
.code-pane { flex: 1; border: 1px solid #ebeef5; overflow: hidden; display: flex; flex-direction: column; }
/* position:relative 使行元素 offsetTop 以本容器为 offsetParent，
   locate() 的 scrollTop 数学才成立（否则相对 body，目标行会滚出视口） */
.code { position: relative; flex: 1; overflow: auto; font-family: Consolas, Monaco, monospace; font-size: 12px; background: #fafafa; }
.code-line { display: flex; white-space: pre; line-height: 20px; }
.code-line .no { width: 56px; text-align: right; padding-right: 10px; color: #c0c4cc; user-select: none; flex: none; }
.code-line.marked { background: #d9ecff; }
.code-line.residue-mark { background: #faecd8; }
.code-line.flash { outline: 2px solid #409eff; }
</style>
