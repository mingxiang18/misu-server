<script setup>
import { computed } from 'vue'
import { Sunny, Moon, Monitor, Close } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

defineProps({
  variant: { type: String, default: 'compact' }   // 'compact' | 'segmented'
})

const { pref, setPref, autoSwitchHint, dismissHint } = useTheme()

const options = [
  { value: 'light',  label: '浅色',     icon: Sunny },
  { value: 'dark',   label: '深色',     icon: Moon },
  { value: 'system', label: '跟随系统', icon: Monitor }
]

const current = computed(() => options.find(o => o.value === pref.value) || options[2])
</script>

<template>
  <!-- 紧凑：单图标 + 下拉（用于桌面 PageHeader）。
       autoSwitchHint=true 时旁边定位"已自动切换到深色"小角标。 -->
  <div v-if="variant === 'compact'" class="theme-switcher-wrap">
    <el-dropdown trigger="click" placement="bottom-end">
      <button
          class="theme-trigger"
          :class="{ 'has-hint': autoSwitchHint }"
          type="button"
          :aria-label="`切换主题，当前：${current.label}`">
        <component :is="current.icon" class="theme-trigger-icon" />
        <span v-if="autoSwitchHint" class="hint-dot" aria-hidden="true"></span>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
              v-for="o in options"
              :key="o.value"
              @click="setPref(o.value)">
            <span class="opt" :class="{ 'opt-active': pref === o.value }">
              <component :is="o.icon" class="opt-icon" />
              <span class="opt-label">{{ o.label }}</span>
              <span v-if="pref === o.value" class="opt-check" aria-hidden="true">✓</span>
            </span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <transition name="hint-fade">
      <div
          v-if="autoSwitchHint"
          class="auto-switch-hint"
          role="status">
        <span class="hint-arrow" aria-hidden="true"></span>
        <span class="hint-text">已自动切换到深色 · 这里可以切回</span>
        <button
            type="button"
            class="hint-close"
            aria-label="关闭提示"
            @click.stop="dismissHint">
          <Close />
        </button>
      </div>
    </transition>
  </div>

  <!-- 段控：三档并排（用于移动端"更多" sheet）。
       autoSwitchHint=true 时在段控上方加 hint banner，说明是自动切换的。 -->
  <div v-else class="theme-segmented-wrap">
    <transition name="hint-fade">
      <div v-if="autoSwitchHint" class="seg-hint-banner" role="status">
        <span class="seg-hint-text">已自动切换到深色，可点这里切回</span>
        <button
            type="button"
            class="seg-hint-close"
            aria-label="关闭提示"
            @click.stop="dismissHint">
          <Close />
        </button>
      </div>
    </transition>
    <div class="theme-segmented" role="group" aria-label="主题">
      <button
          v-for="o in options"
          :key="o.value"
          type="button"
          class="seg-item"
          :class="{ 'seg-active': pref === o.value }"
          @click="setPref(o.value)">
        <component :is="o.icon" class="seg-icon" />
        <span class="seg-label">{{ o.label }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* ---------- compact (icon-only trigger) ---------- */
.theme-switcher-wrap {
  position: relative;
  display: inline-flex;
}
.theme-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard);
}
.theme-trigger:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.theme-trigger.has-hint {
  color: var(--accent);
}
.theme-trigger-icon {
  width: 18px;
  height: 18px;
}
.hint-dot {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 8px 2px rgba(139, 157, 240, 0.45);
}

/* ---------- 自动切换提示角标（定位在按钮下方，箭头指向按钮） ---------- */
.auto-switch-hint {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2) var(--space-2) var(--space-3);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  white-space: nowrap;
  z-index: var(--z-overlay);
}
.hint-arrow {
  position: absolute;
  top: -5px;
  right: 13px;
  width: 9px;
  height: 9px;
  background: var(--color-bg-surface);
  border-left: 1px solid var(--color-border-default);
  border-top: 1px solid var(--color-border-default);
  transform: rotate(45deg);
}
.hint-text {
  letter-spacing: 0.1px;
}
.hint-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard);
}
.hint-close :deep(svg) {
  width: 14px;
  height: 14px;
}
.hint-close:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

/* 进入/退出动画 */
.hint-fade-enter-active,
.hint-fade-leave-active {
  transition: opacity 180ms var(--ease-standard),
              transform 180ms var(--ease-standard);
}
.hint-fade-enter-from,
.hint-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 移动端：badge 可能超出右边界，限宽 + 缩字 */
@media (max-width: 640px) {
  .auto-switch-hint {
    max-width: calc(100vw - 24px);
    white-space: normal;
    line-height: 1.4;
    font-size: var(--font-size-xs);
  }
}

/* ---------- dropdown option ---------- */
.opt {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 128px;
  color: var(--color-text-primary);
}
.opt-icon {
  width: 16px;
  height: 16px;
  color: var(--color-text-secondary);
}
.opt-active .opt-icon,
.opt-active .opt-label {
  color: var(--accent);
}
.opt-label {
  flex: 1;
}
.opt-check {
  margin-left: var(--space-2);
  color: var(--accent);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-sm);
}

/* ---------- segmented (mobile sheet) ---------- */
.theme-segmented-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.seg-hint-banner {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2) var(--space-2) var(--space-3);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
  line-height: 1.4;
}
.seg-hint-text {
  flex: 1;
  letter-spacing: 0.1px;
}
.seg-hint-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
  cursor: pointer;
  flex-shrink: 0;
}
.seg-hint-close :deep(svg) {
  width: 14px;
  height: 14px;
}
.theme-segmented {
  display: flex;
  gap: var(--space-1);
  padding: var(--space-1);
  background: var(--color-bg-muted);
  border-radius: var(--radius-md);
}
.seg-item {
  flex: 1 1 0;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: var(--space-2) var(--space-1);
  background: transparent;
  border-radius: calc(var(--radius-md) - 2px);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard),
              box-shadow var(--duration-fast) var(--ease-standard);
}
.seg-item:active:not(.seg-active) {
  background: var(--color-bg-hover);
}
.seg-active {
  background: var(--color-bg-surface);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}
.seg-icon {
  width: 18px;
  height: 18px;
}
.seg-label {
  line-height: 1;
}
</style>
