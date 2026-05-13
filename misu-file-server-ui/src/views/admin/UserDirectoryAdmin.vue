<template>
  <div class="uda">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN / FILE_ADMIN 用户可以访问管理员目录浏览" />

    <template v-else>
      <div class="uda-header">
        <div class="uda-title">
          <h2>用户目录浏览</h2>
          <div class="subtitle">选择用户后，以管理员视角查看其私人 / 公共目录文件与存储用量（只读）</div>
        </div>
        <div class="uda-actions">
          <el-button :icon="Refresh" @click="reloadAll" :loading="loading" :size="isMobile ? 'small' : 'default'">刷新</el-button>
        </div>
      </div>

      <!-- 用户选择 —— 桌面端 dropdown / 移动端 popover；不再占左半屏 -->
      <el-card class="uda-context-card" shadow="never">
        <div class="uda-context-row">
          <span class="uda-context-label">用户</span>
          <el-select
              v-model="selectedUserId"
              :placeholder="userSelectPlaceholder"
              filterable
              remote
              :remote-method="searchUsers"
              :loading="usersLoading"
              :teleported="true"
              class="uda-user-select"
              @change="onUserChange">
            <el-option
                v-for="u in users"
                :key="u.userId"
                :value="String(u.userId)"
                :label="`${u.userName} (ID ${u.userId})`" />
          </el-select>
          <el-radio-group v-model="openType" size="small" :disabled="!selectedUser" @change="reloadStorageAndFiles">
            <el-radio-button :label="0">私人</el-radio-button>
            <el-radio-button :label="1">公共</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="selectedUser" class="uda-context-summary">
          <div class="uda-context-summary-item">
            <span>已用</span><strong>{{ formatBytes(usage.usedBytes) }}</strong>
          </div>
          <div class="uda-context-summary-item">
            <span>配额</span><strong>{{ usage.quotaBytes != null ? formatBytes(usage.quotaBytes) : '不限' }}</strong>
          </div>
          <div class="uda-context-summary-item">
            <span>文件数</span><strong>{{ usage.fileCount || 0 }}</strong>
          </div>
          <div v-if="usage.quotaBytes" class="uda-context-summary-item">
            <span>使用率</span><strong>{{ usagePercent }}%</strong>
          </div>
        </div>
      </el-card>

      <el-card v-if="selectedUser" class="uda-files-card" shadow="never">
        <div class="uda-pathbar">
          <el-button link :icon="Back" :disabled="!parentPath" @click="goUp">上一级</el-button>
          <span class="uda-crumbs">
            <span class="uda-crumb" @click="navigate('')">/</span>
            <template v-for="(seg, idx) in pathSegments" :key="idx">
              <span class="uda-crumb-sep">/</span>
              <span class="uda-crumb" @click="navigate(pathPrefix(idx))">{{ seg }}</span>
            </template>
          </span>
        </div>

        <FileBrowserView
            :entries="files"
            :loading="filesLoading"
            storage-key="uda-view-mode"
            empty-title="目录为空"
            empty-hint="该路径下没有文件"
            :actions-width="160"
            @open="onEntryOpen" />
      </el-card>

      <div v-else class="uda-empty-hint">
        请在上方选择一个用户开始浏览
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Back, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getUserInfo, listUsers } from '@/api/user/user'
import { getUserStorageUsageAsAdmin, listUserFilesAsAdmin } from '@/api/fileServer/fileAdmin'
import { useBreakpoint } from '@/composables/useBreakpoint'
import FileBrowserView from '@/components/fileServer/FileBrowserView.vue'

const { isMobile } = useBreakpoint()
const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => {
  const auths = currentUserInfo.value.authorities || []
  return auths.includes('ADMIN') || auths.includes('FILE_ADMIN')
})

const loading = computed(() => usersLoading.value || filesLoading.value)

const usersLoading = ref(false)
const users = ref([])
const selectedUserId = ref('')
const selectedUser = computed(() => users.value.find(u => String(u.userId) === selectedUserId.value))
const userSelectPlaceholder = computed(() => (usersLoading.value ? '加载中…' : '按用户名搜索 / 选择用户'))

const openType = ref(0)
const parentPath = ref('')
const files = ref([])
const filesLoading = ref(false)
const usage = ref({})
const usagePercent = computed(() => {
  if (!usage.value.quotaBytes || usage.value.quotaBytes <= 0) return 0
  return Math.min(100, Math.round(((usage.value.usedBytes || 0) / usage.value.quotaBytes) * 100))
})
const pathSegments = computed(() => (parentPath.value ? parentPath.value.split('/').filter(Boolean) : []))
const pathPrefix = (index) => pathSegments.value.slice(0, index + 1).join('/')

