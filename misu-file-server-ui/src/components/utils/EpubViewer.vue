<template>
  <div class="epub-reader">
    <div class="epub-toolbar">
      <el-button @click="goBack" size="small" :icon="ArrowLeft">返回</el-button>
      <span class="epub-title" :title="bookTitle">{{ bookTitle || 'EPUB 阅读' }}</span>
      <span class="epub-progress" v-if="progress != null">{{ Math.round(progress * 100) }}%</span>
      <el-button @click="openToc" size="small" :icon="Menu" v-if="toc.length > 0">目录</el-button>
    </div>

    <div class="epub-stage" ref="stageRef">
      <!-- foliate-view 在 mount 后 JS 注入 -->
    </div>

    <div class="epub-controls">
      <el-button @click="prev" size="default" :icon="ArrowLeftBold">上一页</el-button>
      <el-button @click="next" size="default" :icon="ArrowRightBold">下一页</el-button>
    </div>

    <el-dialog v-model="tocVisible" title="目录" :width="isMobile ? 'calc(100vw - 32px)' : '480px'">
      <div class="epub-toc">
        <div v-for="(item, i) in flatToc" :key="i"
             class="epub-toc-item"
             :style="{ paddingLeft: (12 + item.depth * 16) + 'px' }"
             @click="goToHref(item.href)">
          {{ item.label }}
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Menu, ArrowLeftBold, ArrowRightBold } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useBreakpoint } from '@/composables/useBreakpoint';
import request from '@/api/request';
// foliate-js 是 ES module，import 触发 <foliate-view> custom element 注册
import '@/lib/foliate-js/view.js';

const route = useRoute();
const router = useRouter();
const { isMobile } = useBreakpoint();

const url = route.query.url;
const stageRef = ref();
const bookTitle = ref('');
const toc = ref([]);
const tocVisible = ref(false);
const progress = ref(null);
let view = null;

const flatToc = computed(() => {
  const out = [];
  const walk = (items, depth) => {
    for (const it of items) {
      out.push({ label: it.label, href: it.href, depth });
      if (it.subitems && it.subitems.length > 0) walk(it.subitems, depth + 1);
    }
  };
  walk(toc.value || [], 0);
  return out;
});

const openToc = () => { tocVisible.value = true; };
const goToHref = async (href) => {
  if (!view) return;
  tocVisible.value = false;
  try { await view.goTo(href); } catch (e) { ElMessage.error('跳转失败'); }
};
const prev = () => view?.prev?.();
const next = () => view?.next?.();

const goBack = () => {
  if (window.history.length > 1) router.back();
  else router.push('/fileServer/privateDirectory');
};

const loadEpub = async () => {
  try {
    // 走 axios 拿 ArrayBuffer 自动带 Authorization
    const baseApi = (import.meta.env.VITE_BASE_API || '').replace(/\/$/, '');
    let target = String(url || '');
    if (baseApi && target.startsWith(baseApi)) target = target.substring(baseApi.length);
    const buf = await request({ url: target, method: 'get', responseType: 'arraybuffer' });
    const blob = new Blob([buf], { type: 'application/epub+zip' });
    const file = new File([blob], 'book.epub', { type: 'application/epub+zip' });

    view = document.createElement('foliate-view');
    view.style.cssText = 'display: block; width: 100%; height: 100%;';
    stageRef.value.appendChild(view);

    view.addEventListener('relocate', (e) => {
      progress.value = e.detail?.fraction ?? null;
    });

    await view.open(file);
    bookTitle.value = pickMeta(view.book?.metadata?.title);
    toc.value = view.book?.toc || [];
    // 渲染到第一个 section（view.open 不自动 navigate）
    if (view.renderer) {
      view.renderer.setAttribute('flow', isMobile.value ? 'scrolled' : 'paginated');
    }
    // 跳到首节（resolveNavigation 要求数字 / href string / CFI；这里用数字 0）
    try {
      await view.goTo(0);
    } catch (e) {
      console.warn('initial goTo failed', e);
    }
  } catch (e) {
    console.error('epub load failed', e);
    ElMessage.error('EPUB 加载失败：' + (e?.message || e));
  }
};

// metadata.title 可能是字符串或多语言对象 {en: ..., zh: ...}
const pickMeta = (m) => {
  if (!m) return '';
  if (typeof m === 'string') return m;
  return m.zh || m['zh-cn'] || m['zh-CN'] || m.en || Object.values(m)[0] || '';
};

onMounted(loadEpub);
onBeforeUnmount(() => {
  try { view?.close?.(); } catch (e) {}
  view = null;
});
</script>

<style scoped>
.epub-reader {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: var(--color-bg-base);
}

@supports not (height: 100dvh) {
  .epub-reader { height: 100vh; }
}

.epub-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-surface);
  flex-shrink: 0;
}

.epub-title {
  flex: 1 1 auto;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.epub-progress {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-right: 8px;
}

.epub-stage {
  flex: 1 1 0;
  min-height: 0;
  position: relative;
  background: var(--color-bg-surface);
  overflow: hidden;
}

.epub-controls {
  display: flex;
  gap: var(--space-2);
  padding: 10px 16px;
  border-top: 1px solid var(--color-border-subtle);
  background: var(--color-bg-surface);
  flex-shrink: 0;
}

.epub-controls .el-button {
  flex: 1 1 auto;
  min-height: 40px;
}

.epub-toc {
  max-height: min(60vh, 480px);
  overflow-y: auto;
}

.epub-toc-item {
  padding: 8px 12px;
  cursor: pointer;
  color: var(--color-text-primary);
  border-radius: var(--radius-sm);
  font-size: 14px;
  transition: background var(--duration-fast) var(--ease-standard);
}

.epub-toc-item:hover {
  background: var(--color-bg-hover);
}
</style>
