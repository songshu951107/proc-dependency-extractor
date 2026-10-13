<template>
  <div style="display:flex;gap:16px">
    <div style="width:200px;border-right:1px solid #eee;padding-right:12px">
      <div v-for="r in roles" :key="r.id" @click="select(r)"
           :style="{ padding:'8px', borderRadius:'6px', cursor:'pointer',
                     background: current?.id===r.id ? '#ecf5ff' : 'transparent' }">
        {{ r.roleName }}
      </div>
      <el-button style="width:100%;margin-top:10px" @click="addRole">新增角色</el-button>
    </div>
    <div style="flex:1">
      <div style="font-size:13px;color:#666;margin-bottom:8px">勾选该角色可见的菜单/页面</div>
      <el-tree :key="current?.id ?? 0" ref="treeRef" :data="tree" show-checkbox node-key="id"
               :props="treeProps" default-expand-all />
      <el-button type="primary" style="margin-top:12px" @click="save">保存</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listRoles, menuTree, roleMenus, assignRoleMenus, createRole } from '../api/role'

const roles = ref([]); const tree = ref([]); const current = ref(null); const treeRef = ref()

// 「系统管理」分组（menuCode=system）及其子树：仅管理员角色可分配
const isAdminRole = computed(() => current.value?.roleCode === 'ADMIN')
const systemIds = computed(() => {
  const ids = new Set()
  const walk = (nodes, inSys) => {
    for (const n of nodes || []) {
      const cur = inSys || n.menuCode === 'system'
      if (cur) ids.add(n.id)
      walk(n.children, cur)
    }
  }
  walk(tree.value, false)
  return ids
})
const treeProps = computed(() => ({
  label: 'menuName', children: 'children',
  disabled: (data) => !isAdminRole.value && systemIds.value.has(data.id)
}))

async function load() {
  roles.value = (await listRoles()).data
  tree.value = (await menuTree()).data
  if (roles.value.length) select(roles.value[0])
}
async function select(role) {
  current.value = role
  const ids = (await roleMenus(role.id)).data
  await nextTick()
  treeRef.value?.setCheckedKeys(ids)
}
async function save() {
  if (!current.value) return
  let menuIds = treeRef.value.getCheckedKeys().concat(treeRef.value.getHalfCheckedKeys())
  if (!isAdminRole.value) menuIds = menuIds.filter(id => !systemIds.value.has(id))
  await assignRoleMenus(current.value.id, menuIds)
  ElMessage.success('保存成功')
}
async function addRole() {
  const { value } = await ElMessageBox.prompt('角色名称', '新增角色', { inputPattern: /\S+/, inputErrorMessage: '不能为空' })
  await createRole({ roleName: value, roleCode: 'ROLE_' + Date.now() })
  ElMessage.success('已新增'); load()
}
onMounted(load)
</script>