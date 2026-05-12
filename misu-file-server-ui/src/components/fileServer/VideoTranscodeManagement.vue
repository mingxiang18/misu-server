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

      <el-card class="worker-card" :class="workerCardClass" shadow="never">
        <template #header>
          <div class="worker-card-header">
            <div class="worker-card-title">
              <span class="worker-dot" :class="workerStatusDotClass"></span>
              <strong>Worker 状态</strong>
              <el-tag size="small" :type="workerStatusTagType">{{ workerStatusLabel }}</el-tag>
            </div>
            <div class="muted worker-card-updated" v-if="workerState.updatedAt">
              更新于 {{ workerState.updatedAt }}
            </div>
          </div>
        </template>
        <div class="worker-grid">
          <div class="worker-cell">
            <span>编码器</span>
            <strong>{{ workerState.encoder || '-' }}</strong>
          </div>
          <div class="worker-cell">
            <span>当前任务</span>
            <strong class="worker-current-task">{{ workerState.currentTask?.taskId || '空闲' }}</strong>
          </div>
          <div class="worker-cell worker-cell-progress" v-if="workerState.currentTask">
            <span>进度</span>
            <el-progress
              :percentage="workerState.currentTask?.progress || 0"
              :stroke-width="10"
            />
          </div>
          <div class="worker-cell">
            <span>消息</span>
            <strong class="worker-message">{{ workerState.message || '-' }}</strong>
          </div>
        </div>
      </el-card>

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

      <el-table :data="tasks" v-loading="loading" class="task-table" :max-height="tableMaxHeight" empty-text="暂无转码任务">
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
import {getWorkerState} from '@/api/fileServer/transcodeWorker'
import { useBreakpoint } from '@/composables/useBreakpoint'

const { isMobile } = useBreakpoint()
const tableMaxHeight = computed(() => (isMobile.value ? '50vh' : '60vh'))
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

const workerState = ref({})
const workerOnline = ref(false)
let workerTimer = null

const loadWorkerState = async () => {
  try {
    const response = await getWorkerState()
    workerState.value = response.data || {}
    workerOnline.value = true
  } catch (e) {
    // 502 / 5xx 表示 worker pod 不可达；保留上次 state，仅翻 online 旗
    workerOnline.value = false
  }
}

onMounted(() => {
  if (isAdmin.value) {
    loadTasks()
    loadBackfillStatus()
    loadWorkerState()
    backfillTimer = setInterval(loadBackfillStatus, 3000)
    workerTimer = setInterval(loadWorkerState, 5000)
  }
})

onBeforeUnmount(() => {
  if (backfillTimer) {
    clearInterval(backfillTimer)
    backfillTimer = null
  }
  if (workerTimer) {
    clearInterval(workerTimer)
    workerTimer = null
  }
})

const workerStatusLabel = computed(() => {
  if (!workerOnline.value) return 'OFFLINE'
  const s = workerState.value.workerState
  if (s === 'RUNNING') return '转码中'
  if (s === 'IDLE') return '空闲'
  if (s === 'STARTING') return '启动中'
  return s || '未知'
})

const workerStatusTagType = computed(() => {
  if (!workerOnline.value) return 'danger'
  const s = workerState.value.workerState
  if (s === 'RUNNING') return 'warning'
  if (s === 'IDLE') return 'success'
  return 'info'
})

const workerStatusDotClass = computed(() => {
  if (!workerOnline.value) return 'dot-offline'
  if (workerState.value.workerState === 'RUNNING') return 'dot-running'
  return 'dot-idle'
})

const workerCardClass = computed(() => (workerOnline.value ? '' : 'worker-card-offline'))

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
  padding: var(--space-5);
}

@media (max-width: 640px) {
  .transcode-page {
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
  line-height: var(--line-height-tight);
}

.subtitle,
.muted {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}

.worker-card {
  margin-bottom: var(--space-3);
}

.worker-card-offline {
  border-color: var(--color-danger);
}

.worker-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.worker-card-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.worker-card-updated {
  font-size: var(--font-size-xs);
}

.worker-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.dot-idle { background: var(--color-success); }
.dot-running {
  background: var(--color-warning);
  animation: worker-pulse 1.4s ease-in-out infinite;
}
.dot-offline { background: var(--color-danger); }

@keyframes worker-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.55; transform: scale(0.8); }
}

.worker-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
}

.worker-cell span {
  display: block;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.worker-cell strong {
  display: block;
  margin-top: var(--space-1);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
  word-break: break-all;
}

.worker-current-task,
.worker-message {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-tight);
}

.worker-cell-progress {
  grid-column: span 2;
}

.summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--space-3);
  margin-bottom: var(--space-4);
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
  font-size: var(--font-size-2xl);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.summary-item.danger strong {
  color: var(--color-danger);
}

.summary-item.skipped strong {
  color: var(--color-warning);
}

.task-table {
  width: 100%;
}

.backfill-card {
  margin-top: var(--space-4);
  margin-bottom: var(--space-4);
}

.backfill-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.backfill-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
}

.backfill-meta {
  margin-top: var(--space-3);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  line-height: var(--line-height-relaxed);
}

.error-text {
  color: var(--color-danger);
}

@media (max-width: 640px) {
  .toolbar {
    display: block;
  }

  .actions {
    margin-top: var(--space-3);
    justify-content: flex-start;
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .backfill-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .worker-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .worker-cell-progress {
    grid-column: span 2;
  }
}
</style>
