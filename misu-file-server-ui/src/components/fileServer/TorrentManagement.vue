<template>
  <el-tabs
      v-model="tagActiveName"
      type="card"
      class="torrent-tabs">
    <el-tab-pane label="磁力下载管理" name="torrentDownload">
      <torrent-download v-if="tagActiveName === 'torrentDownload'"/>
    </el-tab-pane>
    <el-tab-pane label="订阅管理" name="rssManagement">
      <rss-management v-if="tagActiveName === 'rssManagement'"/>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup>
import { ref } from 'vue'
import TorrentDownload from '@/components/fileServer/TorrentDownload.vue'
import RssManagement from '@/components/fileServer/RssManagement.vue'

const tagActiveName = ref('torrentDownload')
</script>

<style scoped>
.torrent-tabs {
  padding: var(--space-3) var(--space-4);
}

@media (max-width: 640px) {
  .torrent-tabs {
    padding: var(--space-2) var(--space-3);
  }
}

/* el-tabs 自身的 header 在 640px 下会有横向溢出风险，让 nav 横向可滚 */
.torrent-tabs :deep(.el-tabs__nav-wrap) {
  overflow-x: auto;
  scrollbar-width: none;
}

.torrent-tabs :deep(.el-tabs__nav-wrap::-webkit-scrollbar) {
  display: none;
}

.torrent-tabs :deep(.el-tabs__item) {
  font-size: var(--font-size-md);
}
</style>
