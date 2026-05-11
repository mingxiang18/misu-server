<template>
  <div class="text-viewer">
    <div class="text-toolbar">
      <el-button @click="goBack" size="small" :icon="ArrowLeft">返回</el-button>
      <span class="text-name" :title="filePath">{{ filePath || '文本文件' }}</span>
      <span class="text-meta" v-if="meta">{{ meta }}</span>

      <div class="toolbar-spacer"></div>

      <el-button v-if="!editing && !readonly" size="small" type="primary" :icon="Edit" @click="enterEdit">编辑</el-button>
      <template v-else-if="editing">
        <el-button size="small" @click="cancelEdit" :icon="Close">取消</el-button>
        <el-button size="small" type="primary" :icon="Check" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </div>

    <div v-if="binaryWarning" class="text-warn">
      <Warning class="text-warn-icon"/>
      <span>文件含二进制内容，已按 UTF-8 解码。保存可能损坏原文件，建议改用下载。</span>
    </div>

    <div class="text-body" v-loading="loading">
      <textarea
          v-if="content !== null"
          v-model="content"
          :readonly="!editing"
          spellcheck="false"
          class="text-area"
          :class="{ readonly: !editing }"></textarea>
      <div v-else-if="!loading && errorMsg" class="text-empty">
        <Warning class="text-empty-icon"/>
        <p>{{ errorMsg }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Edit, Check, Close, Warning } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { getTextContent, saveTextContent } from '@/api/fileServer/fileServerMvp';

const route = useRoute();
const router = useRouter();

const openType = ref(Number(route.query.openType ?? 0));
const filePath = ref(String(route.query.filePath || ''));
const readonly = computed(() => openType.value === 1); // 公共目录默认只读（写需要 admin；按钮 hide）

const content = ref(null);
const originalContent = ref('');
const editing = ref(false);
const loading = ref(true);
const saving = ref(false);
const errorMsg = ref('');
const sizeBytes = ref(0);
const binaryWarning = ref(false);

const meta = computed(() => {
  if (sizeBytes.value == null) return '';
  const kb = (sizeBytes.value / 1024).toFixed(1);
  return `${kb} KB · ${editing.value ? '编辑中' : '只读'}`;
});

const load = () => {
  loading.value = true;
  errorMsg.value = '';
  getTextContent({ openType: openType.value, filePath: filePath.value })
      .then(resp => {
        content.value = resp.data?.content ?? '';
        originalContent.value = content.value;
        sizeBytes.value = resp.data?.sizeBytes ?? 0;
        binaryWarning.value = !!resp.data?.binaryLikely;
      })
      .catch(err => {
        errorMsg.value = err?.message || '加载失败';
      })
      .finally(() => { loading.value = false; });
};

const enterEdit = () => { editing.value = true; };
const cancelEdit = () => {
  editing.value = false;
  content.value = originalContent.value;
};

const onSave = () => {
  if (content.value === originalContent.value) {
    editing.value = false;
    ElMessage.info('内容未修改');
    return;
  }
  saving.value = true;
  saveTextContent({ openType: openType.value, filePath: filePath.value, content: content.value })
      .then(() => {
        ElMessage.success('保存成功');
        originalContent.value = content.value;
        editing.value = false;
        // 重新拉一次拿最新 sizeBytes
        load();
      })
      .finally(() => { saving.value = false; });
};

const goBack = () => {
  if (window.history.length > 1) router.back();
  else router.push('/fileServer/privateDirectory');
};

onMounted(load);
</script>

<style scoped>
.text-viewer {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: var(--color-bg-base);
}

@supports not (height: 100dvh) {
  .text-viewer { height: 100vh; }
}

.text-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-surface);
  flex-shrink: 0;
}

.text-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-primary);
  word-break: break-all;
}

.text-meta {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-left: 4px;
}

.toolbar-spacer {
  flex: 1 1 auto;
}

.text-warn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: rgba(245, 158, 11, 0.12);
  border-bottom: 1px solid rgba(245, 158, 11, 0.3);
  color: #92400e;
  font-size: 12px;
}

.text-warn-icon {
  width: 16px;
  height: 16px;
}

.text-body {
  flex: 1 1 0;
  min-height: 0;
  padding: 16px;
  overflow: hidden;
  display: flex;
}

.text-area {
  flex: 1 1 0;
  width: 100%;
  height: 100%;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  font-family: ui-monospace, SF Mono, Menlo, Consolas, monospace;
  font-size: 14px;
  line-height: 1.6;
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  resize: none;
  outline: none;
  box-sizing: border-box;
  white-space: pre;
  overflow: auto;
}

.text-area.readonly {
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  cursor: default;
}

.text-area:focus {
  border-color: var(--accent);
}

.text-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1 1 0;
  color: var(--color-text-secondary);
  gap: 8px;
}

.text-empty-icon {
  width: 36px;
  height: 36px;
  color: var(--color-danger);
}
</style>
