<template>
  <div class="staging-page">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN / FILE_ADMIN 用户可以管理预置目录" />

    <template v-else>
      <div class="toolbar">
        <div>
          <h2>预置目录（staging）</h2>
          <div class="subtitle">
            管理员通过 SCP / 本地挂载等方式投递到此目录的物理文件；右键选择共享到公共目录或指定用户的私人目录后才会出现在虚拟文件系统里
          </div>
          <div class="root-path muted" v-if="stagingRoot">物理根：{{ stagingRoot }}</div>
        </div>
        <div class="actions">
          <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
        </div>
      </div>

      <div class="path-bar">
        <el-button link :icon="Back" :disabled="!subPath" @click="goUp">上一级</el-button>
        <span class="breadcrumb">
          <span class="crumb" @click="navigate('')">/</span>
          <template v-for="(seg, idx) in pathSegments" :key="idx">
            <span class="sep">/</span>
            <span class="crumb" @click="navigate(pathPrefix(idx))">{{ seg }}</span>
          </template>
        </span>
      </div>

      <el-table
        :data="entries"
        v-loading="loading"
        class="staging-table"
        empty-text="目录为空 —— 通过 SCP 投递文件到上面的物理根后再回来刷新"
        @row-dblclick="onRowDoubleClick"
        @row-contextmenu="onRowContextMenu"
      >
        <el-table-column label="名称" min-width="320">
          <template #default="{ row }">
            <div class="file-cell">
              <component :is="row.directory ? Folder : Document" class="file-icon" />
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="140">
          <template #default="{ row }">
            <span class="muted">{{ row.directory ? '-' : formatBytes(row.size) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后修改" width="180">
          <template #default="{ row }">
            <span class="muted">{{ formatTime(row.lastModified) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="Share" @click="openShareToPublic(row)">共享到公共</el-button>
            <el-button type="warning" link :icon="User" @click="openShareToUser(row)">共享到用户</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 右键浮层 -->
      <ul
        v-show="contextMenu.visible"
        class="context-menu"
        :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
        @click.stop
      >
        <li @click="openShareToPublic(contextMenu.row)">共享到公共目录</li>
        <li @click="openShareToUser(contextMenu.row)">共享到指定用户的私人目录</li>
      </ul>

      <!-- 共享到公共目录 -->
      <el-dialog v-model="publicDialog.visible" title="共享到公共目录" width="500px">
        <p class="muted dialog-hint">
          源 staging 文件：<code>{{ publicDialog.sourcePath }}</code>
        </p>
        <el-form label-width="120px">
          <el-form-item label="目标虚拟路径">
            <el-input
              v-model="publicDialog.targetVirtualPath"
              placeholder="例：movies/2024/foo.mp4；留空则使用源文件名落到公共根"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="publicDialog.visible = false">取消</el-button>
          <el-button type="primary" @click="confirmShareToPublic" :loading="publicDialog.submitting">确认共享</el-button>
        </template>
      </el-dialog>

      <!-- 共享到用户私人目录 -->
      <el-dialog v-model="userDialog.visible" title="共享到指定用户的私人目录" width="600px">
        <p class="muted dialog-hint">
          源 staging 文件：<code>{{ userDialog.sourcePath }}</code>
        </p>
        <el-form label-width="120px">
          <el-form-item label="选择用户">
            <el-select
              v-model="userDialog.targetUserId"
              filterable
              remote
              :remote-method="searchUsers"
              :loading="userDialog.usersLoading"
              placeholder="按用户名搜索"
              style="width: 100%"
            >
              <el-option
                v-for="u in userDialog.users"
                :key="u.userId"
                :label="`${u.userName} (ID ${u.userId})`"
                :value="String(u.userId)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目标虚拟路径">
            <el-input
              v-model="userDialog.targetVirtualPath"
              placeholder="例：archive/foo.mp4；留空则使用源文件名落到该用户根"
            />
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
import { Back, Document, Folder, Refresh, Share, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getUserInfo, listUsers } from '@/api/user/user'
import {
  getStagingRoot,
  listStaging,
  shareStagingToPublic,
  shareStagingToUser
} from '@/api/fileServer/fileAdmin'

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

const contextMenu = reactive({ visible: false, x: 0, y: 0, row: null })

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
  loadEntries()
}

const goUp = () => {
  if (!subPath.value) return
  const segments = subPath.value.split('/')
  segments.pop()
  navigate(segments.join('/'))
}

const onRowDoubleClick = (row) => {
  if (!row.directory) return
  const next = (subPath.value ? subPath.value + '/' : '') + row.name
  navigate(next)
}

const onRowContextMenu = (row, _column, event) => {
  event.preventDefault()
  contextMenu.row = row
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.visible = true
}

const hideContextMenu = () => {
  contextMenu.visible = false
}

const openShareToPublic = (row) => {
  if (!row) return
  hideContextMenu()
  publicDialog.sourcePath = row.relativePath
  publicDialog.targetVirtualPath = row.name
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

const openShareToUser = (row) => {
  if (!row) return
  hideContextMenu()
  userDialog.sourcePath = row.relativePath
  userDialog.targetVirtualPath = row.name
  userDialog.targetUserId = ''
  userDialog.users = []
  userDialog.visible = true
}

const searchUsers = async (keyword) => {
  userDialog.usersLoading = true
  try {
    const response = await listUsers({ userName: keyword || undefined })
    const data = response.data || {}
    const content = data.content || data.records || data || []
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

const formatBytes = (bytes) => {
  if (bytes == null) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = Number(bytes)
  let idx = 0
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024
    idx += 1
  }
  const decimals = idx >= 2 ? 2 : 0
  return `${value.toFixed(decimals)} ${units[idx]}`
}

const formatTime = (iso) => {
  if (!iso) return '-'
  if (typeof iso === 'string') return iso.replace('T', ' ').slice(0, 19)
  return String(iso)
}

onMounted(() => {
  if (isAdmin.value) {
    reload()
    // 点击页面任意位置（不带 .context-menu）则关闭右键菜单
    document.addEventListener('click', hideContextMenu)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.staging-page {
  padding: var(--space-5);
}

@media (max-width: 640px) {
  .staging-page {
    padding: var(--space-3);
  }
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

h2 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.subtitle,
.muted,
.root-path {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.root-path {
  margin-top: var(--space-1);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  word-break: break-all;
}

.actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.path-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}

.breadcrumb {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.crumb {
  cursor: pointer;
  color: var(--accent);
  margin: 0 var(--space-1);
}

.crumb:hover {
  text-decoration: underline;
}

.sep {
  color: var(--color-text-tertiary);
}

.staging-table {
  width: 100%;
}

.file-cell {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.file-icon {
  width: 16px;
  height: 16px;
  color: var(--color-text-tertiary);
}

.context-menu {
  position: fixed;
  z-index: 1000;
  min-width: 220px;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md, 0 6px 24px rgba(0,0,0,0.12));
  padding: var(--space-1) 0;
  list-style: none;
  margin: 0;
}

.context-menu li {
  padding: 8px 16px;
  cursor: pointer;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.context-menu li:hover {
  background: var(--color-bg-hover);
}

.dialog-hint {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-sm);
  word-break: break-all;
}

.dialog-hint code {
  background: var(--color-bg-subtle, var(--color-bg-hover));
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}
</style>
