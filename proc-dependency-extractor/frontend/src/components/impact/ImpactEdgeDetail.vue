<template>
  <div class="edge-detail">
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="源">{{ edge.source }}</el-descriptions-item>
      <el-descriptions-item label="目标">{{ edge.target }}</el-descriptions-item>
      <el-descriptions-item label="边类型" v-if="edge.edgeType">
        <el-tag :type="edgeTypeTag(edge.edgeType)" size="small">{{ edge.edgeType }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="角色" v-if="edge.role">
        <el-tag :type="edge.role === 'condition' ? 'warning' : 'success'" size="small">
          {{ edge.role }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="操作" v-if="edge.operation">{{ edge.operation }}</el-descriptions-item>
      <el-descriptions-item label="SQL块序号" v-if="edge.sqlBlockSeq">{{ edge.sqlBlockSeq }}</el-descriptions-item>
      <el-descriptions-item label="主表" v-if="edge.mainTable">{{ edge.mainTable }}</el-descriptions-item>
      <el-descriptions-item label="来源表达式" v-if="edge.sourceExpr">{{ edge.sourceExpr }}</el-descriptions-item>
      <el-descriptions-item label="表达式" v-if="edge.expression">
        <code class="expression-code">{{ edge.expression }}</code>
      </el-descriptions-item>
      <el-descriptions-item label="来源别名" v-if="edge.sourceAlias">{{ edge.sourceAlias }}</el-descriptions-item>
    </el-descriptions>

    <div v-if="edge.joinConditions && edge.joinConditions.length" style="margin-top: 10px">
      <div style="font-weight: bold; margin-bottom: 5px">JOIN 条件</div>
      <div v-for="(c, i) in edge.joinConditions" :key="i" class="condition-item">
        {{ c.clause }}
        <div v-if="c.fields && c.fields.length" style="color: #999; font-size: 12px">
          涉及字段: {{ c.fields.join(', ') }}
        </div>
      </div>
    </div>

    <div v-if="edge.whereConditions && edge.whereConditions.length" style="margin-top: 10px">
      <div style="font-weight: bold; margin-bottom: 5px">WHERE 条件</div>
      <div v-for="(c, i) in edge.whereConditions" :key="i" class="condition-item">
        {{ c.clause }}
        <div v-if="c.fields && c.fields.length" style="color: #999; font-size: 12px">
          涉及字段: {{ c.fields.join(', ') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({ edge: Object })

const edgeTypeTag = (type) => {
  if (type === 'INPUT_TO') return 'warning'
  if (type === 'OUTPUT_TO') return 'primary'
  if (type === 'READS') return 'info'
  return ''
}
</script>

<style scoped>
.condition-item {
  padding: 4px 8px;
  margin-bottom: 4px;
  background: #f5f5f5;
  border-radius: 2px;
  font-size: 13px;
}
.expression-code {
  display: block;
  padding: 6px 8px;
  background: #f5f5f5;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
