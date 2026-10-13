<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="blob1"></div>
        <div class="blob2"></div>
        <div class="brand-inner">
          <div class="logo">◈</div>
          <div class="brand-title">影响分析工具</div>
          <div class="brand-sub">存储过程依赖抽取与影响分析平台</div>
        </div>
        <div class="copyright">© 2026 HB Data</div>
      </div>
      <div class="form-area">
        <div class="hint">请登录你的账号</div>
        <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" class="field" />
        <el-input v-model="form.password" type="password" placeholder="请输入密码"
                  :prefix-icon="Lock" show-password class="field" @keyup.enter="submit" />
        <div class="err">{{ error }}</div>
        <el-button class="login-btn" @click="submit">登 录</el-button>
        <div class="forgot">忘记密码请联系管理员</div>
      </div>
    </div>
    <ChangePasswordDialog v-model="showChangePwd" :force="forceChange" @success="onChanged" />
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { setToken, setUser } from '../utils/auth'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const error = ref('')
const showChangePwd = ref(false)
const forceChange = ref(false)

async function submit() {
  error.value = ''
  try {
    const res = await login({ username: form.username, password: form.password })
    setToken(res.data.token)
    setUser(res.data.user)
    if (res.data.mustChangePwd) {
      forceChange.value = true
      showChangePwd.value = true
    } else {
      router.replace('/')
    }
  } catch (e) {
    error.value = e.message || '登录失败'
  }
}

function onChanged() {
  forceChange.value = false
  router.replace('/')
}
</script>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: #eef1f6; }
.login-card { width: 760px; height: 400px; display: flex; border-radius: 16px; overflow: hidden;
  box-shadow: 0 12px 44px rgba(15,32,39,.28); }
.brand { width: 66%; position: relative; background: linear-gradient(150deg,#0f2027,#203a43,#2c5364);
  color: #fff; padding: 34px; display: flex; flex-direction: column; justify-content: space-between; }
.blob1 { position: absolute; width: 170px; height: 170px; border-radius: 50%;
  background: rgba(79,172,254,.4); filter: blur(52px); top: -30px; left: -20px; }
.blob2 { position: absolute; width: 150px; height: 150px; border-radius: 50%;
  background: rgba(123,92,255,.45); filter: blur(48px); bottom: -24px; right: -16px; }
.brand-inner { position: relative; }
.logo { width: 42px; height: 42px; border-radius: 11px; background: rgba(255,255,255,.16);
  border: 1px solid rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center; font-size: 21px; }
.brand-title { margin-top: 22px; font-size: 22px; font-weight: 700; }
.brand-sub { margin-top: 12px; font-size: 13px; opacity: .82; line-height: 1.8; max-width: 280px; }
.copyright { position: relative; font-size: 11.5px; opacity: .6; }
.form-area { width: 34%; padding: 34px 26px; display: flex; flex-direction: column; justify-content: center; }
.hint { font-size: 13px; color: #9aa4b2; margin-bottom: 20px; }
.field { margin-bottom: 14px; }
.err { color: #f56c6c; font-size: 12px; height: 16px; }
.login-btn { width: 100%; border: none; color: #fff;
  background: linear-gradient(90deg,#4facfe,#7b5cff); }
.forgot { margin-top: 14px; text-align: center; font-size: 12px; color: #9aa4b2; }
</style>