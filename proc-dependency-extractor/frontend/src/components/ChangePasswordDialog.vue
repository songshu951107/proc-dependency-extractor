<template>
  <el-dialog :model-value="modelValue" :title="force ? '首次登录请修改密码' : '修改密码'"
             width="420px" :close-on-click-modal="!force" :show-close="!force"
             @close="onClose">
    <el-form :model="form" label-width="80px">
      <el-form-item label="原密码">
        <el-input v-model="form.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="form.newPassword" type="password" show-password
                  placeholder="≥8位且含大小写字母和数字" />
      </el-form-item>
      <el-form-item label="确认">
        <el-input v-model="form.confirm" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="submit" type="primary">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '../api/auth'

const props = defineProps({ modelValue: Boolean, force: Boolean })
const emit = defineEmits(['update:modelValue', 'success'])
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function onClose() { if (!props.force) emit('update:modelValue', false) }

async function submit() {
  if (form.newPassword !== form.confirm) return ElMessage.warning('两次密码不一致')
  if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/.test(form.newPassword)) {
    return ElMessage.warning('新密码须≥8位且含大小写字母和数字')
  }
  await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
  ElMessage.success('修改成功')
  emit('success')
  emit('update:modelValue', false)
}
</script>