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
.login-page { height: 100vh; display: flex; background: #eef1f6; }
.login-card { width: 100%; height: 100%; display: flex; overflow: hidden; }

/* 左侧品牌面板：内容垂直居中，版权固定左下，装饰光晕裁剪在面板内 */
.brand { flex: 2 1 0; min-width: 0; position: relative; overflow: hidden;
  background: linear-gradient(150deg,#0f2027,#203a43,#2c5364); color: #fff;
  display: flex; flex-direction: column; justify-content: center; align-items: flex-start;
  padding: 0 clamp(36px, 6vw, 96px); }
.blob1 { position: absolute; width: 340px; height: 340px; border-radius: 50%;
  background: rgba(79,172,254,.34); filter: blur(72px); top: -90px; left: -70px; }
.blob2 { position: absolute; width: 300px; height: 300px; border-radius: 50%;
  background: rgba(123,92,255,.40); filter: blur(66px); bottom: -80px; right: -60px; }
.brand-inner { position: relative; max-width: 520px; }
.logo { width: 58px; height: 58px; border-radius: 16px; background: rgba(255,255,255,.16);
  border: 1px solid rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center; font-size: 28px; }
.brand-title { margin-top: 28px; font-size: 38px; font-weight: 700; letter-spacing: 2px; }
.brand-sub { margin-top: 16px; font-size: 17px; opacity: .80; line-height: 1.8; max-width: 460px; }
.copyright { position: absolute; left: clamp(36px, 6vw, 96px); bottom: 30px; font-size: 12px; opacity: .55; }

/* 右侧表单栏：白底、内容居中且统一收窄到可读宽度 */
.form-area { flex: 1 1 0; background: #fff; padding: 40px clamp(28px, 5vw, 72px);
  display: flex; flex-direction: column; justify-content: center; align-items: center; }
.hint { width: 100%; max-width: 360px; font-size: 15px; color: #5b6472; margin-bottom: 22px; }
.field { width: 100%; max-width: 360px; margin-bottom: 16px; }
.field :deep(.el-input__wrapper) { border-radius: 8px; }
.err { width: 100%; max-width: 360px; color: #f56c6c; font-size: 12px; height: 16px; }
.login-btn { width: 100%; max-width: 360px; height: 40px; padding: 0 20px; font-size: 15px; border-radius: 8px;
  border: none; color: #fff; background: linear-gradient(90deg,#4facfe,#7b5cff); }
.forgot { width: 100%; max-width: 360px; margin-top: 16px; text-align: center; font-size: 12px; color: #9aa4b2; }
</style>