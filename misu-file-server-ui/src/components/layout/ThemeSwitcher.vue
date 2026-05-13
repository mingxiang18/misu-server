<script setup>
import { computed } from 'vue'
import { Sunny, Moon, Monitor } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

defineProps({
  variant: { type: String, default: 'compact' }   // 'compact' | 'segmented'
})

const { pref, setPref } = useTheme()

const options = [
  { value: 'light',  label: '浅色',     icon: Sunny },
  { value: 'dark',   label: '深色',     icon: Moon },
  { value: 'system', label: '跟随系统', icon: Monitor }
]

const current = computed(() => options.find(o => o.value === pref.value) || options[2])
</script>

<template>
  <!-- 紧凑：单图标 + 下拉（用于桌面 PageHeader） -->
  <el-dropdown v-if="variant === 'compact'" trigger="click" placement="bottom-end">
    <button
        class="theme-trigger"
        type="button"
        :aria-label="`切换主题，当前：${current.label}`">
      <component :is="current.icon" class="theme-trigger-icon" />
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

  <!-- 段控：三档并排（用于移动端"更多" sheet） -->
  <div v-else class="theme-segmented" role="group" aria-label="主题">
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
</template>

<style scoped>
/* ---------- compact (icon-only trigger) ---------- */
.theme-trigger {
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
.theme-trigger-icon {
  width: 18px;
  height: 18px;
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
