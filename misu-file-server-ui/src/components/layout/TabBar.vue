<script setup>
import { ref, computed, inject } from 'vue'
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
  Delete,
  Share,
  Histogram,
  Files,
  Box,
  Menu as IconMenu,
  SwitchButton
} from '@element-plus/icons-vue'
import { Close } from '@element-plus/icons-vue'
import { logOut } from '@/api/auth/auth'
import ThemeSwitcher from '@/components/layout/ThemeSwitcher.vue'
import { useTheme } from '@/composables/useTheme'

const { autoSwitchHint, dismissHint } = useTheme()

const router = useRouter()
const route = useRoute()

const userInfo = inject('userInfo', { value: {} })
const isAdmin = computed(() => (userInfo.value.authorities || []).includes('ADMIN'))

const filesOpen = ref(false)
const moreOpen = ref(false)

const filesItems = computed(() => {
  const base = [
    { key: 'private', label: '私人目录', icon: Folder,            to: '/fileServer/privateDirectory' },
    { key: 'public',  label: '公共目录', icon: FolderOpened,      to: '/fileServer/publicDirectory' },
    { key: 'torrent', label: '磁力下载', icon: Download,          to: '/fileServer/torrentManagement' },
    { key: 'trash',   label: '回收站',   icon: Delete,            to: '/fileServer/trash' },
    { key: 'shares',  label: '我的分享', icon: Share,             to: '/fileServer/shares' }
  ]
  if (isAdmin.value) {
    base.push({ key: 'transcode',  label: '转码管理',     icon: VideoCameraFilled, to: '/fileServer/videoTranscodeManagement' })
    base.push({ key: 'audit',      label: '审计日志',     icon: Histogram,         to: '/fileServer/audit' })
    base.push({ key: 'userDir',    label: '用户目录浏览', icon: Files,             to: '/admin/userDirectory' })
    base.push({ key: 'staging',    label: '预置目录',     icon: Box,               to: '/admin/staging' })
  }
  return base
})

const moreItems = computed(() => {
  const base = [
    { key: 'learn', label: '语言学习', icon: Memo, to: '/languageLearn' }
  ]
  if (isAdmin.value) {
    base.push({ key: 'users', label: '用户管理', icon: User, to: '/userManagement' })
  }
  return base
})

const filesActive = computed(() => {
  const p = route.path
  return ['privateDirectory', 'publicDirectory', 'torrentManagement', 'videoTranscodeManagement',
          'fileServer/trash', 'fileServer/shares', 'fileServer/audit',
          'admin/userDirectory', 'admin/staging']
      .some(m => p.includes(m))
})

const tabs = [
  { key: 'home',      label: '首页',     icon: House,        to: '/' },
  { key: 'videoRoom', label: '放映室',   icon: VideoCamera,  to: '/fileServer/videoRoom',  match: ['videoRoom'] },
  { key: 'bot',       label: '聊天',     icon: ChatDotRound, to: '/chat' }
]

const isLeafActive = (tab) => {
  const p = route.path
  if (tab.to === '/') return p === '/'
  if (tab.match) return tab.match.some(m => p.includes(m))
  return p.startsWith(tab.to)
}

const goTo = (to) => {
  router.push(to)
  filesOpen.value = false
  moreOpen.value = false
}

const handleLogout = () => {
  filesOpen.value = false
  moreOpen.value = false
  logOut()
  router.push('/login')
}
</script>

<template>
  <!-- 自动切换深色提示：TabBar 上方悬浮气泡，箭头指向"更多" -->
  <transition name="tab-hint-fade">
    <div
        v-if="autoSwitchHint"
        class="tab-auto-hint"
        role="status">
      <button
          type="button"
          class="tab-auto-hint-main"
          @click="dismissHint(); moreOpen = true">
        <span class="tab-auto-hint-text">已自动切换到深色 · 点"更多 → 外观"可切回</span>
      </button>
      <button
          type="button"
          class="tab-auto-hint-close"
          aria-label="关闭提示"
          @click.stop="dismissHint">
        <Close />
      </button>
      <span class="tab-auto-hint-arrow" aria-hidden="true"></span>
    </div>
  </transition>

  <nav class="tab-bar" aria-label="底部导航">
    <button
        class="tab-bar-item"
        :class="{ active: isLeafActive(tabs[0]) }"
        @click="goTo(tabs[0].to)">
      <House class="tab-bar-icon" />
      <span class="tab-bar-label">{{ tabs[0].label }}</span>
    </button>

    <!-- 文件 = 二级 sheet 触发 -->
    <button
        class="tab-bar-item"
        :class="{ active: filesActive || filesOpen }"
        @click="filesOpen = true">
      <Folder class="tab-bar-icon" />
      <span class="tab-bar-label">文件</span>
    </button>

    <button
        class="tab-bar-item"
        :class="{ active: isLeafActive(tabs[1]) }"
        @click="goTo(tabs[1].to)">
      <VideoCamera class="tab-bar-icon" />
      <span class="tab-bar-label">{{ tabs[1].label }}</span>
    </button>

    <button
        class="tab-bar-item"
        :class="{ active: isLeafActive(tabs[2]) }"
        @click="goTo(tabs[2].to)">
      <ChatDotRound class="tab-bar-icon" />
      <span class="tab-bar-label">{{ tabs[2].label }}</span>
    </button>

    <button
        class="tab-bar-item"
        :class="{ active: moreOpen }"
        @click="moreOpen = true">
      <span class="tab-bar-icon-wrap">
        <IconMenu class="tab-bar-icon" />
        <span v-if="autoSwitchHint" class="tab-hint-dot" aria-hidden="true"></span>
      </span>
      <span class="tab-bar-label">更多</span>
    </button>
  </nav>

  <!-- 文件管理 sheet -->
  <el-drawer
      v-model="filesOpen"
      direction="btt"
      size="auto"
      :with-header="false"
      class="bottom-sheet">
    <div class="sheet">
      <div class="sheet-handle" aria-hidden="true"></div>
      <h3 class="sheet-title">文件管理</h3>
      <div class="sheet-grid">
        <button
            v-for="m in filesItems"
            :key="m.key"
            class="sheet-grid-item"
            @click="goTo(m.to)">
          <span class="sheet-grid-icon">
            <component :is="m.icon" />
          </span>
          <span class="sheet-grid-label">{{ m.label }}</span>
        </button>
      </div>
    </div>
  </el-drawer>

  <!-- 更多 sheet -->
  <el-drawer
      v-model="moreOpen"
      direction="btt"
      size="auto"
      :with-header="false"
      class="bottom-sheet">
    <div class="sheet">
      <div class="sheet-handle" aria-hidden="true"></div>
      <h3 class="sheet-title">更多功能</h3>
      <div class="sheet-grid">
        <button
            v-for="m in moreItems"
            :key="m.key"
            class="sheet-grid-item"
            @click="goTo(m.to)">
          <span class="sheet-grid-icon">
            <component :is="m.icon" />
          </span>
          <span class="sheet-grid-label">{{ m.label }}</span>
        </button>
      </div>

      <div class="sheet-divider"></div>
      <div class="sheet-section">
        <div class="sheet-section-label">外观</div>
        <ThemeSwitcher variant="segmented" />
      </div>

      <div class="sheet-divider"></div>
      <button class="sheet-row danger" @click="handleLogout">
        <span class="sheet-row-icon">
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
  background: var(--nav-bg);
  backdrop-filter: var(--nav-backdrop);
  -webkit-backdrop-filter: var(--nav-backdrop);
  border-top: 1px solid var(--nav-border);
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

