<script setup>
import { computed, inject } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { logOut } from '@/api/auth/auth'
import ThemeSwitcher from '@/components/layout/ThemeSwitcher.vue'

const route = useRoute()
const router = useRouter()

const userInfo = inject('userInfo', { value: {} })

const titleByPath = {
  '/': '首页',
  '/userManagement': '用户管理',
  '/languageLearn': '语言学习',
  '/bot': '机器人'
}

const titleByMatch = [
  { contains: 'privateDirectory', title: '私人目录' },
  { contains: 'publicDirectory', title: '公共目录' },
  { contains: 'videoRoom', title: '放映室' },
  { contains: 'torrentManagement', title: '磁力下载' },
  { contains: 'videoTranscodeManagement', title: '转码管理' },
  { contains: 'epubViewer', title: 'Epub 阅读' }
]

const pageTitle = computed(() => {
  const p = route.path
  if (titleByPath[p]) return titleByPath[p]
  for (const m of titleByMatch) if (p.includes(m.contains)) return m.title
  return ''
})

const initial = computed(() => {
  const n = userInfo.value && userInfo.value.userName
  return (n ? String(n).charAt(0) : 'U').toUpperCase()
})

const handleLogout = () => {
  logOut()
  router.push('/login')
}
</script>

<template>
  <header class="page-header">
    <h1 class="page-header-title">{{ pageTitle }}</h1>

    <div class="page-header-actions">
      <ThemeSwitcher variant="compact" />

    <el-dropdown trigger="click" placement="bottom-end">
      <button class="user-trigger" type="button">
        <span class="user-avatar" aria-hidden="true">{{ initial }}</span>
        <span class="user-name">{{ userInfo.userName || '未登录' }}</span>
        <el-icon class="user-arrow"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>个人信息</el-dropdown-item>
          <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--layout-page-header-height);
  padding: 0 var(--space-6);
  background: var(--nav-bg);
  backdrop-filter: var(--nav-backdrop);
  -webkit-backdrop-filter: var(--nav-backdrop);
  border-bottom: 1px solid var(--nav-border);
  flex-shrink: 0;
}

.page-header-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
  line-height: 1;
}

.page-header-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3) var(--space-1) var(--space-1);
  border-radius: var(--radius-pill);
  color: var(--color-text-primary);
  font-size: var(--font-size-base);
  background: transparent;
  transition: background var(--duration-fast) var(--ease-standard);
}

.user-trigger:hover {
  background: var(--color-bg-hover);
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
}

.user-name {
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
}

.user-arrow {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
