<template>
  <div class="fbv">
    <div class="fbv-toolbar">
      <slot name="toolbar-left">
        <div class="fbv-toolbar-left" />
      </slot>
      <div class="fbv-toolbar-right">
        <slot name="toolbar-right" />
        <el-radio-group v-model="viewMode" size="small" class="fbv-view-toggle">
          <el-radio-button :label="GRID">
            <Grid class="fbv-toggle-icon" />
            <span class="fbv-toggle-label">图标</span>
          </el-radio-button>
          <el-radio-button :label="LIST">
            <List class="fbv-toggle-icon" />
            <span class="fbv-toggle-label">列表</span>
          </el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- Grid view -->
    <div v-if="viewMode === GRID" v-loading="loading" class="fbv-grid">
      <template v-if="!loading && entries.length === 0">
        <div class="fbv-empty">
          <component :is="Folder" class="fbv-empty-icon" />
          <p class="fbv-empty-title">{{ emptyTitle }}</p>
          <p class="fbv-empty-hint">{{ emptyHint }}</p>
        </div>
      </template>
      <el-container
          v-for="(entry, idx) in entries"
          :key="keyOf(entry, idx)"
          class="fbv-card"
          @click="onOpen(entry, $event)"
          @contextmenu.prevent="onContext(entry, $event)"
          @touchstart="onTouchStart(entry, $event)"
          @touchend="onTouchEnd"
          @touchmove="onTouchMove"
          @touchcancel="onTouchCancel">
        <el-main class="fbv-card-main">
          <Folder v-if="isDir(entry)" class="fbv-icon fbv-icon-folder" />
          <Picture v-else-if="typeOf(entry) === 'image'" class="fbv-icon" />
          <Film v-else-if="typeOf(entry) === 'video'" class="fbv-icon fbv-icon-video" />
          <Document v-else-if="typeOf(entry) === 'document'" class="fbv-icon fbv-icon-doc" />
          <Document v-else-if="typeOf(entry) === 'text'" class="fbv-icon fbv-icon-text" />
          <Document v-else class="fbv-icon" />
        </el-main>
        <el-footer class="fbv-card-footer">
          <span class="fbv-card-name" :title="nameOf(entry)">{{ nameOf(entry) }}</span>
          <span v-if="!isDir(entry) && sizeOf(entry) != null" class="fbv-card-meta">{{ formatBytes(sizeOf(entry)) }}</span>
        </el-footer>
        <div v-if="hasActions" class="fbv-card-actions" @click.stop>
          <slot name="actions" :entry="entry" :compact="true" />
        </div>
      </el-container>
    </div>

    <!-- List view -->
    <el-table
        v-else
        :data="entries"
        v-loading="loading"
        class="fbv-list"
        :empty-text="emptyTitle"
        @row-dblclick="(row, _col, ev) => onOpen(row, ev)"
        @row-contextmenu="(row, _col, ev) => onContext(row, ev)">
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <div class="fbv-name-cell" @click="onOpen(row, $event)"
               @touchstart="onTouchStart(row, $event)"
               @touchend="onTouchEnd"
               @touchmove="onTouchMove"
               @touchcancel="onTouchCancel">
            <Folder v-if="isDir(row)" class="fbv-row-icon fbv-icon-folder" />
            <Document v-else class="fbv-row-icon" />
            <span class="fbv-row-name">{{ nameOf(row) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" label="类型" width="110">
        <template #default="{ row }">
          <span class="fbv-row-type">{{ typeLabel(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" :width="isMobile ? 92 : 120">
        <template #default="{ row }">
          <span class="fbv-row-meta">{{ isDir(row) ? '-' : formatBytes(sizeOf(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile && showLastModified" label="最后修改" width="180">
        <template #default="{ row }">
          <span class="fbv-row-meta">{{ formatTime(modifiedOf(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="hasActions" label="操作" :width="actionsWidth" fixed="right">
        <template #default="{ row }">
          <slot name="actions" :entry="row" :compact="false" />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, useSlots, watch } from 'vue'
import { Document, Film, Folder, Grid, List, Picture } from '@element-plus/icons-vue'
import { useBreakpoint } from '@/composables/useBreakpoint'

const GRID = 'grid'
const LIST = 'list'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  emptyTitle: { type: String, default: '目录为空' },
  emptyHint: { type: String, default: '当前目录下没有可显示的内容' },
  storageKey: { type: String, default: 'fbv-view-mode' },
  defaultMode: { type: String, default: GRID },
  actionsWidth: { type: Number, default: 200 },
  showLastModified: { type: Boolean, default: false },
  // 字段提取（适配后端两种返回结构：file_mapping FileResponseDto 用 fileName/fileType/fileSize；
  // staging 物理列表 StagingEntryDto 用 name/directory/size/lastModified）
  nameKeys: { type: Array, default: () => ['name', 'fileName'] },
  sizeKeys: { type: Array, default: () => ['size', 'fileSize'] },
  modifiedKeys: { type: Array, default: () => ['lastModified', 'updateTime'] },
  // 目录判定：staging 用 entry.directory === true；file_mapping 用 fileType === 'directory'
  isDirectory: { type: Function, default: null }
})
const emit = defineEmits(['open', 'context'])
const slots = useSlots()
const hasActions = computed(() => !!slots.actions)

const { isMobile } = useBreakpoint()

const initialMode = (() => {
  try {
    const v = localStorage.getItem(props.storageKey)
    if (v === GRID || v === LIST) return v
  } catch (_) { /* localStorage 可能不可用 */ }
  return props.defaultMode === LIST ? LIST : GRID
})()
const viewMode = ref(initialMode)
watch(viewMode, (v) => {
  try { localStorage.setItem(props.storageKey, v) } catch (_) { /* ignore */ }
})

// ----------- 字段提取 helpers -----------
const pick = (obj, keys) => {
  for (const k of keys) {
    if (obj && obj[k] != null) return obj[k]
  }
  return null
}
const nameOf = (e) => pick(e, props.nameKeys) || ''
const sizeOf = (e) => pick(e, props.sizeKeys)
const modifiedOf = (e) => pick(e, props.modifiedKeys)
const typeOf = (e) => (e && (e.fileType || e.type)) || ''

const isDir = (e) => {
  if (typeof props.isDirectory === 'function') return !!props.isDirectory(e)
  if (e == null) return false
  if (typeof e.directory === 'boolean') return e.directory
  if (e.fileType === 'directory') return true
  return false
}

const typeLabel = (e) => {
  if (isDir(e)) return '目录'
  const t = typeOf(e)
  if (!t) return '文件'
  return t
}

const keyOf = (e, idx) => {
  if (!e) return idx
  const fallback = (e.filePath || '') + nameOf(e)
  return e.id ?? e.taskId ?? e.relativePath ?? (fallback || idx)
}

const formatBytes = (bytes) => {
  if (bytes == null) return '-'
  const n = Number(bytes)
  if (!Number.isFinite(n)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${i >= 2 ? v.toFixed(2) : v.toFixed(0)} ${units[i]}`
}
const formatTime = (val) => {
  if (!val) return '-'
  if (typeof val === 'string') return val.replace('T', ' ').slice(0, 19)
  if (val instanceof Date) return val.toISOString().slice(0, 19).replace('T', ' ')
  return String(val)
}

// ----------- 交互：单击 open，长按 / 右键 context -----------
const LONG_PRESS_MS = 500
let longPressTimer = null
let longPressEntry = null
let touchStart = null
let touchSuppressClick = false

const clearLongPress = () => {
  if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null }
  longPressEntry = null
}
const onTouchStart = (entry, ev) => {
  touchSuppressClick = false
  longPressEntry = entry
  const t = ev.touches && ev.touches[0]
  touchStart = t ? { x: t.clientX, y: t.clientY } : null
  longPressTimer = setTimeout(() => {
    if (longPressEntry) {
      touchSuppressClick = true
      emit('context', longPressEntry, ev)
    }
    longPressTimer = null
  }, LONG_PRESS_MS)
}
const onTouchMove = (ev) => {
  if (!touchStart) return
  const t = ev.touches && ev.touches[0]
  if (!t) return
  const dx = Math.abs(t.clientX - touchStart.x)
  const dy = Math.abs(t.clientY - touchStart.y)
  if (dx > 8 || dy > 8) clearLongPress()
}
const onTouchEnd = () => { clearLongPress(); touchStart = null }
const onTouchCancel = () => { clearLongPress(); touchStart = null }

const onOpen = (entry, ev) => {
  if (touchSuppressClick) { touchSuppressClick = false; return }
  emit('open', entry, ev)
}
const onContext = (entry, ev) => emit('context', entry, ev)

onBeforeUnmount(clearLongPress)
</script>

<style scoped>
.fbv {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.fbv-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.fbv-toolbar-left {
  flex: 1 1 auto;
  min-width: 0;
}

.fbv-toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.fbv-view-toggle :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.fbv-toggle-icon {
  width: 14px;
  height: 14px;
}

@media (max-width: 640px) {
  .fbv-toggle-label { display: none; }
  .fbv-toggle-icon { width: 16px; height: 16px; }
}

/* ---------- Grid ---------- */
.fbv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: var(--space-3);
  min-height: 200px;
  align-content: start;
}

@media (max-width: 640px) {
  .fbv-grid {
    grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
    gap: var(--space-2);
  }
}

.fbv-empty {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-12) var(--space-4);
  text-align: center;
  color: var(--color-text-tertiary);
}
.fbv-empty-icon {
  width: 56px;
  height: 56px;
  color: var(--color-border-strong);
}
.fbv-empty-title {
  margin: 0;
  font-size: var(--font-size-base);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.fbv-empty-hint {
  margin: 0;
  font-size: var(--font-size-sm);
}

.fbv-card {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  aspect-ratio: 4 / 5;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  user-select: none;
  -webkit-touch-callout: none;
  transition:
      border-color var(--duration-fast) var(--ease-standard),
      background var(--duration-fast) var(--ease-standard),
      transform var(--duration-fast) var(--ease-standard);
}
.fbv-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border-default);
  transform: translateY(-1px);
}
.fbv-card:active { transform: translateY(0); }

.fbv-card-main {
  flex: 0 0 60%;
  width: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
  padding: 0 !important;
  background: var(--color-bg-muted);
  box-shadow: none !important;
}

.fbv-card-footer {
  flex: 0 0 40%;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-2) var(--space-2) !important;
  height: auto !important;
  background: var(--color-bg-surface);
  gap: 2px;
}

.fbv-card-name {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-all;
  text-align: center;
  font-size: var(--font-size-sm);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
  width: 100%;
}
.fbv-card-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

@media (max-width: 640px) {
  .fbv-card-name { font-size: var(--font-size-xs); }
  .fbv-card-footer { padding: var(--space-1) var(--space-2) !important; }
}

.fbv-icon {
  width: 40px;
  height: 40px;
  color: var(--color-text-secondary);
}
.fbv-icon-folder { color: var(--accent); }
.fbv-icon-video,
.fbv-icon-doc,
.fbv-icon-text { color: var(--color-text-secondary); }

@media (max-width: 640px) {
  .fbv-icon { width: 32px; height: 32px; }
}

/* 卡片右下角 action slot (compact 模式) —— 让用户点点击不会冒泡触发 open */
.fbv-card-actions {
  position: absolute;
  right: 4px;
  bottom: calc(40% + 4px);
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  z-index: 1;
}

/* ---------- List ---------- */
.fbv-list { width: 100%; }
.fbv-name-cell {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  user-select: none;
  -webkit-touch-callout: none;
}
.fbv-row-icon {
  width: 18px;
  height: 18px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}
.fbv-row-name {
  word-break: break-all;
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
}
.fbv-row-type, .fbv-row-meta {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}
</style>
