<script setup>
import { ref, computed, watch } from 'vue'
import { Search, Close, Check } from '@element-plus/icons-vue'
import ChatAvatar from './ChatAvatar.vue'
import { searchUsers } from '@/api/chat/chat'
import logger from '@/utils/logger'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  isMobile: { type: Boolean, default: false },
  // 'create'（建群）| 'add'（往现有群加人）
  mode: { type: String, default: 'create' },
  // add 模式下排除的已有成员 userId
  existingIds: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'created', 'add-members'])

const isAdd = computed(() => props.mode === 'add')

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const groupName = ref('')
const keyword = ref('')
const selected = ref([])
const candidates = ref([])
const loading = ref(false)
const submitting = ref(false)

const runSearch = async () => {
  loading.value = true
  try {
    const res = await searchUsers(keyword.value.trim())
    const ex = (props.existingIds || []).map((x) => String(x))
    candidates.value = (res.data || []).filter((u) => !ex.includes(String(u.userId)))
  } catch (err) {
    logger.error('搜索用户失败:', err)
    candidates.value = []
  } finally {
    loading.value = false
  }
}

let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(runSearch, 250)
})

watch(visible, (v) => {
  if (v) {
    groupName.value = ''
    keyword.value = ''
    selected.value = []
    candidates.value = []
    submitting.value = false
    runSearch()
  }
})

const isSelected = (u) => selected.value.some((s) => String(s.userId) === String(u.userId))
const toggle = (u) => {
  if (isSelected(u)) selected.value = selected.value.filter((s) => String(s.userId) !== String(u.userId))
  else selected.value.push(u)
}
const removeChip = (u) => { selected.value = selected.value.filter((s) => String(s.userId) !== String(u.userId)) }

const canCreate = computed(() =>
    selected.value.length >= 1 && !submitting.value && (isAdd.value || groupName.value.trim().length > 0))

const submit = () => {
  if (!canCreate.value) return
  submitting.value = true
  const ids = selected.value.map((u) => String(u.userId))
  if (isAdd.value) {
    emit('add-members', ids)
  } else {
    emit('created', { title: groupName.value.trim(), memberUserIds: ids })
  }
}

// 父组件创建完成后会关闭弹窗；这里暴露一个失败回滚（重新允许提交）
defineExpose({ resetSubmitting: () => { submitting.value = false } })
</script>

<template>
  <el-dialog
      v-model="visible"
      :width="isMobile ? '92%' : '460px'"
      :show-close="false"
      align-center
      class="cg-dialog">
    <template #header>
      <div class="cg-header">
        <h3 class="cg-title">{{ isAdd ? '添加成员' : '创建群聊' }}</h3>
        <button class="cg-close" type="button" aria-label="关闭" @click="visible = false"><Close /></button>
      </div>
    </template>

    <div class="cg-body">
      <div v-if="!isAdd" class="cg-field">
        <label class="cg-label">群名称</label>
        <input v-model="groupName" class="cg-name-input" type="text" placeholder="给群聊起个名字" maxlength="20" />
      </div>

      <div class="cg-field">
        <label class="cg-label">添加成员 · 已选 {{ selected.length }}</label>

        <div v-if="selected.length > 0" class="cg-chips">
          <span v-for="u in selected" :key="u.userId" class="cg-chip">
            <ChatAvatar :name="u.nickName || u.userName" :size="20" />
            <span class="cg-chip-name">{{ u.nickName || u.userName }}</span>
            <button class="cg-chip-x" type="button" aria-label="移除" @click="removeChip(u)"><Close /></button>
          </span>
        </div>

        <div class="cg-search">
          <Search class="cg-search-icon" />
          <input v-model="keyword" class="cg-search-input" type="text" placeholder="搜索用户昵称 / 账号" />
        </div>

        <div class="cg-candidates">
          <button
              v-for="u in candidates"
              :key="u.userId"
              class="cg-cand"
              :class="{ selected: isSelected(u) }"
              type="button"
              @click="toggle(u)">
            <ChatAvatar :name="u.nickName || u.userName" :size="36" />
            <div class="cg-cand-meta">
              <span class="cg-cand-name">{{ u.nickName || u.userName }}</span>
              <span class="cg-cand-sub">@{{ u.userName }}</span>
            </div>
            <span class="cg-cand-check"><Check /></span>
          </button>
          <div v-if="candidates.length === 0" class="cg-cand-empty">没有匹配的用户</div>
        </div>

        <p v-if="!isAdd" class="cg-hint">冥想bb 会自动加入新群，群里 @它 即可让它回复</p>
      </div>
    </div>

    <template #footer>
      <div class="cg-footer">
        <button class="cg-btn ghost" type="button" @click="visible = false">取消</button>
        <button class="cg-btn primary" type="button" :disabled="!canCreate" @click="submit">{{ isAdd ? '添加' : '创建' }}</button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.cg-dialog) { border-radius: var(--radius-lg); overflow: hidden; }
