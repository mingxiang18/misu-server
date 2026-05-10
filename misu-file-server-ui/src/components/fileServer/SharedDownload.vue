<template>
  <div class="shared-page">
    <div class="shared-card">
      <div class="shared-brand">
        <span class="shared-logo">M</span>
        <span class="shared-brand-name">misu</span>
      </div>

      <div v-if="loading" class="shared-loading">
        <el-icon class="is-loading"><Loading/></el-icon>
        <span>加载分享信息...</span>
      </div>

      <template v-else-if="info">
        <!-- 失效态 -->
        <div v-if="info.revoked || info.expired || info.exhausted" class="shared-invalid">
          <Warning class="shared-invalid-icon"/>
          <p class="shared-invalid-title">{{ invalidReason }}</p>
          <p class="shared-invalid-hint">请联系分享者获取新的链接。</p>
        </div>

        <!-- 正常态 -->
        <template v-else>
          <div class="shared-file">
            <component :is="fileIcon" class="shared-file-icon"/>
            <div class="shared-file-meta">
              <div class="shared-file-name" :title="info.fileName">{{ info.fileName }}</div>
              <div class="shared-file-info">
                {{ formatType(info.fileType) }} · {{ formatSize(info.fileSize) }}
              </div>
            </div>
          </div>

          <el-form v-if="info.requirePassword"
                   :model="form"
                   class="shared-form"
                   label-position="top"
                   @submit.prevent="onDownload">
            <el-form-item label="访问密码" required>
              <el-input v-model="form.password"
                        type="password"
                        size="large"
                        show-password
                        placeholder="请输入访问密码"
                        autocomplete="current-password"
                        @keyup.enter="onDownload"
                        @input="errorMsg = ''"/>
            </el-form-item>
          </el-form>

          <div v-if="errorMsg" class="shared-error">
            <Warning class="shared-error-icon"/>
            <span>{{ errorMsg }}</span>
          </div>

          <div class="shared-actions">
            <el-button type="primary" size="large" :loading="downloading" @click="onDownload">
              <Download style="width: 18px; height: 18px; margin-right: 6px;"/>
              下载
            </el-button>
          </div>

          <div class="shared-footer">
            <span v-if="info.expireTime">将于 {{ formatTime(info.expireTime) }} 过期</span>
            <span v-if="info.maxDownloads != null"> · 已下载 {{ info.downloadCount }} / {{ info.maxDownloads }}</span>
          </div>
        </template>
      </template>

      <div v-else class="shared-invalid">
        <Warning class="shared-invalid-icon"/>
        <p class="shared-invalid-title">分享不存在或已失效</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Loading, Warning, Download, Folder, Picture, Film, Document } from '@element-plus/icons-vue';
import { getSharedInfo, buildSharedDownloadUrl } from '@/api/fileServer/fileShareApi';

const route = useRoute();
const token = ref(String(route.params.token || ''));
const info = ref(null);
const loading = ref(true);
const downloading = ref(false);
const form = ref({ password: '' });
const errorMsg = ref('');

const fileIcon = computed(() => {
  switch (info.value?.fileType) {
    case 'directory': return Folder;
    case 'image': return Picture;
    case 'video': return Film;
    default: return Document;
  }
});

const invalidReason = computed(() => {
  if (!info.value) return '分享不存在';
  if (info.value.revoked) return '分享已被撤销';
  if (info.value.expired) return '分享已过期';
  if (info.value.exhausted) return '下载次数已用完';
  return '分享已失效';
});

const formatType = (t) => ({ directory: '目录', image: '图片', video: '视频', other: '文件' })[t] || (t || '文件');
const formatSize = (b) => {
  if (b == null) return '';
  if (b >= 1024 * 1024 * 1024) return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`;
  if (b >= 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${b} B`;
};
const formatTime = (t) => String(t || '').replace('T', ' ').slice(0, 19);

const reload = () => {
  loading.value = true;
  getSharedInfo(token.value)
      .then(resp => { info.value = resp.data; })
      .catch(() => { info.value = null; })
      .finally(() => { loading.value = false; });
};

const onDownload = async () => {
  errorMsg.value = '';
  if (info.value?.requirePassword && !form.value.password) {
    errorMsg.value = '请输入访问密码';
    return;
  }
  downloading.value = true;
  try {
    const url = buildSharedDownloadUrl(token.value, form.value.password);
    const resp = await fetch(url);
    if (!resp.ok) {
      const text = await resp.text();
      errorMsg.value = `下载失败：${resp.status} ${text.slice(0, 80)}`;
      return;
    }
    // 后端 AjaxResult 错误也可能 HTTP 200 — 检查 content-type 是否为 application/json
    const ct = resp.headers.get('content-type') || '';
    if (ct.includes('application/json')) {
      const data = await resp.json();
      if (data?.code !== 200) {
        errorMsg.value = data?.msg || '下载失败';
        return;
      }
    }
    const blob = await resp.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = info.value?.fileName || 'download';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(blobUrl);
    // 重新拉一次 info 反映 downloadCount + 清错误
    reload();
  } catch (e) {
    errorMsg.value = `下载异常：${e.message || e}`;
  } finally {
    downloading.value = false;
  }
};

onMounted(reload);
</script>

<style scoped>
.shared-page {
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-base);
  padding: 24px;
}

@supports not (height: 100dvh) {
  .shared-page { min-height: 100vh; }
}

.shared-card {
  width: 100%;
  max-width: 440px;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.06);
}

.shared-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
}

.shared-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}

.shared-brand-name {
  font-weight: 600;
  font-size: 16px;
  color: var(--color-text-primary);
}

.shared-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--color-text-secondary);
  padding: 24px 0;
  justify-content: center;
}

.shared-file {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: var(--color-bg-base);
  border-radius: 12px;
  margin-bottom: 20px;
}

.shared-file-icon {
  width: 36px;
  height: 36px;
  color: var(--accent);
  flex-shrink: 0;
}

.shared-file-meta {
  flex: 1 1 auto;
  min-width: 0;
}

.shared-file-name {
  font-weight: 500;
  font-size: 16px;
  color: var(--color-text-primary);
  word-break: break-all;
}

.shared-file-info {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-top: 4px;
}

.shared-form {
  margin-bottom: 12px;
}

.shared-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: rgba(220, 38, 38, 0.08);
  border-left: 3px solid var(--color-danger);
  border-radius: 4px;
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: 14px;
}

.shared-error-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.shared-actions {
  display: flex;
  margin-bottom: 14px;
}

.shared-actions .el-button {
  flex: 1 1 auto;
}

.shared-footer {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.shared-invalid {
  text-align: center;
  padding: 32px 16px;
}

.shared-invalid-icon {
  width: 48px;
  height: 48px;
  color: var(--color-danger);
  margin-bottom: 12px;
}

.shared-invalid-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 500;
  color: var(--color-text-primary);
}

.shared-invalid-hint {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>
