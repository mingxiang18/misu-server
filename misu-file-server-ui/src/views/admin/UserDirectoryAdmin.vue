<template>
  <div class="user-dir-admin">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN / FILE_ADMIN 用户可以访问管理员目录浏览" />

    <template v-else>
      <div class="toolbar">
        <div>
          <h2>用户目录浏览</h2>
          <div class="subtitle">选择用户后，以管理员视角查看其私人 / 公共目录文件与存储用量（只读）</div>
        </div>
        <div class="actions">
          <el-button :icon="Refresh" @click="reloadAll" :loading="loading">刷新</el-button>
        </div>
      </div>

      <div class="layout">
        <el-card class="user-card" shadow="never">
          <div class="user-card-header">
            <el-input
              v-model="userQuery.userName"
              placeholder="按用户名搜索"
              clearable
              :prefix-icon="Search"
              @keyup.enter="reloadUsers"
            />
            <el-button type="primary" :icon="Search" @click="reloadUsers">查询</el-button>
          </div>
          <el-table
            :data="users"
            v-loading="usersLoading"
            highlight-current-row
            class="user-table"
            empty-text="无用户"
            @current-change="handleUserSelect"
            :row-class-name="(({ row }) => selectedUser && row.userId === selectedUser.userId ? 'is-selected' : '')"
          >
            <el-table-column prop="userName" label="用户名" min-width="120" show-overflow-tooltip />
            <el-table-column prop="userId" label="ID" width="100" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.status === '0' || row.status === 0" type="success" size="small">正常</el-tag>
                <el-tag v-else type="info" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="user-pager"
            small
            v-model:current-page="userPagination.page"
            v-model:page-size="userPagination.size"
            :total="userPagination.total"
            layout="prev, pager, next"
            background
            @current-change="loadUsers"
          />
        </el-card>

        <el-card class="files-card" shadow="never">
          <div v-if="!selectedUser" class="empty-hint">
            从左侧选择一个用户开始浏览
          </div>
          <template v-else>
            <div class="files-header">
              <div class="files-title">
                <strong>{{ selectedUser.userName }}</strong>
                <span class="muted">ID {{ selectedUser.userId }}</span>
                <el-radio-group v-model="openType" size="small" @change="reloadStorageAndFiles">
                  <el-radio-button :label="0">私人</el-radio-button>
                  <el-radio-button :label="1">公共</el-radio-button>
                </el-radio-group>
              </div>
            </div>

            <div class="usage-grid">
              <div class="summary-item">
                <span>已用</span>
                <strong>{{ formatBytes(usage.usedBytes) }}</strong>
              </div>
              <div class="summary-item">
                <span>配额</span>
                <strong>{{ usage.quotaBytes != null ? formatBytes(usage.quotaBytes) : '不限' }}</strong>
              </div>
              <div class="summary-item">
                <span>文件数</span>
                <strong>{{ usage.fileCount || 0 }}</strong>
              </div>
              <div class="summary-item" v-if="usage.quotaBytes">
                <span>使用率</span>
                <strong>{{ usagePercent }}%</strong>
              </div>
            </div>

            <div class="path-bar">
              <el-button link :icon="Back" :disabled="!parentPath" @click="goUp">上一级</el-button>
              <span class="breadcrumb">
                <span class="crumb" @click="navigate('')">/</span>
                <template v-for="(seg, idx) in pathSegments" :key="idx">
                  <span class="sep">/</span>
                  <span class="crumb" @click="navigate(pathPrefix(idx))">{{ seg }}</span>
                </template>
              </span>
            </div>

            <el-table
              :data="files"
              v-loading="filesLoading"
              class="file-table"
              empty-text="目录为空"
              @row-dblclick="onRowDoubleClick"
            >
              <el-table-column label="名称" min-width="240">
                <template #default="{ row }">
                  <div class="file-cell">
                    <component :is="row.fileType === 'directory' ? Folder : Document" class="file-icon" />
                    <span>{{ row.fileName }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="fileType" label="类型" width="120" />
              <el-table-column label="大小" width="120">
                <template #default="{ row }">
                  <span class="muted">{{ row.fileType === 'directory' ? '-' : formatBytes(row.fileSize) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Back, Document, Folder, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getUserInfo, listUsers } from '@/api/user/user'
import { getUserStorageUsageAsAdmin, listUserFilesAsAdmin } from '@/api/fileServer/fileAdmin'

const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => {
  const auths = currentUserInfo.value.authorities || []
  return auths.includes('ADMIN') || auths.includes('FILE_ADMIN')
})

const loading = computed(() => usersLoading.value || filesLoading.value)

const usersLoading = ref(false)
const users = ref([])
const userQuery = reactive({ userName: '' })
const userPagination = reactive({ page: 1, size: 20, total: 0 })
const selectedUser = ref(null)

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

const loadUsers = async () => {
  if (!isAdmin.value) return
  usersLoading.value = true
  try {
    // 后端 selectUserPage 暂未支持 pageNum / pageSize 参数；这里调用并直接消费返回值
    const response = await listUsers({ userName: userQuery.userName || undefined })
    const data = response.data || {}
    // 后端 AjaxResult.success(Page) → PageResult.buildPageResult：{ list, total, totalPages, pageSize, pageNumber }
    const content = data.list || data.content || data.records || []
    users.value = Array.isArray(content) ? content : []
    userPagination.total = data.total ?? data.totalElements ?? users.value.length
  } finally {
    usersLoading.value = false
  }
}

const reloadUsers = () => {
  userPagination.page = 1
  loadUsers()
}

const handleUserSelect = (row) => {
  if (!row) return
  selectedUser.value = row
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

const onRowDoubleClick = (row) => {
  if (row.fileType !== 'directory') return
  const next = (parentPath.value ? parentPath.value + '/' : '') + row.fileName
  navigate(next)
}

const reloadAll = () => {
  loadUsers()
  if (selectedUser.value) {
    loadStorageUsage()
    loadFiles()
  }
}

watch(openType, () => reloadStorageAndFiles())

onMounted(() => {
  if (isAdmin.value) {
    loadUsers()
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
.user-dir-admin {
  padding: var(--space-5);
}

@media (max-width: 640px) {
  .user-dir-admin {
    padding: var(--space-3);
  }
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

h2 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.subtitle,
.muted {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--space-4);
  align-items: flex-start;
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }
}

.user-card {
  display: flex;
  flex-direction: column;
}

.user-card-header {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.user-table :deep(.is-selected) {
  background: var(--accent-soft);
}

.user-pager {
  margin-top: var(--space-2);
  display: flex;
  justify-content: center;
}

.files-card {
  min-height: 360px;
}

.empty-hint {
  padding: var(--space-6) 0;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-base);
}

.files-header {
  margin-bottom: var(--space-3);
}

.files-title {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.usage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

@media (max-width: 640px) {
  .usage-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.summary-item {
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--color-bg-surface);
}

.summary-item span {
  display: block;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.summary-item strong {
  display: block;
  margin-top: var(--space-1);
  font-size: var(--font-size-xl);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.path-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
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
</style>