:deep(.cg-dialog .el-dialog__header) { padding: 0; margin: 0; }
:deep(.cg-dialog .el-dialog__body) { padding: 0; }
:deep(.cg-dialog .el-dialog__footer) { padding: 0; }

.cg-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-border-subtle);
}
.cg-title { font-size: var(--font-size-lg); font-weight: var(--font-weight-semibold); color: var(--color-text-primary); }
.cg-close {
  width: 30px; height: 30px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); color: var(--color-text-tertiary); background: transparent;
  transition: background var(--duration-fast) var(--ease-standard);
}
.cg-close:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.cg-close :deep(svg) { width: 16px; height: 16px; }

.cg-body { padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-5); }
.cg-field { display: flex; flex-direction: column; gap: var(--space-2); }
.cg-label { font-size: var(--font-size-sm); color: var(--color-text-secondary); font-weight: var(--font-weight-medium); }

.cg-name-input {
  height: 40px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-md);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--font-size-base);
  outline: none;
  transition: border-color var(--duration-fast) var(--ease-standard);
}
.cg-name-input:focus { border-color: var(--accent); }

.cg-chips { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.cg-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 5px 3px 4px;
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
}
.cg-chip-name { font-size: var(--font-size-sm); color: var(--accent); }
.cg-chip-x {
  width: 16px; height: 16px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); background: rgba(0,0,0,0.06); color: var(--accent);
}
.cg-chip-x :deep(svg) { width: 10px; height: 10px; }

.cg-search { position: relative; display: flex; align-items: center; }
.cg-search-icon { position: absolute; left: var(--space-3); width: 16px; height: 16px; color: var(--color-text-tertiary); }
.cg-search-input {
  width: 100%; height: 38px;
  padding: 0 var(--space-3) 0 calc(var(--space-3) + 22px);
  border: 1px solid transparent; border-radius: var(--radius-md);
  background: var(--color-bg-muted); color: var(--color-text-primary);
  font-size: var(--font-size-base); outline: none;
  transition: background var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard);
}
.cg-search-input:focus { background: var(--color-bg-surface); border-color: var(--color-border-strong); }

.cg-candidates {
  max-height: 220px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 2px;
  border: 1px solid var(--color-border-subtle); border-radius: var(--radius-md);
  padding: var(--space-1);
}
.cg-cand {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-2); border-radius: var(--radius-md);
  background: transparent; text-align: left;
  transition: background var(--duration-fast) var(--ease-standard);
}
.cg-cand:hover { background: var(--color-bg-hover); }
.cg-cand.selected { background: var(--accent-soft); }
.cg-cand-meta { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; }
.cg-cand-name { font-size: var(--font-size-base); color: var(--color-text-primary); }
.cg-cand-sub { font-size: var(--font-size-xs); color: var(--color-text-tertiary); }
.cg-cand-check {
  flex-shrink: 0; width: 22px; height: 22px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); border: 1px solid var(--color-border-default);
  color: transparent;
}
.cg-cand.selected .cg-cand-check { background: var(--accent); border-color: var(--accent); color: var(--color-text-on-accent); }
.cg-cand-check :deep(svg) { width: 13px; height: 13px; }
.cg-cand-empty { padding: var(--space-5); text-align: center; font-size: var(--font-size-sm); color: var(--color-text-tertiary); }

.cg-hint { font-size: var(--font-size-xs); color: var(--color-text-tertiary); }

.cg-footer {
  display: flex; justify-content: flex-end; gap: var(--space-2);
  padding: var(--space-3) var(--space-5) var(--space-4);
  border-top: 1px solid var(--color-border-subtle);
}
.cg-btn {
  height: 38px; padding: 0 var(--space-5);
  border-radius: var(--radius-md); font-size: var(--font-size-base); font-weight: var(--font-weight-medium);
  transition: background var(--duration-fast) var(--ease-standard);
}
.cg-btn.ghost { background: var(--color-bg-muted); color: var(--color-text-secondary); }
.cg-btn.ghost:hover { background: var(--color-bg-hover); }
.cg-btn.primary { background: var(--accent); color: var(--color-text-on-accent); }
.cg-btn.primary:hover:not(:disabled) { background: var(--accent-strong); }
.cg-btn.primary:disabled { background: var(--color-border-default); cursor: not-allowed; opacity: 0.7; }
</style>
