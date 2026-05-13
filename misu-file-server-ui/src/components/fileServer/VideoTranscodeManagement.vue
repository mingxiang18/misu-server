<template>
  <div class="transcode-page">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN 用户可以管理视频转码任务" />

    <template v-else>
      <div class="toolbar">
        <div>
          <h2>视频转码管理</h2>
          <div class="subtitle">DB 镜像 + 磁盘 reconcile：分页查看任务、批量重试失败 / 重新转码已完成视频</div>
        </div>
        <div class="actions">
          <el-button :icon="Refresh" @click="loadJobs" :loading="loading">刷新</el-button>
          <el-button type="warning" :icon="RefreshLeft" @click="recoverRunning" :loading="recovering">恢复 running</el-button>
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

      <el-card class="filter-card" shadow="never">
        <el-form :inline="!isMobile" :model="filter" class="filter-form" @submit.prevent>
          <el-form-item label="状态">
            <el-select v-model="filter.state" placeholder="全部状态" clearable class="filter-input">
              <el-option v-for="opt in STATE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="队列">
            <el-select v-model="filter.queueState" placeholder="全部队列" clearable class="filter-input">
              <el-option v-for="opt in QUEUE_STATE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="filter.keyword" placeholder="路径 / 任务ID 关键字" clearable class="filter-input filter-input-wide" @keyup.enter="applyFilter" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="applyFilter">查询</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <div class="batch-actions">
        <span class="batch-summary muted">已选 {{ selectedIds.length }} 项</span>
        <el-button
          type="danger"
          :icon="Top"
          :disabled="selectedIds.length === 0"
          :loading="prioritizing"
          @click="prioritizeBatchHandler"
        >
          设为最优先
        </el-button>
        <el-button
          type="primary"
          :icon="RefreshRight"
          :disabled="selectedIds.length === 0"
          :loading="retryingBatch"
          @click="retryBatchHandler"
        >
          批量重试失败
        </el-button>
        <el-button
          type="warning"
          :icon="VideoCamera"
          :disabled="selectedIds.length === 0"
          :loading="reTranscoding"
          @click="reTranscodeHandler"
        >
          重新转码所选
        </el-button>
      </div>

      <!-- 移动端：卡片列表（避免横向滚动） -->
      <div v-if="isMobile" v-loading="loading" class="list-card-stack">
        <div v-for="row in jobs" :key="row.taskId" class="list-card">
          <div class="list-card-header">
            <span class="list-card-title">
              <el-tooltip :content="row.taskId" placement="top">
                <span class="mono">{{ shortTaskId(row.taskId) }}</span>
              </el-tooltip>
            </span>
            <span style="display:inline-flex;gap:4px;flex-wrap:wrap;">
              <el-tag size="small" :type="stateTagType(row)">{{ displayState(row) }}</el-tag>
              <el-tag v-if="row.priority" size="small" type="danger" effect="dark">最优先</el-tag>
            </span>
          </div>
          <el-progress :percentage="row.progress || 0" :status="progressStatus(row)" />
          <div class="list-card-meta">
            <div class="list-card-meta-row">
              <span class="list-card-meta-label">队列</span>
              <el-tag size="small" :type="queueTagType(row.queueState)">{{ row.queueState || 'UNKNOWN' }}</el-tag>
            </div>
            <div class="list-card-meta-row">
              <span class="list-card-meta-label">源文件</span>
              <span class="list-card-meta-value" style="word-break: break-all;">
                {{ row.sourceVirtualPath || row.sourcePath || '-' }}
                <el-button
                  v-if="row.sourcePath"
                  size="small" link :icon="CopyDocument"
                  @click="copyPath(row.sourcePath)" />
              </span>
            </div>
            <div v-if="row.outputPath" class="list-card-meta-row">
              <span class="list-card-meta-label">输出 mp4</span>
              <span class="list-card-meta-value" style="word-break: break-all;">
                {{ row.outputPath }}
                <el-button size="small" link :icon="CopyDocument" @click="copyPath(row.outputPath)" />
              </span>
            </div>
            <div v-if="row.message" class="list-card-meta-row">
              <span class="list-card-meta-label">消息</span>
              <span class="list-card-meta-value">{{ row.message }}</span>
            </div>
            <div class="list-card-meta-row">
              <span class="list-card-meta-label">尝试</span>
              <span class="list-card-meta-value">{{ (row.enqueueCount || 1) }}× / 重 {{ row.retryCount || 0 }}</span>
            </div>
            <div class="list-card-meta-row">
              <span class="list-card-meta-label">更新</span>
              <span class="list-card-meta-value">{{ formatTime(row.updateTime) }}</span>
            </div>
          </div>
          <div class="list-card-actions" v-if="canPrioritize(row) || row.retryable || row.reTranscodeable">
            <el-button v-if="canPrioritize(row)" type="danger" link :icon="Top" @click="prioritizeOne(row)">置顶</el-button>
            <el-button v-if="row.retryable" type="primary" link :icon="RefreshRight" @click="retryOne(row)">重试</el-button>
            <el-button v-if="row.reTranscodeable" type="warning" link :icon="VideoCamera" @click="reTranscodeOne(row)">重转</el-button>
          </div>
        </div>
        <div v-if="!loading && jobs.length === 0" class="list-card-empty">暂无转码任务</div>
      </div>

      <!-- 桌面：表格 -->
      <el-table
        v-else
        :data="jobs"
        v-loading="loading"
        class="task-table"
        :max-height="tableMaxHeight"
        empty-text="暂无转码任务"
        @selection-change="onSelectionChange"
        row-key="taskId"
      >
        <el-table-column type="selection" width="48" reserve-selection />
        <el-table-column label="任务ID" width="130">
          <template #default="{ row }">
            <el-tooltip :content="row.taskId" placement="top">
              <span class="mono">{{ shortTaskId(row.taskId) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="队列" width="140">
          <template #default="{ row }">
            <div class="queue-cell">
              <el-tag :type="queueTagType(row.queueState)">{{ row.queueState || 'UNKNOWN' }}</el-tag>
              <el-tag v-if="row.priority" type="danger" size="small" effect="dark">最优先</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row)">{{ displayState(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :status="progressStatus(row)" />
          </template>
        </el-table-column>
        <el-table-column label="源文件" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="path-cell">
              <span>{{ row.sourceVirtualPath || row.sourcePath || '-' }}</span>
              <el-button
                v-if="row.sourcePath"
                size="small"
                link
                :icon="CopyDocument"
                @click="copyPath(row.sourcePath)"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="输出 mp4" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="path-cell" v-if="row.outputPath">
              <span>{{ row.outputPath }}</span>
              <el-button size="small" link :icon="CopyDocument" @click="copyPath(row.outputPath)" />
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="180" show-overflow-tooltip />
        <el-table-column label="尝试" width="90">
          <template #default="{ row }">
            <span class="muted">{{ (row.enqueueCount || 1) }}× / 重 {{ row.retryCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170">
          <template #default="{ row }">
            <span class="muted">{{ formatTime(row.updateTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canPrioritize(row)"
              type="danger"
              link
              :icon="Top"
              @click="prioritizeOne(row)"
            >置顶</el-button>
            <el-button v-if="row.retryable" type="primary" link :icon="RefreshRight" @click="retryOne(row)">重试</el-button>
            <el-button
              v-if="row.reTranscodeable"
              type="warning"
              link
              :icon="VideoCamera"
              @click="reTranscodeOne(row)"
            >重转</el-button>
            <span v-if="!row.retryable && !row.reTranscodeable && !canPrioritize(row)" class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        :layout="isMobile ? 'prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
        :small="isMobile"
        :pager-count="isMobile ? 5 : 7"
        background
        @current-change="loadJobs"
        @size-change="loadJobs"
      />

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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CaretRight, CopyDocument, Refresh, RefreshLeft, RefreshRight, Search, Top, VideoCamera } from '@element-plus/icons-vue'
import { getUserInfo } from '@/api/user/user'
import {
  getTranscodeJobs,
  getTranscodeTaskSummary,
  prioritizeTranscodeBatch,
  recoverRunningTasks,
  reTranscodeBatch,
  retryFailedTask,
  retryTranscodeBatch
} from '@/api/fileServer/videoTranscodeAdmin'
import { getFileMappingBackfillStatus, startFileMappingBackfill } from '@/api/fileServer/fileAdmin'
import { getWorkerState } from '@/api/fileServer/transcodeWorker'
import { useBreakpoint } from '@/composables/useBreakpoint'

const STATE_OPTIONS = [
  { value: 'WAITING', label: 'WAITING' },
  { value: 'PROCESSING', label: 'PROCESSING' },
  { value: 'SUCCESS', label: 'SUCCESS' },
  { value: 'FAILED', label: 'FAILED' },
  { value: 'TOO_LARGE', label: 'TOO_LARGE' },
  { value: 'UNSUPPORTED', label: 'UNSUPPORTED' },
  { value: 'NONE', label: 'NONE' }
]

const QUEUE_STATE_OPTIONS = [
  { value: 'WAITING', label: 'WAITING' },
  { value: 'RUNNING', label: 'RUNNING' },
  { value: 'FAILED', label: 'FAILED' },
  { value: 'DONE', label: 'DONE' },
  { value: 'UNKNOWN', label: 'UNKNOWN' }
]

const { isMobile } = useBreakpoint()
const tableMaxHeight = computed(() => (isMobile.value ? '50vh' : '60vh'))
const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => (currentUserInfo.value.authorities || []).includes('ADMIN'))
const loading = ref(false)
const recovering = ref(false)
const retryingBatch = ref(false)
const reTranscoding = ref(false)
const prioritizing = ref(false)

const summary = ref({})
const jobs = ref([])
const selectedRows = ref([])
const selectedIds = computed(() => selectedRows.value.map((r) => r.taskId))

const filter = reactive({ state: '', queueState: '', keyword: '' })
const pagination = reactive({ page: 1, size: 20, total: 0 })

const backfillLoading = ref(false)
const startingBackfill = ref(false)
const backfillStatus = ref({})
let backfillTimer = null

const workerState = ref({})
const workerOnline = ref(false)
let workerTimer = null
let jobsTimer = null

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

const loadSummary = async () => {
  try {
    const response = await getTranscodeTaskSummary()
    summary.value = response.data || {}
  } catch (e) {
    // summary 异常不阻塞主表
  }
}

const loadJobs = async () => {
  loading.value = true
  try {
    const response = await getTranscodeJobs({
      state: filter.state || undefined,
      queueState: filter.queueState || undefined,
      keyword: filter.keyword || undefined,
      page: pagination.page - 1,
      size: pagination.size
    })
    const page = response.data || {}
    // 后端 AjaxResult.success(Page) 走 PageResult.buildPageResult 包装：{ list, total, totalPages, pageSize, pageNumber }
    jobs.value = page.list || page.content || []
    pagination.total = page.total ?? page.totalElements ?? 0
  } finally {
    loading.value = false
  }
}

const applyFilter = () => {
  pagination.page = 1
  loadJobs()
}

const resetFilter = () => {
  filter.state = ''
  filter.queueState = ''
  filter.keyword = ''
  pagination.page = 1
  loadJobs()
}

onMounted(() => {
  if (isAdmin.value) {
    loadJobs()
    loadSummary()
    loadBackfillStatus()
    loadWorkerState()
    backfillTimer = setInterval(loadBackfillStatus, 3000)
    workerTimer = setInterval(loadWorkerState, 5000)
    jobsTimer = setInterval(() => {
      loadSummary()
      loadJobs()
    }, 8000)
  }
})

onBeforeUnmount(() => {
  if (backfillTimer) { clearInterval(backfillTimer); backfillTimer = null }
  if (workerTimer) { clearInterval(workerTimer); workerTimer = null }
  if (jobsTimer) { clearInterval(jobsTimer); jobsTimer = null }
})

const onSelectionChange = (rows) => {
  selectedRows.value = rows
}

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

const retryOne = async (row) => {
  await ElMessageBox.confirm(`确认重试任务 ${shortTaskId(row.taskId)}？`, '重试转码任务', { type: 'warning' })
  await retryFailedTask(row.taskId)
  ElMessage.success('已移回等待队列')
  loadJobs()
}

const retryBatchHandler = async () => {
  const ids = selectedIds.value
  if (ids.length === 0) return
  await ElMessageBox.confirm(`确认批量重试 ${ids.length} 个任务？仅处于 FAILED 队列的会被移回等待队列。`, '批量重试', { type: 'warning' })
  retryingBatch.value = true
  try {
    const response = await retryTranscodeBatch(ids)
    ElMessage.success(`已重试 ${response.data || 0} 个失败任务`)
    loadJobs()
  } finally {
    retryingBatch.value = false
  }
}

const reTranscodeOne = async (row) => {
  await ElMessageBox.confirm(`确认对 ${shortTaskId(row.taskId)} 重新转码？现有输出 mp4 / 预览 / 状态文件将被清理。`, '重新转码', { type: 'warning' })
  reTranscoding.value = true
  try {
    const response = await reTranscodeBatch([row.taskId])
    ElMessage.success(`已重排 ${response.data || 0} 个任务`)
    loadJobs()
  } finally {
    reTranscoding.value = false
  }
}

const reTranscodeHandler = async () => {
  const ids = selectedIds.value
  if (ids.length === 0) return
  await ElMessageBox.confirm(`确认对所选 ${ids.length} 个任务重新转码？现有输出文件将被清理后重排到队列。`, '批量重新转码', { type: 'warning' })
  reTranscoding.value = true
  try {
    const response = await reTranscodeBatch(ids)
    ElMessage.success(`已重排 ${response.data || 0} 个任务`)
    loadJobs()
  } finally {
    reTranscoding.value = false
  }
}

const canPrioritize = (row) => {
  if (!row) return false
  // RUNNING 已被 worker 抢走、TOO_LARGE / UNSUPPORTED 没有可调度的 task 文件，都不允许置顶
  if (row.queueState === 'RUNNING') return false
  if (row.state === 'TOO_LARGE' || row.state === 'UNSUPPORTED' || row.state === 'NONE') return false
  return true
}

const prioritizeOne = async (row) => {
  await ElMessageBox.confirm(
      `确认把 ${shortTaskId(row.taskId)} 提到队列最前？已运行的任务不会被抢占，DONE / FAILED 会先重排到等待队列。`,
      '设为最优先',
      { type: 'warning' }
  )
  prioritizing.value = true
  try {
    const response = await prioritizeTranscodeBatch([row.taskId])
    if (response.data) {
      ElMessage.success('已置顶')
    } else {
      ElMessage.warning('未能置顶（任务可能已在运行）')
    }
    loadJobs()
  } finally {
    prioritizing.value = false
  }
}

const prioritizeBatchHandler = async () => {
  const ids = selectedIds.value
  if (ids.length === 0) return
  await ElMessageBox.confirm(
      `确认把所选 ${ids.length} 个任务提到队列最前？已运行的任务不会被抢占；DONE / FAILED 会先重排到等待队列。`,
      '批量设为最优先',
      { type: 'warning' }
  )
  prioritizing.value = true
  try {
    const response = await prioritizeTranscodeBatch(ids)
    ElMessage.success(`已置顶 ${response.data || 0} 个任务`)
    loadJobs()
  } finally {
    prioritizing.value = false
  }
}

const recoverRunning = async () => {
  await ElMessageBox.confirm('确认将 running 目录中的任务恢复到等待队列？适用于 worker 异常退出后的任务恢复。', '恢复运行中任务', { type: 'warning' })
  recovering.value = true
  try {
    const response = await recoverRunningTasks()
    ElMessage.success(`已恢复 ${response.data || 0} 个任务`)
    loadJobs()
  } finally {
    recovering.value = false
  }
}

const copyPath = async (text) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制路径')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择')
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

const shortTaskId = (id) => (id ? id.slice(0, 8) : '-')

const formatTime = (value) => {
  if (!value) return '-'
  if (typeof value === 'string') return value.replace('T', ' ').slice(0, 19)
  return String(value)
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

.filter-card {
  margin-bottom: var(--space-3);
}

.filter-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  row-gap: var(--space-2);
}

.filter-input { min-width: 160px; }
.filter-input-wide { min-width: 240px; }

@media (max-width: 640px) {
  .filter-input, .filter-input-wide { min-width: 0; width: 100%; }
  .filter-form :deep(.el-form-item) { width: 100%; margin-right: 0; margin-bottom: var(--space-2); }
  .filter-form :deep(.el-form-item__content) { width: 100%; }
}

.batch-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}

.batch-summary {
  margin-right: var(--space-2);
}

.task-table {
  width: 100%;
}

.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: var(--font-size-sm);
}

.path-cell {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  word-break: break-all;
}

.queue-cell {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-wrap: wrap;
}

.pager {
  margin-top: var(--space-3);
  display: flex;
  justify-content: flex-end;
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