.tab-bar-icon-wrap {
  position: relative;
  display: inline-flex;
}
.tab-bar-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
}
.tab-hint-dot {
  position: absolute;
  top: -2px;
  right: -3px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 8px 2px rgba(139, 157, 240, 0.55);
}

/* 自动切换深色 · TabBar 上方悬浮气泡（箭头指向"更多"按钮） */
.tab-auto-hint {
  position: fixed;
  right: 8px;
  bottom: calc(var(--layout-tab-bar-height) + env(safe-area-inset-bottom) + 12px);
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-1) 0 var(--space-3);
  background: var(--color-bg-surface);
  border: 1px solid var(--accent);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md), 0 0 24px -4px rgba(139, 157, 240, 0.35);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  z-index: var(--z-overlay);
  max-width: calc(100vw - 16px);
}
.tab-auto-hint-main {
  flex: 1 1 auto;
  display: inline-flex;
  align-items: center;
  padding: var(--space-2) 0;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: inherit;
  color: inherit;
  text-align: left;
}
.tab-auto-hint-text {
  line-height: 1.4;
  word-break: break-all;
}
.tab-auto-hint-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
  cursor: pointer;
  flex-shrink: 0;
}
.tab-auto-hint-close :deep(svg) {
  width: 14px;
  height: 14px;
}
/* 箭头：贴底，指向"更多"按钮（最右 tab item 中心，约距右边 10vw） */
.tab-auto-hint-arrow {
  position: absolute;
  bottom: -6px;
  right: calc(10vw - 5px);
  width: 10px;
  height: 10px;
  background: var(--color-bg-surface);
  border-right: 1px solid var(--accent);
  border-bottom: 1px solid var(--accent);
  transform: rotate(45deg);
}
.tab-hint-fade-enter-active,
.tab-hint-fade-leave-active {
  transition: opacity 180ms var(--ease-standard),
              transform 180ms var(--ease-standard);
}
.tab-hint-fade-enter-from,
.tab-hint-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

.tab-bar-label {
  font-size: 11px;
  line-height: 1;
}

/* ---------- Bottom sheet (shared) ---------- */
:deep(.bottom-sheet) {
  border-top-left-radius: var(--radius-lg);
  border-top-right-radius: var(--radius-lg);
  background: var(--color-bg-surface);
}

:deep(.bottom-sheet .el-drawer__body) {
  padding: 0;
}

.sheet {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4) max(var(--space-4), env(safe-area-inset-bottom));
}

.sheet-handle {
  width: 40px;
  height: 4px;
  margin: var(--space-1) auto var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--color-border-default);
}

.sheet-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  padding: 0 var(--space-2);
}

.sheet-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-2);
}

.sheet-grid-item {
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

.sheet-grid-item:active {
  background: var(--color-bg-hover);
}

.sheet-grid-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  border-radius: var(--radius-md);
}

.sheet-grid-icon :deep(svg) {
  width: 20px;
  height: 20px;
}

.sheet-grid-label {
  text-align: center;
  line-height: var(--line-height-tight);
}

.sheet-divider {
  height: 1px;
  background: var(--color-border-subtle);
  margin: var(--space-1) 0;
}

.sheet-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: 0 var(--space-2);
}

.sheet-section-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  letter-spacing: 0.5px;
}

.sheet-row {
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

.sheet-row:active {
  background: var(--color-bg-hover);
}

.sheet-row.danger {
  color: var(--color-danger);
}

.sheet-row-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.sheet-row-icon :deep(svg) {
  width: 18px;
  height: 18px;
}
</style>
