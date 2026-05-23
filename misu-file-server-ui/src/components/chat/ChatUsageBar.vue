<script setup>
import { ref, computed, watch } from 'vue'
import { Coin, ArrowDown, RefreshRight } from '@element-plus/icons-vue'
import { getUsage } from '@/api/chat/chat'
import logger from '@/utils/logger'

const props = defineProps({
  conversationId: { type: [String, Number], default: null }
})

const loading = ref(false)
const error = ref(false)
const expanded = ref(false)
const usage = ref(null)

const load = async () => {
  loading.value = true
  error.value = false
  try {
    const res = await getUsage()
    const d = res.data || {}
    if (d.available === false) { error.value = true; usage.value = null }
    else usage.value = d
  } catch (err) {
    logger.error('加载用量失败:', err)
    error.value = true
    usage.value = null
  } finally {
    loading.value = false
  }
}

watch(() => props.conversationId, (id) => { if (id != null) load() }, { immediate: true })

const num = (v) => (v == null || isNaN(Number(v)) ? 0 : Number(v))
const pct = computed(() => {
  const limit = num(usage.value && usage.value.limitCny)
  if (limit <= 0) return 0
  return Math.min(100, Math.round((num(usage.value && usage.value.spentCny) / limit) * 100))
})
const level = computed(() => (pct.value >= 100 ? 'danger' : pct.value >= 80 ? 'warning' : 'normal'))
const isEmpty = computed(() => usage.value && num(usage.value.spentCny) === 0 && num(usage.value.totalTokens) === 0)

const cny = (v) => '¥' + num(v).toFixed(2)
const tk = (n) => {
  const v = num(n)
  if (v >= 10000) return (v / 10000).toFixed(1) + 'w'
  if (v >= 1000) return (v / 1000).toFixed(1) + 'k'
  return String(Math.round(v))
}
const toggle = () => { if (usage.value) expanded.value = !expanded.value }
</script>

<template>
  <div class="usage-bar" :class="level">
    <!-- loading -->
    <div v-if="loading" class="usage-row usage-muted">
      <el-icon class="usage-ico"><Coin /></el-icon>
      <span class="usage-label">本月 AI 用量</span>
      <div class="usage-track skeleton"><div class="usage-fill shimmer"></div></div>
      <span class="usage-amt">加载中…</span>
    </div>

    <!-- error / 降级 -->
    <div v-else-if="error" class="usage-row usage-muted">
      <el-icon class="usage-ico"><Coin /></el-icon>
      <span class="usage-label">用量暂不可用</span>
      <span class="usage-spacer" />
      <button class="usage-retry" type="button" @click="load"><el-icon><RefreshRight /></el-icon>重试</button>
    </div>

    <!-- normal -->
    <template v-else-if="usage">
      <button class="usage-row" type="button" @click="toggle">
        <el-icon class="usage-ico"><Coin /></el-icon>
        <span class="usage-label">本月 AI 用量</span>
        <div class="usage-track"><div class="usage-fill" :style="{ width: pct + '%' }"></div></div>
        <span class="usage-amt">
          <template v-if="isEmpty">本月暂无用量</template>
          <template v-else>{{ cny(usage.spentCny) }} / {{ cny(usage.limitCny) }} · 剩余 {{ cny(usage.remainingCny) }}</template>
        </span>
        <el-icon class="usage-chev" :class="{ open: expanded }"><ArrowDown /></el-icon>
      </button>

      <transition name="usage-expand">
        <div v-show="expanded" class="usage-detail">
          <div class="usage-detail-head">本月合计 {{ tk(usage.totalTokens) }} tokens</div>
          <div v-if="!usage.models || usage.models.length === 0" class="usage-detail-empty">本月暂无用量</div>
          <div v-for="m in usage.models" :key="m.model" class="usage-detail-row">
            <span class="usage-model">{{ m.model }}</span>
            <span class="usage-model-tk">{{ tk(m.tokens) }} tokens</span>
            <span class="usage-model-cny">{{ cny(m.costCny) }}</span>
          </div>
        </div>
      </transition>
    </template>
  </div>
</template>

<style scoped>
.usage-bar {
  flex-shrink: 0;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-surface);
  --u-fill: var(--accent);
  --u-track: var(--accent-soft);
}
.usage-bar.warning { --u-fill: var(--color-warning); --u-track: var(--color-warning-soft); }
.usage-bar.danger { --u-fill: var(--color-danger); --u-track: var(--color-danger-soft); }

.usage-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: transparent;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-standard);
}
button.usage-row:hover { background: var(--color-bg-hover); }
.usage-muted { cursor: default; }

.usage-ico { color: var(--u-fill); font-size: 16px; flex-shrink: 0; }
.usage-label { font-size: var(--font-size-sm); color: var(--color-text-secondary); white-space: nowrap; flex-shrink: 0; }
.usage-spacer { flex: 1 1 auto; }

.usage-track {
  flex: 1 1 auto;
  min-width: 40px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--u-track);
  overflow: hidden;
}
.usage-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--u-fill);
  transition: width var(--duration-base) var(--ease-standard);
}
.usage-track.skeleton { background: var(--color-bg-muted); }
.usage-fill.shimmer {
  width: 40%;
  background: linear-gradient(90deg, var(--color-bg-muted), var(--color-border-default), var(--color-bg-muted));
  animation: usage-shimmer 1.2s infinite;
}
@keyframes usage-shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(250%); } }

.usage-amt { font-size: var(--font-size-sm); color: var(--color-text-primary); white-space: nowrap; flex-shrink: 0; }
.usage-chev {
  flex-shrink: 0; color: var(--color-text-tertiary); font-size: 14px;
  transition: transform var(--duration-fast) var(--ease-standard);
}
.usage-chev.open { transform: rotate(180deg); }

.usage-retry {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: var(--font-size-sm); color: var(--accent); background: transparent;
}
.usage-retry :deep(svg) { width: 13px; height: 13px; }

.usage-detail {
  padding: var(--space-2) var(--space-4) var(--space-3);
  background: var(--color-bg-muted);
  border-top: 1px solid var(--color-border-subtle);
}
.usage-detail-head { font-size: var(--font-size-xs); color: var(--color-text-tertiary); margin-bottom: var(--space-2); }
.usage-detail-empty { font-size: var(--font-size-sm); color: var(--color-text-tertiary); padding: var(--space-1) 0; }
.usage-detail-row { display: flex; align-items: center; gap: var(--space-2); padding: 3px 0; }
.usage-model { flex: 1 1 auto; min-width: 0; font-size: var(--font-size-sm); color: var(--color-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.usage-model-tk { font-size: var(--font-size-xs); color: var(--color-text-secondary); flex-shrink: 0; }
.usage-model-cny { font-size: var(--font-size-sm); color: var(--color-text-primary); flex-shrink: 0; min-width: 52px; text-align: right; }

.usage-expand-enter-active, .usage-expand-leave-active { transition: opacity var(--duration-fast) var(--ease-standard); }
.usage-expand-enter-from, .usage-expand-leave-to { opacity: 0; }

/* 移动端：金额过长时让进度条让位，金额换行隐患由 white-space 控制 */
@media (max-width: 640px) {
  .usage-label { display: none; }
  .usage-amt { font-size: var(--font-size-xs); }
}
</style>
