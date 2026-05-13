<template>
  <div class="stg">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN / FILE_ADMIN 用户可以管理预置目录" />

    <template v-else>
      <div class="stg-header">
        <div class="stg-title">
          <h2>预置目录（staging）</h2>
          <div class="subtitle">SCP / 本地挂载投递到此目录的物理文件；通过右键 / 长按选择共享到公共或指定用户私人目录</div>
          <div class="stg-root" v-if="stagingRoot">物理根：{{ stagingRoot }}</div>
        </div>
        <div class="stg-actions">
          <el-button :icon="Refresh" @click="reload" :loading="loading" :size="isMobile ? 'small' : 'default'">刷新</el-button>
        </div>
      </div>

      <el-card class="stg-card" shadow="never">
        <div class="stg-pathbar">
          <el-button link :icon="Back" :disabled="!subPath" @click="goUp">上一级</el-button>
          <span class="stg-crumbs">
            <span class="stg-crumb" @click="navigate('')">/</span>
            <template v-for="(seg, idx) in pathSegments" :key="idx">
              <span class="stg-crumb-sep">/</span>
              <span class="stg-crumb" @click="navigate(pathPrefix(idx))">{{ seg }}</span>
            </template>
          </span>
        </div>

        <FileBrowserView
            :entries="entries"
            :loading="loading"
            storage-key="stg-view-mode"
            empty-title="目录为空"
            empty-hint="通过 SCP 投递文件到上面的物理根后再回来刷新"
            :actions-width="220"
            :show-last-modified="true"
            @open="onOpen"
            @context="onContext">
          <template #actions="{ entry, compact }">
            <template v-if="compact">
              <el-button size="small" link :icon="Share" @click.stop="openShareToPublic(entry)" title="共享到公共" />
              <el-button size="small" link :icon="User" @click.stop="openShareToUser(entry)" title="共享到用户" />
            </template>
            <template v-else>
              <el-button type="primary" link :icon="Share" @click.stop="openShareToPublic(entry)">公共</el-button>
              <el-button type="warning" link :icon="User" @click.stop="openShareToUser(entry)">用户</el-button>
            </template>
          </template>
        </FileBrowserView>
      </el-card>

      <!-- 浮层右键菜单（桌面端）；移动端长按也走它 -->
      <ul
          v-show="contextMenu.visible"
          class="stg-context-menu"
          :style="contextMenuStyle"
          @click.stop>
        <li @click="openShareToPublic(contextMenu.entry)">共享到公共目录</li>
        <li @click="openShareToUser(contextMenu.entry)">共享到指定用户私人目录</li>
        <li class="stg-context-cancel" @click="hideContextMenu">取消</li>
      </ul>

      <!-- 共享到公共目录 -->
      <el-dialog
          v-model="publicDialog.visible"
          title="共享到公共目录"
          :width="isMobile ? '92vw' : '500px'"
          :fullscreen="false">
        <p class="stg-dialog-hint">
          源 staging 文件：<code>{{ publicDialog.sourcePath }}</code>
        </p>
        <el-form :label-width="isMobile ? '88px' : '120px'">
          <el-form-item label="目标虚拟路径">
            <el-input
                v-model="publicDialog.targetVirtualPath"
                placeholder="例：movies/2024/foo.mp4；留空 = 用源文件名" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="publicDialog.visible = false">取消</el-button>
          <el-button type="primary" @click="confirmShareToPublic" :loading="publicDialog.submitting">确认共享</el-button>
        </template>
      </el-dialog>

      <!-- 共享到用户私人目录 -->
      <el-dialog
          v-model="userDialog.visible"
          title="共享到指定用户的私人目录"
          :width="isMobile ? '92vw' : '600px'"
          :fullscreen="false">
        <p class="stg-dialog-hint">
          源 staging 文件：<code>{{ userDialog.sourcePath }}</code>
        </p>
        <el-form :label-width="isMobile ? '88px' : '120px'">
          <el-form-item label="选择用户">
            <el-select
                v-model="userDialog.targetUserId"
                filterable
                remote
                :remote-method="searchUsers"
                :loading="userDialog.usersLoading"
                placeholder="按用户名搜索"
                style="width: 100%">
              <el-option
                  v-for="u in userDialog.users"
                  :key="u.userId"
                  :label="`${u.userName} (ID ${u.userId})`"
                  :value="String(u.userId)" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标虚拟路径">
            <el-input
                v-model="userDialog.targetVirtualPath"
                placeholder="例：archive/foo.mp4；留空 = 用源文件名" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="userDialog.visible = false">取消</el-button>
          <el-button type="primary" @click="confirmShareToUser" :loading="userDialog.submitting">确认共享</el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Back, Refresh, Share, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getUserInfo, listUsers } from '@/api/user/user'
