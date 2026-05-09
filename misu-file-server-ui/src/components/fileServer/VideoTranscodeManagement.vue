<template>
  <div class="transcode-page">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN 用户可以管理视频转码任务" />

    <template v-else>
      <div class="toolbar">
        <div>
          <h2>视频转码管理</h2>
          <div class="subtitle">查看队列状态、失败原因，并重试失败或中断的转码任务</div>
        </div>
        <div class="actions">
          <el-button :icon="Refresh" @click="loadTasks" :loading="loading">刷新</el-button>
          <el-button type="warning" :icon="RefreshLeft" @click="recoverRunning" :loading="recovering">恢复 running</el-button>
          <el-button type="primary" :icon="RefreshRight" @click="retryAllFailed" :loading="retryingAll" :disabled="summary.failedCount === 0">
            重试全部失败
          </el-button>
        </div>
      </div>

      <div class="summary">
        <div class="summary-item">
          <span>等待</span>
          <strong>{{ summary.waitingCount || 0 }}</strong>
        </div>
        <div class="summary-item">
          <span>运行中</span>
          <strong>{{ summary.runningCount || 0 }}</strong>
        </div>
        <div class="summary-item danger">
          <span>失败</span>
          <strong>{{ summary.failedCount || 0 }}</strong>
        </div>
        <div class="summary-item skipped">
          <span>软链接跳过</span>
          <strong>{{ summary.skippedCount || 0 }}</strong>
        </div>
        <div class="summary-item">
          <span>完成</span>
          <strong>{{ summary.doneCount || 0 }}</strong>
        </div>
      </div>

      <el-table :data="tasks" v-loading="loading" class="task-table" height="calc(100vh - 260px)" empty-text="暂无转码任务">
        <el-table-column prop="taskId" label="任务ID" width="250" show-overflow-tooltip />
        <el-table-column label="队列" width="110">
          <template #default="{ row }">
            <el-tag :type="queueTagType(row.queueState)">{{ row.queueState || 'UNKNOWN' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row)">{{ displayState(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="150">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :status="progressStatus(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="message" label="原因 / 消息" min-width="220" show-overflow-tooltip />
        <el-table-column prop="sourcePath" label="源文件" min-width="320" show-overflow-tooltip />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.retryable" type="primary" link :icon="RefreshRight" @click="retryOne(row)">
              重试
            </el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-card class="backfill-card" shadow="never">
        <template #header>
          <div class="backfill-header">
            <span>文件映射回填</span>
            <div class="actions">
              <el-button :icon="Refresh" @click="loadBackfillStatus" :loading="backfillLoading">刷新状态</el-button>
              <el-button type="primary" :icon="CaretRight" @click="startBackfill" :loading="startingBackfill" :disabled="backfillStatus.running">
                启动回填
              </el-button>
            </div>
          </div>
        </template>
        <div class="backfill-grid">
          <div class="summary-item">
            <span>运行状态</span>
            <strong>{{ backfillStatus.running ? '运行中' : '空闲' }}</strong>
          </div>
          <div class="summary-item">
            <span>已处理</span>
            <strong>{{ backfillStatus.processedCount || 0 }}</strong>
          </div>
          <div class="summary-item">
            <span>新增</span>
            <strong>{{ backfillStatus.createdCount || 0 }}</strong>
          </div>
          <div class="summary-item">
            <span>更新</span>
            <strong>{{ backfillStatus.updatedCount || 0 }}</strong>
          </div>
        </div>
        <div class="backfill-meta">
          <div>开始时间：{{ backfillStatus.startTime || '-' }}</div>
          <div>结束时间：{{ backfillStatus.endTime || '-' }}</div>
          <div class="error-text" v-if="backfillStatus.lastError">错误信息：{{ backfillStatus.lastError }}</div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {CaretRight, Refresh, RefreshLeft, RefreshRight} from '@element-plus/icons-vue'
import {getUserInfo} from '@/api/user/user'
import {
  getTranscodeTaskSummary,
  recoverRunningTasks,
  retryAllFailedTasks,
  retryFailedTask
} from '@/api/fileServer/videoTranscodeAdmin'
import {getFileMappingBackfillStatus, startFileMappingBackfill} from '@/api/fileServer/fileAdmin'

const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => (currentUserInfo.value.authorities || []).includes('ADMIN'))
const loading = ref(false)
const recovering = ref(false)
const retryingAll = ref(false)
const summary = ref({})
const tasks = computed(() => summary.value.tasks || [])
const backfillLoading = ref(false)
const startingBackfill = ref(false)
const backfillStatus = ref({})
let backfillTimer = null

onMounted(() => {
  if (isAdmin.value) {
    loadTasks()
    loadBackfillStatus()
    backfillTimer = setInterval(loadBackfillStatus, 3000)
  }
})

onBeforeUnmount(() => {
  if (backfillTimer) {
    clearInterval(backfillTimer)
    backfillTimer = null
  }
})

const loadTasks = async () => {
  loading.value = true
  try {
    const response = await getTranscodeTaskSummary()
    summary.value = response.data || {}
  } finally {
    loading.value = false
  }
}

const retryOne = async (row) => {
  await ElMessageBox.confirm(`确认重试任务 ${row.taskId}？`, '重试转码任务', { type: 'warning' })
  await retryFailedTask(row.taskId)
  ElMessage.success('已移回等待队列')
  loadTasks()
}

const retryAllFailed = async () => {
  await ElMessageBox.confirm('确认重试全部失败任务？', '重试全部失败任务', { type: 'warning' })
  retryingAll.value = true
  try {
    const response = await retryAllFailedTasks()
    ElMessage.success(`已重试 ${response.data || 0} 个失败任务`)
    loadTasks()
  } finally {
    retryingAll.value = false
  }
}

const recoverRunning = async () => {
  await ElMessageBox.confirm('确认将 running 目录中的任务恢复到等待队列？适用于 worker 异常退出后的任务恢复。', '恢复运行中任务', { type: 'warning' })
  recovering.value = true
  try {
    const response = await recoverRunningTasks()
    ElMessage.success(`已恢复 ${response.data || 0} 个任务`)
    loadTasks()
  } finally {
    recovering.value = false
  }
}

const loadBackfillStatus = async () => {
  backfillLoading.value = true
  try {
    const response = await getFileMappingBackfillStatus()
    backfillStatus.value = response.data || {}
  } finally {
    backfillLoading.value = false
  }
}

const startBackfill = async () => {
  await ElMessageBox.confirm('确认启动 file_mapping 回填任务？任务会异步扫描 public/private 目录。', '启动回填', { type: 'warning' })
  startingBackfill.value = true
  try {
    await startFileMappingBackfill()
    ElMessage.success('回填任务已启动')
    loadBackfillStatus()
  } finally {
    startingBackfill.value = false
  }
}

const displayState = (row) => {
  if (row.state === 'FAILED' && row.message && row.message.includes('软链接')) {
    return 'SKIPPED'
  }
  return row.state || 'UNKNOWN'
}

const queueTagType = (queueState) => {
  if (queueState === 'FAILED') return 'danger'
  if (queueState === 'RUNNING') return 'warning'
  if (queueState === 'DONE') return 'success'
  return 'info'
}

const stateTagType = (row) => {
  const state = displayState(row)
  if (state === 'FAILED') return 'danger'
  if (state === 'SKIPPED') return 'warning'
  if (state === 'SUCCESS') return 'success'
  if (state === 'PROCESSING') return 'primary'
  return 'info'
}

const progressStatus = (row) => {
  if (displayState(row) === 'SUCCESS') return 'success'
  if (displayState(row) === 'FAILED') return 'exception'
  return undefined
}
</script>

<style scoped>
.transcode-page {
  padding: 20px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 16px;
}

h2 {
  margin: 0;
  font-size: 22px;
}

.subtitle,
.muted {
  color: #6b7280;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}

.summary-item span {
  display: block;
  color: #6b7280;
  font-size: 13px;
}

.summary-item strong {
  display: block;
  margin-top: 4px;
  font-size: 24px;
}

.summary-item.danger strong {
  color: #d93026;
}

.summary-item.skipped strong {
  color: #b7791f;
}

.task-table {
  width: 100%;
}

.backfill-card {
  margin-bottom: 16px;
}

.backfill-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.backfill-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.backfill-meta {
  margin-top: 10px;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.8;
}

.error-text {
  color: #d93026;
}

@media (max-width: 760px) {
  .toolbar {
    display: block;
  }

  .actions {
    margin-top: 12px;
    justify-content: flex-start;
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .backfill-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
