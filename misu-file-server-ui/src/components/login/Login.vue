<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '@/api/auth/auth'
import { setLoginTokens } from '@/api/auth/token'
import { setUserInfo } from '@/api/user/user'

const router = useRouter()

const userName = ref('')
const password = ref('')
const captchaCode = ref('123')

const errorMsg = ref('')
const loading = ref(false)

const handleLogin = () => {
  errorMsg.value = ''

  if (!userName.value.trim()) {
    errorMsg.value = '账号不能为空'
    return
  }
  if (!password.value) {
    errorMsg.value = '密码不能为空'
    return
  }

  loading.value = true
  login(userName.value, password.value, captchaCode.value)
      .then(response => {
        setLoginTokens(response.data.token, response.data.refreshToken)
        return setUserInfo()
      })
      .then(() => {
        router.push('/')
      })
      .catch(err => {
        errorMsg.value = (err && err.message) ? err.message : '登录失败，请检查账号或密码'
      })
      .finally(() => {
        loading.value = false
      })
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <header class="login-brand">
        <div class="login-logo" aria-hidden="true">M</div>
        <h1 class="login-title">misu</h1>
        <p class="login-tagline">欢迎回来</p>
      </header>

      <form class="login-form" @submit.prevent="handleLogin">
        <label class="login-field">
          <span class="login-field-label">账号</span>
          <el-input
              v-model="userName"
              placeholder="请输入账号"
              clearable
              autocomplete="username"
              size="large"
              @keyup.enter="handleLogin"/>
        </label>

        <label class="login-field">
          <span class="login-field-label">密码</span>
          <el-input
              v-model="password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
              size="large"
              @keyup.enter="handleLogin"/>
        </label>

        <p v-if="errorMsg" class="login-error" role="alert">{{ errorMsg }}</p>

        <el-button
            type="primary"
            class="login-submit"
            size="large"
            :loading="loading"
            @click="handleLogin">
          登 录
        </el-button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100svh;
  /* iOS / Android 键盘弹出时使用动态视口高度 */
  min-height: 100dvh;
  width: 100%;
  padding: var(--space-6) var(--space-5);
  background: var(--color-bg-base);
  /* 安全区 */
  padding-top: max(var(--space-6), env(safe-area-inset-top));
  padding-bottom: max(var(--space-6), env(safe-area-inset-bottom));
}

/* ---------- Card 容器 ----------
   桌面：360 居中卡片 + 阴影
   移动：撑开整页（去除卡片框感） */
.login-card {
  width: 100%;
  max-width: 360px;
  display: flex;
  flex-direction: column;
  background: transparent;
  border: none;
}

@media (min-width: 641px) {
  .login-card {
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-md);
    padding: var(--space-10) var(--space-8) var(--space-8);
  }
}

/* ---------- Brand ---------- */
.login-brand {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  margin-bottom: var(--space-8);
}

@media (min-width: 641px) {
  .login-brand {
    align-items: center;
    text-align: center;
    gap: var(--space-2);
    margin-bottom: var(--space-6);
  }
}

.login-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: var(--radius-md);
  font-size: 22px;
  font-weight: var(--font-weight-semibold);
  letter-spacing: -0.02em;
}

.login-title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
  line-height: var(--line-height-tight);
}

.login-tagline {
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
}

/* ---------- Form ---------- */
.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  width: 100%;
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.login-field-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.login-error {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-danger);
  line-height: var(--line-height-normal);
}

.login-submit {
  width: 100%;
  margin-top: var(--space-2);
  /* 字距让"登 录"两字看起来不挤 */
  letter-spacing: 0.4em;
  padding-left: 0.4em;
}

/* 让 Element Plus input 的字号在移动端保持 16px 防 iOS 缩放 */
:deep(.el-input__inner) {
  font-size: var(--font-size-md);
}

/* large size 输入框高度更舒适 */
:deep(.el-input--large .el-input__wrapper) {
  border-radius: var(--radius-md);
}

/* 主按钮 large size 更易点中（移动端 hit-target ≥ 44） */
:deep(.login-submit.el-button--large) {
  height: 48px;
  border-radius: var(--radius-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}
</style>
