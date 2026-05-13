<template>
  <div class="shares-view">
    <div class="shares-header">
      <div class="shares-title">
        <Share class="shares-title-icon"/>
        <div>
          <div class="shares-title-text">我的分享</div>
          <div class="shares-title-hint">查看、复制链接或撤销已创建的外链分享。</div>
        </div>
      </div>
      <el-button @click="loadList(pageNumber)" :loading="loading">刷新</el-button>
    </div>

    <div class="shares-body" v-loading="loading">
      <div v-if="!loading && items.length === 0" class="shares-empty">
        <Share class="shares-empty-icon"/>
        <p class="shares-empty-title">还没有分享</p>
        <p class="shares-empty-hint">在文件上右键 → 外链分享，可以创建公开链接，支持过期 / 密码 / 下载次数。</p>
      </div>

      <el-table v-else :data="items" stripe class="shares-table">
        <el-table-column prop="fileName" label="文件名" min-width="200">
          <template #default="{ row }">
            <div class="shares-cell-name">
              <component :is="iconFor(row.fileType)" class="shares-cell-icon"/>
              <span :title="row.fileName">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.revoked" type="info" size="small">已撤销</el-tag>
            <el-tag v-else-if="row.expired" type="warning" size="small">已过期</el-tag>
            <el-tag v-else-if="row.exhausted" type="warning" size="small">已用完</el-tag>
            <el-tag v-else type="success" size="small">有效</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" label="保护" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.hasPassword" size="small" type="primary">需要密码</el-tag>
            <span v-else style="color:var(--color-text-tertiary);">无</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" label="下载次数" width="120">
          <template #default="{ row }">
            <span>{{ row.downloadCount }}</span>
            <span v-if="row.maxDownloads != null" style="color:var(--color-text-tertiary);"> / {{ row.maxDownloads }}</span>
            <span v-else style="color:var(--color-text-tertiary);"> / ∞</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="expireTime" label="过期时间" width="180">
          <template #default="{ row }">{{ formatTime(row.expireTime) }}</template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="createTime" label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="isMobile ? 130 : 200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="copyLink(row)">复制</el-button>
            <el-button size="small" type="danger" link @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > 0" class="shares-pagination">
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
import { Share, Folder, Picture, Film, Document } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listShares, revokeShare } from '@/api/fileServer/fileShareApi';
import { useBreakpoint } from '@/composables/useBreakpoint';

const { isMobile } = useBreakpoint();

const items = ref([]);
const total = ref(0);
const pageNumber = ref(1);
const pageSize = ref(20);
const loading = ref(false);

const loadList = (page = 1) => {
  loading.value = true;
  pageNumber.value = page;
  listShares({ pageNumber: page, pageSize: pageSize.value })
      .then(resp => {
        items.value = resp.data?.items || [];
        total.value = resp.data?.total || 0;
      })
      .catch(() => {})
      .finally(() => { loading.value = false; });
};

const buildPublicUrl = (token) => {
  const base = window.location.origin;
  return `${base}/share/${token}`;
};

const copyLink = async (row) => {
  const link = buildPublicUrl(row.shareToken);
  try {
    await navigator.clipboard.writeText(link);
    ElMessage.success('外链已复制到剪贴板');
  } catch (e) {
    ElMessageBox.alert(`<a style="word-break: break-all;">${link}</a>`, '外链', { dangerouslyUseHTMLString: true });
  }
};

const revoke = (row) => {
  ElMessageBox.confirm(
      `确认撤销【${row.fileName}】的分享？撤销后链接立即失效。`,
      '撤销分享',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' }
  ).then(() => {
    revokeShare(row.id).then(() => {
      ElMessage.success('已撤销');
      loadList(pageNumber.value);
    });
  }).catch(() => {});
};

const iconFor = (t) => t === 'directory' ? Folder : t === 'image' ? Picture : t === 'video' ? Film : Document;
const formatTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '-';

onMounted(() => loadList(1));
</script>

<style scoped>
.shares-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}

.shares-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-border-subtle);
}

@media (max-width: 640px) {
  .shares-header {
    padding: var(--space-3);
    flex-wrap: wrap;
  }
  .shares-title-icon { width: 22px; height: 22px; }
  .shares-title-text { font-size: var(--font-size-base); }
  .shares-title-hint { display: none; }
  .shares-body { padding: var(--space-2) var(--space-3) var(--space-12); }
}

.shares-title {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.shares-title-icon {
  width: 28px;
  height: 28px;
  color: var(--accent);
}

.shares-title-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.shares-title-hint {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-top: 2px;
}

.shares-body {
  flex: 1 1 auto;
  overflow: auto;
  padding: var(--space-4) var(--space-5);
}

.shares-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-12) var(--space-4);
  min-height: 360px;
  text-align: center;
  color: var(--color-text-secondary);
}

.shares-empty-icon {
  width: 64px;
  height: 64px;
  color: var(--color-border-strong);
  margin-bottom: var(--space-2);
}

.shares-empty-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.shares-empty-hint {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-sm);
}

.shares-table {
  background: var(--color-bg-surface);
  border-radius: var(--radius-md);
}

.shares-cell-name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.shares-cell-icon {
  width: 18px;
  height: 18px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.shares-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}
</style>
