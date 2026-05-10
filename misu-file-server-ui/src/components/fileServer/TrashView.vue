<template>
  <div class="trash-view">
    <div class="trash-header">
      <div class="trash-title">
        <Delete class="trash-title-icon"/>
        <div>
          <div class="trash-title-text">回收站</div>
          <div class="trash-title-hint">最近 {{ retentionDays }} 天内删除的文件可在此还原；之后会被自动清理。</div>
        </div>
      </div>
      <div class="trash-actions">
        <el-radio-group v-model="openType" @change="loadTrash(1)">
          <el-radio-button :value="0">私人</el-radio-button>
          <el-radio-button :value="1">公共</el-radio-button>
        </el-radio-group>
        <el-button @click="loadTrash(pageNumber)" :loading="loading">刷新</el-button>
      </div>
    </div>

    <div class="trash-body" v-loading="loading">
      <div v-if="!loading && items.length === 0" class="trash-empty">
        <Delete class="trash-empty-icon"/>
        <p class="trash-empty-title">回收站是空的</p>
        <p class="trash-empty-hint">删除的文件会先放在这里 {{ retentionDays }} 天，确认要清理时再永久删除。</p>
      </div>

      <el-table v-else :data="items" stripe class="trash-table" empty-text="回收站是空的">
        <el-table-column prop="fileName" label="文件名" min-width="220">
          <template #default="{ row }">
            <div class="trash-cell-name">
              <component :is="iconFor(row.fileType)" class="trash-cell-icon"/>
              <span class="trash-cell-text">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="原位置" min-width="200">
          <template #default="{ row }">
            <span class="trash-cell-path">/{{ row.originalParentPath || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="100">
          <template #default="{ row }">{{ typeLabel(row.fileType) }}</template>
        </el-table-column>
        <el-table-column prop="fileSize" label="大小" width="110">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="deleteTime" label="删除时间" width="180">
          <template #default="{ row }">{{ formatTime(row.deleteTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="restoreItem(row)">还原</el-button>
            <el-button size="small" type="danger" link @click="purgeItem(row)">永久删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > 0" class="trash-pagination">
        <el-pagination
            background
            layout="total, prev, pager, next, jumper"
            :total="total"
            :page-size="pageSize"
            :current-page="pageNumber"
            @current-change="loadTrash"/>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { Delete, Folder, Picture, Film, Document } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listTrash, restoreTrash, purgeTrash } from '@/api/fileServer/fileServerMvp';

const retentionDays = 7;

const openType = ref(0);
const items = ref([]);
const total = ref(0);
const pageNumber = ref(1);
const pageSize = ref(20);
const loading = ref(false);

const loadTrash = (page = 1) => {
  loading.value = true;
  pageNumber.value = page;
  listTrash({ openType: openType.value, pageNumber: page, pageSize: pageSize.value })
      .then(resp => {
        items.value = resp.data?.items || [];
        total.value = resp.data?.total || 0;
      })
      .catch(() => {})
      .finally(() => { loading.value = false; });
};

const restoreItem = (row) => {
  ElMessageBox.confirm(
      `确认将【${row.fileName}】还原到 /${row.originalParentPath || ''} ？`,
      '还原',
      { confirmButtonText: '确认还原', cancelButtonText: '取消' }
  ).then(() => {
    restoreTrash(row.id).then(() => {
      ElMessage.success('已还原');
      loadTrash(pageNumber.value);
    });
  }).catch(() => {});
};

const purgeItem = (row) => {
  ElMessageBox.confirm(
      `永久删除【${row.fileName}】，删除后不可恢复，是否继续？`,
      '永久删除',
      { confirmButtonText: '永久删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => {
    purgeTrash(row.id).then(() => {
      ElMessage.success('已永久删除');
      loadTrash(pageNumber.value);
    });
  }).catch(() => {});
};

const iconFor = (t) => t === 'directory' ? Folder : t === 'image' ? Picture : t === 'video' ? Film : Document;
const typeLabel = (t) => ({ directory: '目录', image: '图片', video: '视频', other: '文件' })[t] || t;
const formatSize = (b) => {
  if (b == null) return '-';
  if (b >= 1024 * 1024 * 1024) return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`;
  if (b >= 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${b} B`;
};
const formatTime = (t) => {
  if (!t) return '-';
  return String(t).replace('T', ' ').slice(0, 19);
};

onMounted(() => loadTrash(1));
</script>

<style scoped>
.trash-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}

.trash-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-border-subtle);
}

.trash-title {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.trash-title-icon {
  width: 28px;
  height: 28px;
  color: var(--accent);
  flex-shrink: 0;
}

.trash-title-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.trash-title-hint {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-top: 2px;
}

.trash-actions {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  flex-shrink: 0;
}

.trash-body {
  flex: 1 1 auto;
  overflow: auto;
  padding: var(--space-4) var(--space-5);
}

.trash-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: var(--space-12) var(--space-4);
  min-height: 360px;
  color: var(--color-text-secondary);
}

.trash-empty-icon {
  font-size: 64px;
  color: var(--color-border-strong);
  margin-bottom: var(--space-2);
  width: 64px;
  height: 64px;
}

.trash-empty-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.trash-empty-hint {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-sm);
}

.trash-table {
  background: var(--color-bg-surface);
  border-radius: var(--radius-md);
}

.trash-cell-name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.trash-cell-icon {
  width: 18px;
  height: 18px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.trash-cell-text {
  word-break: break-all;
}

.trash-cell-path {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  font-family: ui-monospace, monospace;
}

.trash-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}

@media (max-width: 640px) {
  .trash-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
