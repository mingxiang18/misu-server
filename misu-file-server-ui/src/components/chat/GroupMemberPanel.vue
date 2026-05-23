<script setup>
import { computed } from 'vue'
import { Plus, Close, CircleCloseFilled } from '@element-plus/icons-vue'
import ChatAvatar from './ChatAvatar.vue'
import { botProfile } from './botProfile.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  conversation: { type: Object, default: null },
  currentUser: { type: Object, required: true },
  isMobile: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'add-member', 'remove-member', 'leave'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const members = computed(() => (props.conversation && props.conversation.members) || [])
const isOwner = computed(() => props.conversation && String(props.conversation.ownerUserId) === String(props.currentUser.userId))

const label = (m) => (m.botFlag ? '冥想bb' : (m.nickName || m.userName))
const isMe = (m) => String(m.userId) === String(props.currentUser.userId)
const canRemove = (m) => isOwner.value && !m.botFlag && !isMe(m)
</script>

<template>
  <el-drawer
      v-model="visible"
      :direction="isMobile ? 'btt' : 'rtl'"
      :size="isMobile ? 'auto' : 320"
      :with-header="false"
      class="gm-drawer">
    <div class="gm">
      <div v-if="isMobile" class="gm-handle" aria-hidden="true"></div>
      <header class="gm-head">
        <h3 class="gm-title">群成员 · {{ members.length }}</h3>
        <button class="gm-close" type="button" aria-label="关闭" @click="visible = false"><Close /></button>
      </header>

      <button class="gm-add" type="button" @click="emit('add-member')">
        <span class="gm-add-icon"><Plus /></span>
        <span>添加成员</span>
      </button>

      <div class="gm-list">
        <div v-for="m in members" :key="m.userId" class="gm-item">
          <ChatAvatar :name="label(m)" :kind="m.botFlag ? 'bot' : 'user'" :avatar="m.botFlag ? botProfile.avatar : m.avatar" :size="40" />
          <div class="gm-item-meta">
            <span class="gm-item-name">
              {{ label(m) }}
              <span v-if="isMe(m)" class="gm-tag">我</span>
            </span>
            <span class="gm-item-sub">
              <span v-if="m.role === 'OWNER'" class="gm-role-owner">群主</span>
              <span v-else-if="m.botFlag">机器人</span>
              <span v-else>成员</span>
            </span>
          </div>
          <button
              v-if="canRemove(m)"
              class="gm-remove"
              type="button"
              aria-label="移出群聊"
              @click="emit('remove-member', m)">
            <CircleCloseFilled />
          </button>
        </div>
      </div>

      <button v-if="!isOwner" class="gm-leave" type="button" @click="emit('leave')">退出群聊</button>
    </div>
  </el-drawer>
</template>

<style scoped>
:deep(.gm-drawer) { background: var(--color-bg-surface); }
:deep(.gm-drawer.el-drawer.rtl) { border-top-left-radius: var(--radius-lg); border-bottom-left-radius: var(--radius-lg); }
:deep(.gm-drawer.el-drawer.btt) { border-top-left-radius: var(--radius-lg); border-top-right-radius: var(--radius-lg); }
:deep(.gm-drawer .el-drawer__body) { padding: 0; }

.gm {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: var(--space-2) var(--space-4) max(var(--space-4), env(safe-area-inset-bottom));
}

.gm-handle {
  width: 40px; height: 4px; margin: var(--space-1) auto var(--space-2);
  border-radius: var(--radius-pill); background: var(--color-border-default);
}

.gm-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-2) 0 var(--space-3);
}
.gm-title { font-size: var(--font-size-md); font-weight: var(--font-weight-semibold); color: var(--color-text-primary); }
.gm-close {
  width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); color: var(--color-text-tertiary); background: transparent;
}
.gm-close:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.gm-close :deep(svg) { width: 16px; height: 16px; }

.gm-add {
  display: flex; align-items: center; gap: var(--space-3);
  width: 100%; padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-md); background: transparent; color: var(--accent);
  font-size: var(--font-size-base);
  transition: background var(--duration-fast) var(--ease-standard);
}
.gm-add:hover { background: var(--accent-soft); }
.gm-add-icon {
  width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); border: 1px dashed var(--accent); color: var(--accent);
}
.gm-add-icon :deep(svg) { width: 18px; height: 18px; }

.gm-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; margin-top: var(--space-1); }
.gm-item {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-2);
  border-radius: var(--radius-md);
}
.gm-item:hover { background: var(--color-bg-hover); }
.gm-item-meta { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.gm-item-name { display: flex; align-items: center; gap: var(--space-1); font-size: var(--font-size-base); color: var(--color-text-primary); }
.gm-tag {
  padding: 0 5px; height: 16px; display: inline-flex; align-items: center;
  border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-tertiary); font-size: 10px;
}
.gm-item-sub { font-size: var(--font-size-xs); color: var(--color-text-tertiary); }
.gm-role-owner { color: var(--accent); }
.gm-remove {
  flex-shrink: 0; width: 28px; height: 28px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); background: transparent; color: var(--color-text-tertiary);
  transition: color var(--duration-fast) var(--ease-standard), background var(--duration-fast) var(--ease-standard);
}
.gm-remove:hover { color: var(--color-danger); background: var(--color-danger-soft); }
.gm-remove :deep(svg) { width: 18px; height: 18px; }

.gm-leave {
  margin-top: var(--space-3);
  height: 40px;
  border-radius: var(--radius-md);
  background: var(--color-danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
}
</style>