import {
  getStagingRoot,
  listStaging,
  shareStagingToPublic,
  shareStagingToUser
} from '@/api/fileServer/fileAdmin'
import { useBreakpoint } from '@/composables/useBreakpoint'
import FileBrowserView from '@/components/fileServer/FileBrowserView.vue'

const { isMobile } = useBreakpoint()
const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => {
  const auths = currentUserInfo.value.authorities || []
  return auths.includes('ADMIN') || auths.includes('FILE_ADMIN')
})

const stagingRoot = ref('')
const subPath = ref('')
const entries = ref([])
const loading = ref(false)

const pathSegments = computed(() => (subPath.value ? subPath.value.split('/').filter(Boolean) : []))
const pathPrefix = (index) => pathSegments.value.slice(0, index + 1).join('/')

// 右键 / 长按浮层菜单
const contextMenu = reactive({ visible: false, x: 0, y: 0, entry: null })
const contextMenuStyle = computed(() => {
  const w = window.innerWidth || 0
  const left = Math.min(contextMenu.x, Math.max(0, w - 240))
  const top = contextMenu.y
  return { top: top + 'px', left: left + 'px' }
})

const publicDialog = reactive({
  visible: false,
  sourcePath: '',
  targetVirtualPath: '',
  submitting: false
})

const userDialog = reactive({
  visible: false,
  sourcePath: '',
  targetVirtualPath: '',
  targetUserId: '',
  users: [],
  usersLoading: false,
  submitting: false
})

const loadStagingRoot = async () => {
  try {
    const response = await getStagingRoot()
    stagingRoot.value = response.data || ''
  } catch (e) {
    stagingRoot.value = ''
  }
}

const loadEntries = async () => {
  loading.value = true
  try {
    const response = await listStaging(subPath.value || undefined)
    entries.value = response.data || []
  } catch (e) {
    entries.value = []
  } finally {
    loading.value = false
  }
}

const reload = () => {
  loadStagingRoot()
  loadEntries()
}

const navigate = (path) => {
  subPath.value = path || ''
  hideContextMenu()
  loadEntries()
}

const goUp = () => {
  if (!subPath.value) return
  const segments = subPath.value.split('/')
  segments.pop()
  navigate(segments.join('/'))
}

const onOpen = (entry) => {
  if (!entry) return
  if (entry.directory) {
    const next = (subPath.value ? subPath.value + '/' : '') + entry.name
    navigate(next)
  }
  // 非目录单击不做事，行内 / 长按操作走 actions slot 或 contextmenu
}

const onContext = (entry, ev) => {
  if (!entry) return
  let x = 0, y = 0
  if (ev && ev.clientX != null) { x = ev.clientX; y = ev.clientY }
  else if (ev && ev.touches && ev.touches[0]) { x = ev.touches[0].clientX; y = ev.touches[0].clientY }
  else if (ev && ev.changedTouches && ev.changedTouches[0]) { x = ev.changedTouches[0].clientX; y = ev.changedTouches[0].clientY }
  contextMenu.entry = entry
  contextMenu.x = x
  contextMenu.y = y
  contextMenu.visible = true
}

