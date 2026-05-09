<script setup>
import { computed, inject } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  House,
  Folder,
  FolderOpened,
  VideoCamera,
  VideoCameraFilled,
  ChatDotRound,
  Memo,
  Download,
  User,
  SwitchButton
} from '@element-plus/icons-vue'
import { logOut } from '@/api/auth/auth'

const router = useRouter()
const route = useRoute()

const userInfo = inject('userInfo', { value: {} })
const isAdmin = computed(() => (userInfo.value.authorities || []).includes('ADMIN'))

const items = computed(() => {
  const base = [
    { key: 'home',      label: '首页',     icon: House,             to: '/' },
    { key: 'private',   label: '私人目录', icon: Folder,            to: '/fileServer/privateDirectory',  match: ['privateDirectory'] },
    { key: 'public',    label: '公共目录', icon: FolderOpened,      to: '/fileServer/publicDirectory',   match: ['publicDirectory'] },
    { key: 'videoRoom', label: '放映室',   icon: VideoCamera,       to: '/fileServer/videoRoom',         match: ['videoRoom'] },
    { key: 'bot',       label: '机器人',   icon: ChatDotRound,      to: '/bot' },
    { key: 'learn',     label: '学习',     icon: Memo,              to: '/languageLearn' }
  ]
  const adminOnly = [
    { key: 'torrent',   label: '磁力下载', icon: Download,          to: '/fileServer/torrentManagement', match: ['torrentManagement'] },
    { key: 'transcode', label: '转码管理', icon: VideoCameraFilled, to: '/fileServer/videoTranscodeManagement', match: ['videoTranscodeManagement'] },
    { key: 'users',     label: '用户管理', icon: User,              to: '/userManagement' }
  ]
  return isAdmin.value ? [...base, ...adminOnly] : base
})

const isActive = (item) => {
  const p = route.path
  if (item.to === '/') return p === '/'
  if (item.match) return item.match.some(m => p.includes(m))
  return p.startsWith(item.to)
}

const goTo = (item) => router.push(item.to)

const handleLogout = () => {
  logOut()
  router.push('/login')
}
</script>

<template>
  <aside class="side-nav">
    <div class="side-nav-brand" @click="router.push('/')">
      <span class="side-nav-logo" aria-hidden="true">M</span>
      <span class="side-nav-brand-name">misu</span>
    </div>

    <nav class="side-nav-menu" aria-label="主导航">
      <button
          v-for="item in items"
          :key="item.key"
          class="side-nav-item"
          :class="{ active: isActive(item) }"
          @click="goTo(item)">
        <component :is="item.icon" class="side-nav-item-icon" />
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="side-nav-foot">
      <button class="side-nav-item" @click="handleLogout">
        <SwitchButton class="side-nav-item-icon" />
        <span>退出登录</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.side-nav {
  display: flex;
  flex-direction: column;
  width: var(--layout-side-nav-width);
  background: var(--color-bg-surface);
  border-right: 1px solid var(--color-border-subtle);
  padding: var(--space-5) var(--space-3) var(--space-4);
  flex-shrink: 0;
}

.side-nav-brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-5);
  cursor: pointer;
  user-select: none;
}

.side-nav-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-lg);
  letter-spacing: -0.02em;
}

.side-nav-brand-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}

.side-nav-menu {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1 1 auto;
}

.side-nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  height: 38px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  text-align: left;
  background: transparent;
  transition:
      background var(--duration-fast) var(--ease-standard),
      color var(--duration-fast) var(--ease-standard);
}

.side-nav-item:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.side-nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: var(--font-weight-medium);
}

.side-nav-item-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.side-nav-foot {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-border-subtle);
}
</style>
