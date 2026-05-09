<script setup>
import { ref, computed, inject } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  House,
  Folder,
  VideoCamera,
  VideoCameraFilled,
  ChatDotRound,
  Memo,
  FolderOpened,
  Download,
  User,
  Menu as IconMenu,
  SwitchButton
} from '@element-plus/icons-vue'
import { logOut } from '@/api/auth/auth'

const router = useRouter()
const route = useRoute()

const userInfo = inject('userInfo', { value: {} })
const isAdmin = computed(() => (userInfo.value.authorities || []).includes('ADMIN'))

const moreOpen = ref(false)

const tabs = [
  { key: 'home',      label: '首页',     icon: House,        to: '/' },
  { key: 'files',     label: '文件',     icon: Folder,       to: '/fileServer/privateDirectory', match: ['privateDirectory', 'publicDirectory'] },
  { key: 'videoRoom', label: '放映室',   icon: VideoCamera,  to: '/fileServer/videoRoom',        match: ['videoRoom'] },
  { key: 'bot',       label: '机器人',   icon: ChatDotRound, to: '/bot' }
]

const moreItems = computed(() => {
  const base = [
    { key: 'learn',  label: '语言学习', icon: Memo,         to: '/languageLearn' },
    { key: 'public', label: '公共目录', icon: FolderOpened, to: '/fileServer/publicDirectory' }
  ]
  const adminOnly = [
    { key: 'torrent',   label: '磁力下载', icon: Download,          to: '/fileServer/torrentManagement' },
    { key: 'transcode', label: '转码管理', icon: VideoCameraFilled, to: '/fileServer/videoTranscodeManagement' },
    { key: 'users',     label: '用户管理', icon: User,              to: '/userManagement' }
  ]
  return isAdmin.value ? [...base, ...adminOnly] : base
})

const isActive = (tab) => {
  const p = route.path
  if (tab.to === '/') return p === '/'
  if (tab.match) return tab.match.some(m => p.includes(m))
  return p.startsWith(tab.to)
}

const goTo = (to) => {
  router.push(to)
  moreOpen.value = false
}

const handleLogout = () => {
  moreOpen.value = false
  logOut()
  router.push('/login')
}
</script>

<template>
  <nav class="tab-bar" aria-label="底部导航">
    <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-bar-item"
        :class="{ active: isActive(tab) }"
        @click="goTo(tab.to)">
      <component :is="tab.icon" class="tab-bar-icon" />
      <span class="tab-bar-label">{{ tab.label }}</span>
    </button>
    <button
        class="tab-bar-item"
        :class="{ active: moreOpen }"
        @click="moreOpen = true">
      <IconMenu class="tab-bar-icon" />
      <span class="tab-bar-label">更多</span>
    </button>
  </nav>

  <el-drawer
      v-model="moreOpen"
      direction="btt"
      size="auto"
      :with-header="false"
      class="more-drawer">
    <div class="more-sheet">
      <div class="more-sheet-handle" aria-hidden="true"></div>
      <h3 class="more-sheet-title">更多功能</h3>

      <div class="more-grid">
        <button
            v-for="m in moreItems"
            :key="m.key"
            class="more-grid-item"
            @click="goTo(m.to)">
          <span class="more-grid-icon">
            <component :is="m.icon" />
          </span>
          <span class="more-grid-label">{{ m.label }}</span>
        </button>
      </div>

      <div class="more-divider"></div>

      <button class="more-row danger" @click="handleLogout">
        <span class="more-row-icon">
          <SwitchButton />
        </span>
        <span>退出登录</span>
      </button>
    </div>
  </el-drawer>
</template>

<style scoped>
.tab-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  height: calc(var(--layout-tab-bar-height) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--color-bg-surface);
  border-top: 1px solid var(--color-border-subtle);
  z-index: var(--z-sticky);
}

.tab-bar-item {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: var(--space-1) var(--space-2);
  color: var(--color-text-tertiary);
  background: transparent;
  transition: color var(--duration-fast) var(--ease-standard);
}

.tab-bar-item:active {
  background: var(--color-bg-hover);
}

.tab-bar-item.active {
  color: var(--accent);
}

.tab-bar-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
}

.tab-bar-label {
  font-size: 11px;
  line-height: 1;
}

/* ---------- Drawer "更多" sheet ---------- */
:deep(.more-drawer) {
  border-top-left-radius: var(--radius-lg);
  border-top-right-radius: var(--radius-lg);
  background: var(--color-bg-surface);
}

:deep(.more-drawer .el-drawer__body) {
  padding: 0;
}

.more-sheet {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4) max(var(--space-4), env(safe-area-inset-bottom));
}

.more-sheet-handle {
  width: 40px;
  height: 4px;
  margin: var(--space-1) auto var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--color-border-default);
}

.more-sheet-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  padding: 0 var(--space-2);
}

.more-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-2);
}

.more-grid-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-1);
  background: transparent;
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
  transition: background var(--duration-fast) var(--ease-standard);
}

.more-grid-item:active {
  background: var(--color-bg-hover);
}

.more-grid-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  border-radius: var(--radius-md);
}

.more-grid-icon :deep(svg) {
  width: 20px;
  height: 20px;
}

.more-grid-label {
  text-align: center;
  line-height: var(--line-height-tight);
}

.more-divider {
  height: 1px;
  background: var(--color-border-subtle);
  margin: var(--space-1) 0;
}

.more-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: transparent;
  font-size: var(--font-size-base);
  color: var(--color-text-primary);
  transition: background var(--duration-fast) var(--ease-standard);
}

.more-row:active {
  background: var(--color-bg-hover);
}

.more-row.danger {
  color: var(--color-danger);
}

.more-row-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.more-row-icon :deep(svg) {
  width: 18px;
  height: 18px;
}
</style>
