<script setup>
import { computed } from 'vue'
import { Close, Document, Download, Delete } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  files: { type: Array, default: () => [] },
  isMobile: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'download', 'delete'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const formatSize = (bytes) => {
  if (bytes == null) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}
const fmtTime = (v) => {
  if (!v) return ''
  const d = new Date(v)
  if (isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <el-drawer
      v-model="visible"
      :direction="isMobile ? 'btt' : 'rtl'"
      :size="isMobile ? 'auto' : 360"
      :with-header="false"
      class="gf-drawer">
    <div class="gf">
      <div v-if="isMobile" class="gf-handle" aria-hidden="true"></div>
      <header class="gf-head">
        <h3 class="gf-title">群文件 · {{ files.length }}</h3>
        <button class="gf-close" type="button" aria-label="关闭" @click="visible = false"><Close /></button>
      </header>

      <div v-if="files.length === 0" class="gf-empty">群里还没有共享文件</div>

      <div class="gf-list">
        <div v-for="f in files" :key="f.id" class="gf-item">
          <span class="gf-icon"><Document /></span>
          <div class="gf-meta">
            <span class="gf-name">{{ f.fileName }}</span>
            <span class="gf-sub">{{ formatSize(f.size) }} · {{ f.uploaderName }} · {{ fmtTime(f.createTime) }}</span>
          </div>
          <button class="gf-act" type="button" aria-label="下载" @click="emit('download', f)"><Download /></button>
          <button v-if="f.canDelete" class="gf-act danger" type="button" aria-label="删除" @click="emit('delete', f)"><Delete /></button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
:deep(.gf-drawer) { background: var(--color-bg-surface); }
:deep(.gf-drawer.el-drawer.rtl) { border-top-left-radius: var(--radius-lg); border-bottom-left-radius: var(--radius-lg); }
:deep(.gf-drawer.el-drawer.btt) { border-top-left-radius: var(--radius-lg); border-top-right-radius: var(--radius-lg); }
:deep(.gf-drawer .el-drawer__body) { padding: 0; }

.gf { display: flex; flex-direction: column; height: 100%; padding: var(--space-2) var(--space-4) max(var(--space-4), env(safe-area-inset-bottom)); }
.gf-handle { width: 40px; height: 4px; margin: var(--space-1) auto var(--space-2); border-radius: var(--radius-pill); background: var(--color-border-default); }
.gf-head { display: flex; align-items: center; justify-content: space-between; padding: var(--space-2) 0 var(--space-3); }
.gf-title { font-size: var(--font-size-md); font-weight: var(--font-weight-semibold); color: var(--color-text-primary); }
.gf-close { width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-pill); color: var(--color-text-tertiary); background: transparent; }
.gf-close:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.gf-close :deep(svg) { width: 16px; height: 16px; }

.gf-empty { padding: var(--space-8) var(--space-4); text-align: center; font-size: var(--font-size-sm); color: var(--color-text-tertiary); }

.gf-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.gf-item { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2); border-radius: var(--radius-md); }
.gf-item:hover { background: var(--color-bg-hover); }
.gf-icon { flex-shrink: 0; width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-md); background: var(--accent-soft); color: var(--accent); }
.gf-icon :deep(svg) { width: 20px; height: 20px; }
.gf-meta { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.gf-name { font-size: var(--font-size-base); color: var(--color-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gf-sub { font-size: var(--font-size-xs); color: var(--color-text-tertiary); }
.gf-act { flex-shrink: 0; width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-pill); color: var(--color-text-secondary); background: transparent; transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard); }
.gf-act:hover { background: var(--accent-soft); color: var(--accent); }
.gf-act.danger:hover { background: var(--color-danger-soft); color: var(--color-danger); }
.gf-act :deep(svg) { width: 16px; height: 16px; }
</style>
