<script setup>
import { computed } from 'vue'
import { Service, ChatDotRound } from '@element-plus/icons-vue'

const props = defineProps({
  name: { type: String, default: '' },
  avatar: { type: String, default: '' },
  // 'user' | 'bot' | 'group'
  kind: { type: String, default: 'user' },
  size: { type: Number, default: 40 }
})

// 6 套暖色调，从名字哈希稳定取色，保证同一用户每次同色
const PALETTE = [
  { bg: '#FBE7DA', fg: '#C2410C' },
  { bg: '#E8EEDD', fg: '#5F7A3D' },
  { bg: '#F8E9D6', fg: '#B5651D' },
  { bg: '#E3E8F6', fg: '#4C5B92' },
  { bg: '#F4DCE4', fg: '#9B3B66' },
  { bg: '#DDEDEC', fg: '#3C7E78' }
]

const hash = (s) => {
  let h = 0
  for (let i = 0; i < (s || '').length; i++) h = (h * 31 + s.charCodeAt(i)) | 0
  return Math.abs(h)
}

const color = computed(() => PALETTE[hash(props.name) % PALETTE.length])
const initial = computed(() => (props.name ? String(props.name).trim().charAt(0) : '?').toUpperCase())
const sizePx = computed(() => props.size + 'px')
const fontPx = computed(() => Math.round(props.size * 0.42) + 'px')
const iconPx = computed(() => Math.round(props.size * 0.5) + 'px')
</script>

<template>
  <span
      class="bot-avatar-base"
      :class="'kind-' + kind"
      :style="{
        width: sizePx,
        height: sizePx,
        background: kind === 'user' && !avatar ? color.bg : undefined,
        color: kind === 'user' && !avatar ? color.fg : undefined
      }"
      aria-hidden="true">
    <img v-if="avatar" :src="avatar" alt="" class="bot-avatar-img" />
    <span v-else-if="kind === 'bot'" class="bot-avatar-icon" :style="{ fontSize: iconPx }"><Service /></span>
    <span v-else-if="kind === 'group'" class="bot-avatar-icon" :style="{ fontSize: iconPx }"><ChatDotRound /></span>
    <span v-else class="bot-avatar-initial" :style="{ fontSize: fontPx }">{{ initial }}</span>
  </span>
</template>

<style scoped>
.bot-avatar-base {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: var(--radius-pill);
  overflow: hidden;
  font-weight: var(--font-weight-semibold);
  user-select: none;
}

.kind-bot {
  background: linear-gradient(135deg, var(--accent) 0%, var(--accent-strong) 100%);
  color: var(--color-text-on-accent);
}

.kind-group {
  background: var(--accent-soft);
  color: var(--accent);
}

.bot-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bot-avatar-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.bot-avatar-icon :deep(svg) {
  width: 1em;
  height: 1em;
}

.bot-avatar-initial {
  line-height: 1;
}
</style>