const hideContextMenu = () => { contextMenu.visible = false }

const openShareToPublic = (entry) => {
  if (!entry) return
  hideContextMenu()
  publicDialog.sourcePath = entry.relativePath || entry.name
  publicDialog.targetVirtualPath = entry.name || ''
  publicDialog.visible = true
}

const confirmShareToPublic = async () => {
  publicDialog.submitting = true
  try {
    await shareStagingToPublic({
      sourceStagingPath: publicDialog.sourcePath,
      targetVirtualPath: publicDialog.targetVirtualPath || undefined
    })
    ElMessage.success('已共享到公共目录')
    publicDialog.visible = false
  } finally {
    publicDialog.submitting = false
  }
}

const openShareToUser = (entry) => {
  if (!entry) return
  hideContextMenu()
  userDialog.sourcePath = entry.relativePath || entry.name
  userDialog.targetVirtualPath = entry.name || ''
  userDialog.targetUserId = ''
  userDialog.users = []
  userDialog.visible = true
}

const searchUsers = async (keyword) => {
  userDialog.usersLoading = true
  try {
    const response = await listUsers({ userName: keyword || undefined })
    const data = response.data || {}
    const content = data.list || data.content || data.records || []
    userDialog.users = Array.isArray(content) ? content : []
  } finally {
    userDialog.usersLoading = false
  }
}

const confirmShareToUser = async () => {
  if (!userDialog.targetUserId) {
    ElMessage.warning('请先选择目标用户')
    return
  }
  userDialog.submitting = true
  try {
    await shareStagingToUser({
      sourceStagingPath: userDialog.sourcePath,
      targetUserId: userDialog.targetUserId,
      targetVirtualPath: userDialog.targetVirtualPath || undefined
    })
    ElMessage.success('已共享到该用户私人目录')
    userDialog.visible = false
  } finally {
    userDialog.submitting = false
  }
}

onMounted(() => {
  if (isAdmin.value) {
    reload()
    document.addEventListener('click', hideContextMenu)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.stg {
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

@media (max-width: 640px) {
  .stg {
    padding: var(--space-3) var(--space-3) var(--space-12);
    gap: var(--space-2);
  }
}

.stg-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.stg-title h2 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}
.subtitle {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  margin-top: 2px;
}
.stg-root {
  margin-top: var(--space-1);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  word-break: break-all;
}

@media (max-width: 640px) {
  .stg-title h2 { font-size: var(--font-size-lg); }
  .subtitle { font-size: var(--font-size-xs); }
}

.stg-actions { display: flex; gap: var(--space-2); flex-shrink: 0; }

.stg-card { border-radius: var(--radius-md); }

.stg-pathbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: nowrap;
  margin-bottom: var(--space-2);
  overflow-x: auto;
  scrollbar-width: thin;
}

.stg-crumbs {
  display: inline-flex;
  align-items: center;
  flex-wrap: nowrap;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  white-space: nowrap;
}
.stg-crumb {
  cursor: pointer;
  color: var(--accent);
  padding: 0 var(--space-1);
}
.stg-crumb:hover { text-decoration: underline; }
.stg-crumb-sep { color: var(--color-text-tertiary); padding: 0 2px; }

.stg-context-menu {
  position: fixed;
  z-index: 1000;
  min-width: 220px;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-md);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.12);
  padding: var(--space-1) 0;
  list-style: none;
  margin: 0;
}
.stg-context-menu li {
  padding: 10px 16px;
  cursor: pointer;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}
.stg-context-menu li:hover { background: var(--color-bg-hover); }
.stg-context-cancel { color: var(--color-text-tertiary); border-top: 1px solid var(--color-border-subtle); margin-top: 4px; }

.stg-dialog-hint {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  word-break: break-all;
}
.stg-dialog-hint code {
  background: var(--color-bg-muted, var(--color-bg-hover));
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  color: var(--color-text-primary);
}
</style>
