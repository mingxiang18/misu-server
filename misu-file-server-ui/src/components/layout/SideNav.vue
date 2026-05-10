<script setup>
import { ref, computed, watch, inject } from 'vue'
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
  Delete,
  Share,
  User,
  ArrowRight,
  SwitchButton
} from '@element-plus/icons-vue'
import { logOut } from '@/api/auth/auth'

const router = useRouter()
const route = useRoute()

const userInfo = inject('userInfo', { value: {} })
const isAdmin = computed(() => (userInfo.value.authorities || []).includes('ADMIN'))

const nav = computed(() => {
  const filesChildren = [
    { key: 'private',  label: '私人目录', icon: Folder,            to: '/fileServer/privateDirectory',         match: ['privateDirectory'] },
    { key: 'public',   label: '公共目录', icon: FolderOpened,      to: '/fileServer/publicDirectory',          match: ['publicDirectory'] },
    { key: 'trash',    label: '回收站',   icon: Delete,            to: '/fileServer/trash',                    match: ['fileServer/trash'] },
    { key: 'shares',   label: '我的分享', icon: Share,             to: '/fileServer/shares',                   match: ['fileServer/shares'] },
    { key: 'torrent',  label: '磁力下载', icon: Download,          to: '/fileServer/torrentManagement',        match: ['torrentManagement'] }
  ]
  if (isAdmin.value) {
    filesChildren.push({
      key: 'transcode', label: '转码管理', icon: VideoCameraFilled, to: '/fileServer/videoTranscodeManagement', match: ['videoTranscodeManagement']
    })
  }

  const items = [
    { key: 'home',      label: '首页',     icon: House,        to: '/' },
    { key: 'files',     label: '文件管理', icon: Folder,       children: filesChildren },
    { key: 'videoRoom', label: '放映室',   icon: VideoCamera,  to: '/fileServer/videoRoom', match: ['videoRoom'] },
    { key: 'bot',       label: '机器人',   icon: ChatDotRound, to: '/bot' },
    { key: 'learn',     label: '学习',     icon: Memo,         to: '/languageLearn' }
  ]
  if (isAdmin.value) {
    items.push({ key: 'users', label: '用户管理', icon: User, to: '/userManagement' })
  }
  return items
})

const isLeafActive = (item) => {
  const p = route.path
  if (item.to === '/') return p === '/'
  if (item.match) return item.match.some(m => p.includes(m))
  return p.startsWith(item.to)
}

const isGroupActive = (group) => (group.children || []).some(isLeafActive)

const expanded = ref({})
watch(
    () => route.path,
    () => {
      nav.value.forEach(item => {
        if (item.children && isGroupActive(item)) {
          expanded.value[item.key] = true
        }
      })
    },
    { immediate: true }
)

const toggleGroup = (key) => {
  expanded.value[key] = !expanded.value[key]
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
      <template v-for="item in nav" :key="item.key">
        <!-- Leaf item -->
        <button
            v-if="!item.children"
            class="side-nav-item"
            :class="{ active: isLeafActive(item) }"
            @click="goTo(item)">
          <component :is="item.icon" class="side-nav-item-icon" />
          <span class="side-nav-item-label">{{ item.label }}</span>
        </button>

        <!-- Expandable group -->
        <div v-else class="side-nav-group" :class="{ open: expanded[item.key] }">
          <button
              class="side-nav-item"
              :class="{
                'group-trigger': true,
                'has-active-child': isGroupActive(item) && !expanded[item.key]
              }"
              @click="toggleGroup(item.key)">
            <component :is="item.icon" class="side-nav-item-icon" />
            <span class="side-nav-item-label">{{ item.label }}</span>
            <ArrowRight class="side-nav-item-chevron" />
          </button>

          <div v-show="expanded[item.key]" class="side-nav-children">
            <button
                v-for="child in item.children"
                :key="child.key"
                class="side-nav-subitem"
                :class="{ active: isLeafActive(child) }"
                @click="goTo(child)">
              <span class="side-nav-subitem-rail" aria-hidden="true"></span>
              <span class="side-nav-subitem-label">{{ child.label }}</span>
            </button>
          </div>
        </div>
      </template>
    </nav>

    <div class="side-nav-foot">
      <button class="side-nav-item" @click="handleLogout">
        <SwitchButton class="side-nav-item-icon" />
        <span class="side-nav-item-label">退出登录</span>
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

/* ---------- Leaf / Group trigger ---------- */
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

/* Collapsed group whose child is active gets a soft accent text hint */
.side-nav-item.has-active-child {
  color: var(--accent);
  font-weight: var(--font-weight-medium);
}

.side-nav-item-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.side-nav-item-label {
  flex: 1 1 auto;
  min-width: 0;
}

.side-nav-item-chevron {
  width: 14px;
  height: 14px;
  color: var(--color-text-tertiary);
  transition: transform var(--duration-base) var(--ease-standard);
}

.side-nav-group.open .side-nav-item-chevron {
  transform: rotate(90deg);
}

/* ---------- Children ---------- */
.side-nav-children {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: var(--space-1) 0 var(--space-2);
  padding-left: 28px; /* aligns with leaf icon */
  position: relative;
}

.side-nav-subitem {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  height: 32px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  text-align: left;
  background: transparent;
  position: relative;
  transition:
      background var(--duration-fast) var(--ease-standard),
      color var(--duration-fast) var(--ease-standard);
}

.side-nav-subitem-rail {
  display: inline-block;
  width: 4px;
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-border-default);
  flex-shrink: 0;
  transition: background var(--duration-fast) var(--ease-standard),
              transform var(--duration-fast) var(--ease-standard);
}

.side-nav-subitem:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.side-nav-subitem:hover .side-nav-subitem-rail {
  background: var(--color-border-strong);
}

.side-nav-subitem.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: var(--font-weight-medium);
}

.side-nav-subitem.active .side-nav-subitem-rail {
  background: var(--accent);
  transform: scale(1.4);
}

.side-nav-subitem-label {
  flex: 1 1 auto;
  min-width: 0;
}

/* ---------- Foot ---------- */
.side-nav-foot {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-border-subtle);
}
</style>
