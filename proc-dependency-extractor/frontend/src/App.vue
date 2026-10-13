<template>
  <router-view v-if="isLogin" />
  <el-container v-else style="height: 100vh">
    <el-header style="background:#409eff;color:#fff;display:flex;align-items:center;justify-content:space-between">
      <div style="display:flex;align-items:center">
        <el-icon style="margin-right:8px"><Connection /></el-icon>
        <span style="font-size:18px;font-weight:bold">影响分析工具</span>
      </div>
      <el-dropdown @command="onCommand">
        <span style="color:#fff;cursor:pointer">{{ user?.realName || user?.username }} ▾</span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="pwd">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-container>
      <el-aside width="200px" style="background:#304156">
        <el-menu :default-active="$route.path" router background-color="#304156"
                 text-color="#bfcbd9" active-text-color="#409eff">
          <template v-for="m in user?.menus || []" :key="m.id">
            <el-sub-menu v-if="m.children && m.children.length" :index="String(m.id)">
              <template #title><span>{{ m.menuName }}</span></template>
              <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path">{{ c.menuName }}</el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="m.path">{{ m.menuName }}</el-menu-item>
          </template>
        </el-menu>
      </el-aside>
      <el-main><router-view /></el-main>
    </el-container>
    <ChangePasswordDialog v-model="showPwd" @success="showPwd=false" />
  </el-container>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Connection } from '@element-plus/icons-vue'
import { getUser, clearAuth } from './utils/auth'
import request from './api/request'
import ChangePasswordDialog from './components/ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const user = ref(getUser())
const isLogin = computed(() => route.path === '/login')
const showPwd = ref(false)

watch(() => route.fullPath, () => { user.value = getUser() })

watch(() => route.path, (path) => {
  if (!isLogin.value) {
    request.post('/logs/page-view', { pagePath: path, pageName: route.meta.title || path })
      .catch(() => {})
  }
})

function onCommand(cmd) {
  if (cmd === 'pwd') showPwd.value = true
  if (cmd === 'logout') { clearAuth(); router.replace('/login') }
}
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  font-family: "Microsoft YaHei", Arial, sans-serif;
}
.el-menu {
  border-right: none;
}
</style>