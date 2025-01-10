<template>
  <el-tabs
      v-model="tagActiveName"
      type="card"
      style="padding: 10px">
    <el-tab-pane label="磁力下载管理" name="torrentDownload">
      <torrent-download v-if="tagActiveName === 'torrentDownload'"/>
    </el-tab-pane>
    <el-tab-pane label="订阅管理" name="rssManagement">
      <rss-management v-if="tagActiveName === 'rssManagement'"/>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import TorrentDownload from "@/components/fileServer/TorrentDownload.vue";
import RssManagement from "@/components/fileServer/RssManagement.vue";

const tagActiveName = ref("torrentDownload");
</script>

<style scoped>

</style>
<script setup lang="ts">
</script>