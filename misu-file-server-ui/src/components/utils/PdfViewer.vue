<template>
  <div class="pdf-viewer">
    <div class="pdf-toolbar">
      <el-button @click="goBack" size="small" :icon="ArrowLeft">返回</el-button>
      <span class="pdf-name">{{ fileName }}</span>
      <el-button v-if="url" size="small" :icon="Download" @click="downloadPdf">下载</el-button>
    </div>
    <iframe v-if="url"
            :src="url"
            class="pdf-frame"
            title="PDF 预览"></iframe>
    <div v-else class="pdf-empty">
      <el-icon class="pdf-empty-icon"><Document/></el-icon>
      <p>无 PDF 链接</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Download, Document } from '@element-plus/icons-vue';

const route = useRoute();
const router = useRouter();

const url = computed(() => String(route.query.url || ''));
const fileName = computed(() => String(route.query.name || 'PDF 文档'));

const goBack = () => {
  if (window.history.length > 1) router.back();
  else router.push('/fileServer/privateDirectory');
};

const downloadPdf = () => {
  // url 即直链。打开新标签让浏览器走原生下载/保存
  window.open(url.value, '_blank');
};
</script>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: var(--color-bg-base);
}

@supports not (height: 100dvh) {
  .pdf-viewer { height: 100vh; }
}

.pdf-toolbar {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-surface);
  flex-shrink: 0;
}

.pdf-name {
  flex: 1 1 auto;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-primary);
  word-break: break-all;
}

.pdf-frame {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  border: 0;
  background: #525252;
}

.pdf-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1 1 auto;
  color: var(--color-text-secondary);
  gap: 12px;
}

.pdf-empty-icon {
  width: 48px;
  height: 48px;
}
</style>
