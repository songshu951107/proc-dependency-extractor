<template>
  <div class="sql-block-details">
    <div v-loading="loading">
      <!-- 子 Tab：按目标表名命名 -->
      <el-tabs v-model="activeTab" v-if="blocks.length" type="card">
        <el-tab-pane
          v-for="block in blocks"
          :key="block.blockSeq"
          :name="String(block.blockSeq)"
        >
          <template #label>
            <span>{{ block.targetTable || `Block${block.blockSeq}` }}</span>
            <el-tag
              size="small"
              :type="tagType(block.targetTableType)"
              style="margin-left: 4px"
            >{{ block.targetTableType || 'OTHER' }}</el-tag>
          </template>

          <!-- 子 Tab 内容 -->
          <div class="block-content">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="目标表">
                <el-tag :type="tagType(block.targetTableType)" size="small">
                  {{ block.targetTable || '-' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="主表">
                <el-tag :type="tagType(block.mainTableType)" size="small">
                  {{ block.mainTable || '-' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="块类型">{{ block.blockType }}</el-descriptions-item>
              <el-descriptions-item label="行号">
                {{ block.startLine }} - {{ block.endLine }}
              </el-descriptions-item>
            </el-descriptions>

            <!-- 关联表列表 -->
            <el-divider content-position="left">关联表</el-divider>
            <el-table :data="block.joinTables" border size="small" v-if="block.joinTables?.length">
              <el-table-column type="index" width="50" label="#" />
              <el-table-column label="表名" min-width="200">
                <template #default="{ row }">
                  <el-button
                    v-if="isTempTable(row.tableName)"
                    link
                    type="warning"
                    @click="jumpToBlock(row.tableName)"
                  >{{ row.tableName }}</el-button>
                  <span v-else>{{ row.tableName || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="joinType" label="JOIN 类型" width="120" />
              <el-table-column prop="conditions" label="ON 条件" min-width="300" show-overflow-tooltip />
            </el-table>
            <el-empty v-else description="无关联表" :image-size="40" />

            <!-- WHERE 条件 -->
            <el-divider content-position="left">WHERE 条件</el-divider>
            <pre v-if="block.whereConditions" class="sql-snippet">{{ block.whereConditions }}</pre>
            <el-empty v-else description="无 WHERE 条件" :image-size="40" />

            <!-- SQL 片段 -->
            <el-divider content-position="left">SQL 片段</el-divider>
            <div class="sql-snippet-wrapper">
              <pre v-if="!block.expandedSql" class="sql-snippet collapsed">{{ truncateSql(block.sqlContent) }}</pre>
              <pre v-else class="sql-snippet">{{ block.sqlContent }}</pre>
              <el-button
                link
                type="primary"
                size="small"
                @click="block.expandedSql = !block.expandedSql"
              >{{ block.expandedSql ? '收起' : '展开全部' }}</el-button>
              <el-button
                link
                type="primary"
                size="small"
                @click="$emit('view-script', block.blockSeq)"
              >查看脚本位置</el-button>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <el-empty v-else description="无 SQL 块信息，请重新解析" />
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getSqlBlocks } from '../api/sqlBlock'
import { ElMessage } from 'element-plus'

const props = defineProps({
  procName: String
})

defineEmits(['view-script'])

const loading = ref(false)
const blocks = ref([])
const activeTab = ref('')

function tagType(type) {
  const map = { SOURCE: 'success', TEMP: 'warning', TARGET: 'danger' }
  return map[type] || 'info'
}

function isTempTable(name) {
  return name && name.startsWith('VT_')
}

function truncateSql(sql) {
  if (!sql) return ''
  const lines = sql.split('\n')
  if (lines.length <= 10) return sql
  return lines.slice(0, 10).join('\n') + `\n... (共 ${lines.length} 行，点击展开) `
}

async function loadBlocks() {
  if (!props.procName) return
  loading.value = true
  try {
    const res = await getSqlBlocks(props.procName)
    blocks.value = (res.data || res || []).map((b) => ({ ...b, expandedSql: false }))
    if (blocks.value.length) {
      activeTab.value = String(blocks.value[0].blockSeq)
    }
  } catch (e) {
    ElMessage.error('加载 SQL 块详情失败')
    blocks.value = []
  } finally {
    loading.value = false
  }
}

function jumpToBlock(tableName) {
  const target = blocks.value.find((b) => b.targetTable === tableName)
  if (target) {
    activeTab.value = String(target.blockSeq)
  } else {
    ElMessage.info(`未找到目标表为 ${tableName} 的 SQL 块`)
  }
}

watch(
  () => props.procName,
  () => loadBlocks(),
  { immediate: true }
)
</script>

<style scoped>
.sql-block-details {
  padding: 12px;
}
.block-content {
  padding: 8px;
}
.sql-snippet {
  margin: 0;
  background: #f5f7fa;
  padding: 12px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow: auto;
}
.sql-snippet.collapsed {
  max-height: 200px;
  overflow: hidden;
}
.sql-snippet-wrapper {
  position: relative;
}
</style>
