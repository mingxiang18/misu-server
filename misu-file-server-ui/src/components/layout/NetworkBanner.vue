<script setup>
import { computed } from 'vue'
import { useNetwork } from '@/composables/useNetwork'

const { isOffline, isBackendDown, showBanner } = useNetwork()

const message = computed(() => {
  if (isOffline.value) return '网络已断开，请检查网络连接'
  if (isBackendDown.value) return '后端连接异常，正在尝试恢复'
  return ''
})
</script>

<template>
  <Transition name="banner-slide">
    <div v-if="showBanner" class="network-banner" role="alert" aria-live="polite">
      <span class="dot" />
      <span class="text">{{ message }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.network-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: var(--z-toast);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  padding-top: max(var(--space-2), env(safe-area-inset-top));
  background: var(--color-warning);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  text-align: center;
  box-shadow: var(--shadow-md);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-pill);
  background: #fff;
  animation: pulse 1.4s var(--ease-standard) infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.banner-slide-enter-active,
.banner-slide-leave-active {
  transition: transform var(--duration-base) var(--ease-standard),
              opacity var(--duration-base) var(--ease-standard);
}

.banner-slide-enter-from,
.banner-slide-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
