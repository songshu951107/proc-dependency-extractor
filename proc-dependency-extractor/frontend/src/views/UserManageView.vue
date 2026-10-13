<template>
  <div>
    <div style="display:flex;gap:8px;margin-bottom:12px">
      <el-button type="primary" @click="openDialog()">新增用户</el-button>
      <el-upload :show-file-list="false" :before-upload="onImport" accept=".xlsx,.xls">
        <el-button type="success">Excel 导入</el-button>
      </el-upload>
      <el-input v-model="keyword" placeholder="按用户名/姓名搜索" style="width:220px" />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="realName" label="姓名" />
      <el-table-column label="角色">
        <template #default="{ row }">{{ roleName(row.roleId) }}</template>
      </el-table-column>
      <el-table-column prop="dept" label="部门" />
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="warning" @click="onReset(row)">重置密码</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="pageNum" @current-change="onPage" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="70px">
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleId">
            <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门"><el-input v-model="form.dept" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入结果" width="480px">
      <p>成功 {{ importResult.successCount }} 条，跳过 {{ importResult.skipCount }} 条</p>
      <ul><li v-for="(r, i) in importResult.skipReasons" :key="i">{{ r }}</li></ul>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageUsers, createUser, updateUser, deleteUser, resetPassword, importUsers } from '../api/user'
import { listRoles } from '../api/role'

const rows = ref([]); const total = ref(0); const pageNum = ref(1); const pageSize = 10
const keyword = ref(''); const roles = ref([])
const dialogVisible = ref(false); const importVisible = ref(false)
const importResult = reactive({ successCount: 0, skipCount: 0, skipReasons: [] })
const form = reactive({ id: null, username: '', realName: '', roleId: null, dept: '', email: '', phone: '', status: 1 })

const roleName = (id) => roles.value.find(r => r.id === id)?.roleName || '-'

async function load() {
  const res = await pageUsers({ pageNum: pageNum.value, pageSize, keyword: keyword.value })
  rows.value = res.data.records; total.value = Number(res.data.total)
}
function onPage(p) { pageNum.value = p; load() }

function openDialog(row) {
  Object.assign(form, row ? { ...row } : { id: null, username: '', realName: '', roleId: roles.value[0]?.id, dept: '', email: '', phone: '', status: 1 })
  dialogVisible.value = true
}
async function save() {
  form.id ? await updateUser({ ...form }) : await createUser({ ...form })
  ElMessage.success('保存成功'); dialogVisible.value = false; load()
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除用户 ${row.username}？`, '提示', { type: 'warning' })
  await deleteUser(row.id); ElMessage.success('已删除'); load()
}
async function onReset(row) {
  await ElMessageBox.confirm(`将 ${row.username} 的密码重置为默认密码？`, '提示', { type: 'warning' })
  await resetPassword(row.id); ElMessage.success('已重置'); load()
}
async function onImport(file) {
  const res = await importUsers(file)
  Object.assign(importResult, res.data)
  importVisible.value = true; load()
  return false
}

onMounted(async () => { roles.value = (await listRoles()).data; load() })
</script>