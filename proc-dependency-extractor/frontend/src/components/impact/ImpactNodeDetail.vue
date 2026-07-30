<template>
  <div class="node-detail">
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="ID">{{ node.id }}</el-descriptions-item>
      <el-descriptions-item label="名称">{{ node.label }}</el-descriptions-item>
      <el-descriptions-item label="类型" v-if="node.type">
        <el-tag :type="typeTagType(node.type)" size="small">{{ node.type }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="层级">{{ node.nodeLevel }}</el-descriptions-item>
      <el-descriptions-item label="深度">{{ node.depth ?? '-' }}</el-descriptions-item>
    </el-descriptions>
    <div style="margin-top: 10px; text-align: center">
      <el-button size="small" type="primary" @click="$emit('expand', node)">展开下游</el-button>
      <el-button size="small" @click="$emit('collapse', node)">收起下游</el-button>
    </div>
  </div>
</template>

<script setup>
defineProps({ node: Object })
defineEmits(['expand', 'collapse'])

const typeTagType = (type) => {
  if (type === 'SOURCE') return 'success'
  if (type === 'TEMP') return 'warning'
  if (type === 'TARGET') return 'danger'
  return 'info'
}
</script>