const searchUsers = async (keyword) => {
  if (!isAdmin.value) return
  usersLoading.value = true
  try {
    const response = await listUsers({ userName: keyword || undefined })
    const data = response.data || {}
    const content = data.list || data.content || data.records || []
    users.value = Array.isArray(content) ? content : []
  } finally {
    usersLoading.value = false
  }
}

const onUserChange = () => {
  parentPath.value = ''
  reloadStorageAndFiles()
}

const reloadStorageAndFiles = () => {
  if (!selectedUser.value) return
  loadStorageUsage()
  loadFiles()
}

const loadStorageUsage = async () => {
  if (!selectedUser.value) return
  try {
    const response = await getUserStorageUsageAsAdmin({
      userId: openType.value === 1 ? undefined : selectedUser.value.userId,
      openType: openType.value
    })
    usage.value = response.data || {}
  } catch (e) {
    usage.value = {}
  }
}

const loadFiles = async () => {
  if (!selectedUser.value) return
  filesLoading.value = true
  try {
    const response = await listUserFilesAsAdmin({
      userId: openType.value === 1 ? undefined : selectedUser.value.userId,
      openType: openType.value,
      parentPath: parentPath.value || ''
    })
    files.value = response.data || []
  } catch (e) {
    files.value = []
  } finally {
    filesLoading.value = false
  }
}

const navigate = (path) => {
  parentPath.value = path || ''
  loadFiles()
}

const goUp = () => {
  if (!parentPath.value) return
  const segments = parentPath.value.split('/')
  segments.pop()
  navigate(segments.join('/'))
}

const onEntryOpen = (entry) => {
  if (!entry) return
  if (entry.fileType === 'directory') {
    const next = (parentPath.value ? parentPath.value + '/' : '') + (entry.fileName || entry.name)
    navigate(next)
  } else {
    // 普通文件：只读视图，暂不提供管理员下载入口；以后可扩展
    ElMessage.info('当前仅支持目录下钻，管理员下载入口请走文件服务（未来扩展）')
  }
}

const reloadAll = () => {
  searchUsers('')
  if (selectedUser.value) {
    loadStorageUsage()
    loadFiles()
  }
}

onMounted(() => {
  if (isAdmin.value) {
    searchUsers('')
  } else {
    ElMessage.warning('当前账号无管理员权限')
  }
})

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
</script>

<style scoped>
.uda {
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

@media (max-width: 640px) {
  .uda {
    padding: var(--space-3) var(--space-3) var(--space-12);
    gap: var(--space-2);
  }
}

.uda-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.uda-title h2 {
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

@media (max-width: 640px) {
  .uda-title h2 { font-size: var(--font-size-lg); }
  .subtitle { display: none; }
}

.uda-actions { display: flex; gap: var(--space-2); flex-shrink: 0; }

.uda-context-card { border-radius: var(--radius-md); }

.uda-context-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.uda-context-label {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  flex-shrink: 0;
}

.uda-user-select {
  min-width: 220px;
  flex: 1 1 240px;
}

@media (max-width: 640px) {
  .uda-context-row { gap: var(--space-2); }
  .uda-user-select { min-width: 0; width: 100%; flex: 1 1 100%; }
  .uda-context-label { width: 100%; }
}

.uda-context-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-2);
  margin-top: var(--space-3);
}

@media (max-width: 640px) {
  .uda-context-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

.uda-context-summary-item {
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  background: var(--color-bg-surface);
}
.uda-context-summary-item span {
  display: block;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}
.uda-context-summary-item strong {
  display: block;
  margin-top: 2px;
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.uda-files-card {
  border-radius: var(--radius-md);
}

.uda-pathbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: nowrap;
  margin-bottom: var(--space-2);
  overflow-x: auto;
  scrollbar-width: thin;
}

.uda-crumbs {
  display: inline-flex;
  align-items: center;
  flex-wrap: nowrap;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  white-space: nowrap;
}
.uda-crumb {
  cursor: pointer;
  color: var(--accent);
  padding: 0 var(--space-1);
}
.uda-crumb:hover { text-decoration: underline; }
.uda-crumb-sep { color: var(--color-text-tertiary); padding: 0 2px; }

.uda-empty-hint {
  padding: var(--space-6) var(--space-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-base);
}
</style>
