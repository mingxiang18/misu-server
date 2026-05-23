<script setup>
import { computed } from 'vue'
import { Plus, Search, User } from '@element-plus/icons-vue'
import ChatAvatar from './ChatAvatar.vue'
import { botProfile } from './botProfile.js'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: [String, Number], default: null }
})

const emit = defineEmits(['select', 'create-group'])

const keyword = defineModel('keyword', { default: '' })

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return props.conversations
  return props.conversations.filter((c) => (c.title || '').toLowerCase().includes(k))
})

const avatarKind = (c) => (c.type === 'GROUP' ? 'group' : 'bot')
const memberCount = (c) => (c.memberCount != null ? c.memberCount : (c.members ? c.members.length : 0))

// 服务端 lastMessageAt 为 ISO 时间：今天显示 HH:mm，更早显示 月-日
const fmtTime = (v) => {
  if (!v) return ''
  const d = new Date(v)
  if (isNaN(d.getTime())) return v
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  if (sameDay) return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return `${d.getMonth() + 1}-${d.getDate()}`
}
</script>

<template>
  <div class="conv-list">
    <div class="conv-list-head">
      <h2 class="conv-list-title">消息</h2>
      <button class="conv-create-btn" type="button" @click="emit('create-group')">
        <Plus />
        <span>建群</span>
      </button>
    </div>

    <div class="conv-search">
      <Search class="conv-search-icon" />
      <input
          v-model="keyword"
          class="conv-search-input"
          type="text"
          placeholder="搜索会话" />
    </div>

    <div class="conv-scroll">
      <button
          v-for="c in filtered"
          :key="c.id"
          class="conv-item"
          :class="{ active: String(c.id) === String(activeId) }"
          type="button"
          @click="emit('select', c)">
        <ChatAvatar :name="c.title" :kind="avatarKind(c)" :avatar="c.type === 'PRIVATE' ? botProfile.avatar : ''" :size="46" />
        <div class="conv-item-body">
          <div class="conv-item-line1">
            <span class="conv-item-title">{{ c.title }}</span>
            <span class="conv-item-time">{{ fmtTime(c.lastMessageAt) }}</span>
          </div>
          <div class="conv-item-line2">
            <span class="conv-item-preview">
              <span v-if="c.type === 'GROUP' && c.lastSenderName" class="conv-item-sender">{{ c.lastSenderName }}：</span>{{ c.lastMessage }}
            </span>
            <span v-if="c.unreadCount > 0" class="conv-item-unread">{{ c.unreadCount > 99 ? '99+' : c.unreadCount }}</span>
            <span v-else-if="c.type === 'GROUP'" class="conv-item-count" title="群成员数"><User class="conv-item-count-icon" />{{ memberCount(c) }}</span>
          </div>
        </div>
      </button>

      <div v-if="filtered.length === 0" class="conv-empty">没有匹配的会话</div>
    </div>
  </div>
</template>

<style scoped>
.conv-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--color-bg-surface);
}

.conv-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-4) var(--space-3);
  flex-shrink: 0;
}

.conv-list-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}

.conv-create-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  transition: background var(--duration-fast) var(--ease-standard);
}

.conv-create-btn:hover {
  background: var(--accent);
  color: var(--color-text-on-accent);
}

.conv-create-btn :deep(svg) {
  width: 14px;
  height: 14px;
}

.conv-search {
  position: relative;
  display: flex;
  align-items: center;
  margin: 0 var(--space-4) var(--space-2);
  flex-shrink: 0;
}

.conv-search-icon {
  position: absolute;
  left: var(--space-3);
  width: 16px;
  height: 16px;
  color: var(--color-text-tertiary);
  pointer-events: none;
}

.conv-search-input {
  width: 100%;
  height: 38px;
  padding: 0 var(--space-3) 0 calc(var(--space-3) + 22px);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  font-size: var(--font-size-base);
  outline: none;
  transition: background var(--duration-fast) var(--ease-standard),
              border-color var(--duration-fast) var(--ease-standard);
}

.conv-search-input::placeholder {
  color: var(--color-text-tertiary);
}

.conv-search-input:focus {
  background: var(--color-bg-surface);
  border-color: var(--color-border-strong);
}

.conv-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-1) var(--space-2) var(--space-3);
}

.conv-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3);
  border-radius: var(--radius-lg);
  background: transparent;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-standard);
}

.conv-item:hover {
  background: var(--color-bg-hover);
}

.conv-item.active {
  background: var(--accent-soft);
}

.conv-item-body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.conv-item-line1 {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
}

.conv-item-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-item-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.conv-item-line2 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.conv-item-preview {
  flex: 1 1 auto;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-item-sender {
  color: var(--color-text-tertiary);
}

.conv-item-count {
  flex-shrink: 0;
  height: 18px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-muted);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.conv-item-count-icon {
  width: 11px;
  height: 11px;
}

.conv-item-unread {
  flex-shrink: 0;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.conv-item.active .conv-item-count {
  background: var(--color-bg-surface);
}

.conv-empty {
  padding: var(--space-8) var(--space-4);
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}
</style>
