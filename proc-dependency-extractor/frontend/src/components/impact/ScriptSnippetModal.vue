<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="80%"
    top="5vh"
    @close="$emit('close')"
  >
    <div v-loading="loading" class="modal-body">
      <div v-if="error" class="error-tip">
        <el-alert :title="error" type="error" show-icon :closable="false" />
      </div>
      <template v-else-if="scriptContent">
        <div class="modal-info">
          受上游表 <strong>{{ impactSource }}</strong> 影响
          <span v-if="highlightLines.size === 0" class="no-match-tip">
            （脚本中未直接引用该表，影响可能通过中间表传递）
          </span>
        </div>
        <div class="script-viewer" ref="scriptViewer">
          <div
            v-for="(line, idx) in scriptLines"
            :key="idx"
            :class="['script-line', { 'highlight-line': highlightLines.has(idx + 1) }]"
          >
            <span class="line-number">{{ idx + 1 }}</span>
            <span class="line-content">{{ line }}</span>
          </div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { getScriptByFile } from '@/api/script'

const props = defineProps({
  visible: { type: Boolean, default: false },
  scriptFile: { type: String, default: '' },
  impactSource: { type: String, default: '' },
  targetTable: { type: String, default: '' }
})

defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const scriptContent = ref('')
const scriptLines = ref([])
const highlightLines = ref(new Set())
const scriptViewer = ref(null)

const dialogTitle = computed(() => {
  return props.scriptFile || '代码片段'
})

function computeHighlightLines(content, impactSource) {
  if (!impactSource) return new Set()
  const lines = content.split('\n')
  const highlightSet = new Set()
  const tableName = impactSource.split('.').pop().toUpperCase()
  const shortName = tableName.replace(/^VT_/, '')

  // 词边界匹配：表名前后不能紧跟标识符字符（[A-Z0-9_]）。
  // 子串匹配会把 F_AC_CRBS_DEPOSIT_ACCT 误配到 F_AC_CRBS_DEPOSIT_ACCT_INT_RATE_DEF
  // （后者是前者的延伸表名），导致高亮到引用其他表的行
  const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const names = [tableName]
  if (shortName && shortName !== tableName) names.push(shortName)
  const tableRefPattern = new RegExp(`(?<![A-Z0-9_])(${names.map(escapeRegExp).join('|')})(?![A-Z0-9_])`)

  // 检查一行是否引用了该表
  function referencesTable(upperLine) {
    return tableRefPattern.test(upperLine)
  }

  lines.forEach((line, idx) => {
    const upperLine = line.toUpperCase()
    const isComment = line.trim().startsWith('--') || line.trim().startsWith('#')
    if (isComment) return
    if (!referencesTable(upperLine)) return

    // 排除该表作为目标表的操作行（建表/插入/更新/删除/合并）
    // 只保留该表作为来源表（FROM/JOIN）被引用的行
    if (/\b(CREATE\s+TEMP(ORARY)?\s+TABLE|INSERT\s+INTO|MERGE\s+INTO|UPDATE\s+|DELETE\s+FROM)\b/.test(upperLine)) {
      return
    }

    highlightSet.add(idx + 1)

    // FROM/JOIN 行：继续高亮紧随的 ON/AND 条件行
    if (/\b(FROM|JOIN)\b/.test(upperLine)) {
      for (let i = idx + 1; i < lines.length; i++) {
        const nextTrimmed = lines[i].trim().toUpperCase()
        if (nextTrimmed.startsWith('--') || nextTrimmed.startsWith('#')) continue
        if (nextTrimmed.startsWith('ON ') || nextTrimmed.startsWith('AND ')) {
          highlightSet.add(i + 1)
        } else {
          break
        }
      }
    }
  })
  return highlightSet
}

async function loadScript() {
  if (!props.scriptFile) {
    error.value = '脚本文件名为空'
    return
  }
  loading.value = true
  error.value = ''
  scriptContent.value = ''
  try {
    const res = await getScriptByFile(props.scriptFile)
    const data = res.data || {}
    scriptContent.value = data.content || ''
    scriptLines.value = scriptContent.value.split('\n')
    highlightLines.value = computeHighlightLines(scriptContent.value, props.impactSource)

    if (highlightLines.value.size > 0) {
      nextTick(() => {
        const firstLine = Math.min(...highlightLines.value)
        if (scriptViewer.value) {
          const lineEl = scriptViewer.value.querySelector(`.script-line:nth-child(${firstLine})`)
          if (lineEl) lineEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
        }
      })
    }
  } catch (e) {
    error.value = e.message || '脚本获取失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (val) => {
  if (val) loadScript()
}, { immediate: true })
</script>

<style scoped>
.modal-body {
  min-height: 300px;
  max-height: 75vh;
}
.modal-info {
  margin-bottom: 8px;
  padding: 8px 12px;
  background: #f5f5f5;
  border-radius: 4px;
  font-size: 13px;
}
.no-match-tip {
  color: #faad14;
  margin-left: 8px;
}
.script-viewer {
  max-height: 65vh;
  overflow: auto;
  background: #1f1f1f;
  border-radius: 4px;
  padding: 8px 0;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.script-line {
  display: flex;
  padding: 0 12px;
}
.script-line:hover {
  background: #2a2a2a;
}
.line-number {
  color: #5a5a5a;
  width: 50px;
  text-align: right;
  padding-right: 12px;
  flex-shrink: 0;
  user-select: none;
}
.line-content {
  color: #d4d4d4;
  white-space: pre;
  flex: 1;
}
.highlight-line {
  background: #3b1f1f !important;
  border-left: 3px solid #ef4444;
  padding-left: 9px;
}
.highlight-line .line-content {
  color: #ef9a9a;
}
.error-tip {
  padding: 20px 0;
}
</style>
