<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="title"
    width="80%"
    top="5vh"
    class="script-viewer-dialog"
  >
    <div v-loading="loading" class="script-viewer-body">
      <pre ref="preRef" class="script-content"><code v-html="highlightedContent"></code></pre>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { getScript } from '../api/script'
import { ElMessage } from 'element-plus'

const props = defineProps({
  visible: Boolean,
  procName: String,
  highlightBlockSeq: { type: Number, default: null }
})

defineEmits(['update:visible'])

const loading = ref(false)
const content = ref('')
const sqlBlocks = ref([])
const preRef = ref(null)

const title = computed(() => `脚本查看：${props.procName || ''}`)

const highlightedContent = computed(() => {
  if (!content.value) return ''
  const lines = content.value.split('\n')
  return lines
    .map((line, i) => {
      const lineNum = i + 1
      const inHighlight = isInHighlight(lineNum)
      const escaped = escapeHtml(line)
      const lineNumSpan = `<span class="line-num">${lineNum}</span>`
      const lineContent = inHighlight
        ? `<span class="highlight-line">${escaped}</span>`
        : escaped
      return `${lineNumSpan}\t${lineContent}`
    })
    .join('\n')
})

function isInHighlight(lineNum) {
  if (!props.highlightBlockSeq) return false
  const block = sqlBlocks.value.find((b) => b.seq === props.highlightBlockSeq)
  if (!block) return false
  return lineNum >= block.startLine && lineNum <= block.endLine
}

function escapeHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

async function loadScript() {
  if (!props.procName) return
  loading.value = true
  try {
    const res = await getScript(props.procName)
    const data = res.data || res
    content.value = data.content || ''
    sqlBlocks.value = data.sqlBlocks || []
    if (props.highlightBlockSeq) {
      await nextTick()
      scrollToBlock(props.highlightBlockSeq)
    }
  } catch (e) {
    ElMessage.error('加载脚本失败')
  } finally {
    loading.value = false
  }
}

function scrollToBlock(seq) {
  const block = sqlBlocks.value.find((b) => b.seq === seq)
  if (!block || !preRef.value) return
  const lineHeight = 18
  const scrollTop = (block.startLine - 3) * lineHeight
  preRef.value.scrollTop = Math.max(0, scrollTop)
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      loadScript()
    }
  }
)
</script>

<style scoped>
.script-viewer-dialog :deep(.el-dialog__body) {
  padding: 0;
}
.script-viewer-body {
  height: 70vh;
  overflow: hidden;
}
.script-content {
  height: 100%;
  overflow: auto;
  margin: 0;
  padding: 8px 16px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 18px;
  background: #fafafa;
  white-space: pre;
}
.script-content :deep(.line-num) {
  color: #c0c4cc;
  user-select: none;
  margin-right: 8px;
}
.script-content :deep(.highlight-line) {
  background: #fff3cd;
  display: inline-block;
  width: 100%;
}
</style>
