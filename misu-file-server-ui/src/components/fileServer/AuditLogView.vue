<template>
  <div class="audit-view">
    <div class="audit-header">
      <div class="audit-title">
        <Histogram class="audit-title-icon"/>
        <div>
          <div class="audit-title-text">审计日志</div>
          <div class="audit-title-hint">所有写操作（上传 / 删除 / 移动 / 分享 / 还原 …）的实时记录。仅管理员可见。</div>
        </div>
      </div>
      <el-button @click="loadList(pageNumber)" :loading="loading">刷新</el-button>
    </div>

    <div class="audit-filter">
      <el-select v-model="filter.actionType" placeholder="操作类型" clearable style="width: 200px;">
        <el-option v-for="opt in actionOptions" :key="opt.value" :label="opt.label" :value="opt.value"/>
      </el-select>
      <el-input v-model="filter.userId" placeholder="操作者 userId" clearable style="width: 180px;"/>
      <el-date-picker
          v-model="filter.range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 360px;"/>
      <el-button type="primary" @click="loadList(1)" :loading="loading">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <div class="audit-body" v-loading="loading">
      <el-table :data="items" stripe class="audit-table" :empty-text="loading ? '加载中...' : '暂无日志'">
        <el-table-column prop="createTime" label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="actionType" label="操作" width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="actionTagType(row.actionType)">{{ actionLabel(row.actionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="用户" width="140">
          <template #default="{ row }">
            <span :title="`userId=${row.userId}`">{{ row.userName || row.userId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="targetVirtualPath" label="目标" min-width="240">
          <template #default="{ row }">
            <span class="audit-cell-target">
              <span v-if="row.targetOpenType != null" class="audit-scope">
                {{ row.targetOpenType === 1 ? '公共' : '私人' }}
              </span>
              <span :title="row.targetVirtualPath">/{{ row.targetVirtualPath || '' }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.statusCode === 200 ? 'success' : 'danger'">
              {{ row.statusCode === 200 ? '成功' : (row.statusCode + ' 失败') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="200">
          <template #default="{ row }">
            <span style="color: var(--color-danger);" v-if="row.errorMessage">{{ row.errorMessage }}</span>
            <span style="color: var(--color-text-tertiary);" v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140"/>
      </el-table>

      <div v-if="total > 0" class="audit-pagination">
        <el-pagination
            background
            layout="total, prev, pager, next, jumper"
            :total="total"
            :page-size="pageSize"
            :current-page="pageNumber"
            @current-change="loadList"/>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { Histogram } from '@element-plus/icons-vue';
import { listAuditLogs } from '@/api/fileServer/auditLogApi';

const items = ref([]);
const total = ref(0);
const pageNumber = ref(1);
const pageSize = ref(50);
const loading = ref(false);

const filter = ref({
  actionType: '',
  userId: '',
  range: null,  // [since, until]
});

const actionOptions = [
  { value: 'UPLOAD_FILE', label: '上传文件' },
  { value: 'DELETE_FILE', label: '删除文件' },
  { value: 'MOVE_FILE', label: '移动文件' },
  { value: 'CREATE_DIR', label: '创建目录' },
  { value: 'BATCH_DELETE', label: '批量删除' },
  { value: 'BATCH_MOVE', label: '批量移动' },
  { value: 'RESTORE_TRASH', label: '回收站还原' },
  { value: 'PURGE_TRASH', label: '永久删除' },
  { value: 'SHARE_TO_PUBLIC', label: '私→公共享' },
  { value: 'SHARE_CREATE', label: '创建外链' },
  { value: 'SHARE_REVOKE', label: '撤销外链' },
  { value: 'HASH_INSTANT_UPLOAD', label: '哈希秒传' },
];

const actionLabelMap = Object.fromEntries(actionOptions.map(o => [o.value, o.label]));
const actionLabel = (a) => actionLabelMap[a] || a;
const actionTagType = (a) => {
  if (!a) return '';
  if (a.includes('DELETE') || a === 'PURGE_TRASH' || a === 'SHARE_REVOKE') return 'danger';
  if (a === 'UPLOAD_FILE' || a === 'CREATE_DIR' || a === 'SHARE_CREATE' || a === 'HASH_INSTANT_UPLOAD') return 'success';
  if (a === 'MOVE_FILE' || a === 'BATCH_MOVE' || a === 'RESTORE_TRASH') return 'warning';
  return 'info';
};

const loadList = (page = 1) => {
  loading.value = true;
  pageNumber.value = page;
  const q = { pageNumber: page, pageSize: pageSize.value };
  if (filter.value.actionType) q.actionType = filter.value.actionType;
  if (filter.value.userId) q.userId = filter.value.userId;
  if (filter.value.range && filter.value.range.length === 2) {
    q.since = filter.value.range[0];
    q.until = filter.value.range[1];
  }
  listAuditLogs(q)
      .then(resp => {
        items.value = resp.data?.items || [];
        total.value = resp.data?.total || 0;
      })
      .catch(() => {})
      .finally(() => { loading.value = false; });
};

const resetFilter = () => {
  filter.value = { actionType: '', userId: '', range: null };
  loadList(1);
};

const formatTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '';

onMounted(() => loadList(1));
</script>

<style scoped>
.audit-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}

.audit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-border-subtle);
}

.audit-title {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.audit-title-icon {
  width: 28px;
  height: 28px;
  color: var(--accent);
}

.audit-title-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.audit-title-hint {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-top: 2px;
}

.audit-filter {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  align-items: center;
  border-bottom: 1px solid var(--color-border-subtle);
  flex-wrap: wrap;
}

.audit-body {
  flex: 1 1 auto;
  overflow: auto;
  padding: var(--space-4) var(--space-5);
}

.audit-table {
  background: var(--color-bg-surface);
  border-radius: var(--radius-md);
}

.audit-cell-target {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: ui-monospace, monospace;
  font-size: 13px;
}

.audit-scope {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-family: var(--font-family-base);
}

.audit-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}
</style>
