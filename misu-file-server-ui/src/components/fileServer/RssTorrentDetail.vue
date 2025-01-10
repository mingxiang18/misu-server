<template>
  <el-scrollbar>
    <div class="rss-table">
      <el-table v-loading="getRssTorrentRelationListLoading"
                :data="rssDetail.rssTorrentRelationList"
                max-height="65svh"
                style="width: 100%">
        <el-table-column prop="title" label="标题" min-width="80">
          <template #default="scope">
            <el-tooltip
                class="box-item"
                effect="dark"
                :content="scope.row.title"
                placement="bottom">
              <div class="table-text">
                <span style="word-break: break-all;">{{ scope.row.title }}</span>
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="publishDate" label="发布时间" min-width="100"/>
        <el-table-column prop="torrentUrl" label="下载状态" min-width="60">
          <template #default="scope">
            <el-icon v-if="scope.row.downloadState === 0" color="#909399" size="15"><CircleClose /></el-icon>
            <el-icon v-if="scope.row.downloadState === 1" color="#67c23a" size="15"><CircleCheck /></el-icon>
          </template>
        </el-table-column>
        <el-table-column prop="torrentUrl" label="磁力链接" min-width="100">
          <template #default="scope">
            <el-tooltip
                class="box-item"
                effect="dark"
                :content="scope.row.torrentUrl"
                placement="bottom">
              <div class="table-text">
                <span style="word-break: break-all;">{{ scope.row.torrentUrl }}</span>
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="author" label="发布人" min-width="80"/>
        <el-table-column prop="updateTime" label="更新时间" min-width="100"/>
        <el-table-column label="操作" min-width="75" fixed="right">
          <template #default="scope">
            <div class="table-option">
              <div v-if="scope.row.downloadState === 0"><el-button link type="primary" @click="downloadTorrent(scope.row)">下载</el-button></div>
              <div v-if="scope.row.downloadState === 1"><el-button  link type="primary" @click="downloadTorrent(scope.row)">重新下载</el-button></div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-scrollbar>
</template>

<script setup>
import {onMounted, onBeforeUnmount, ref, onUnmounted} from "vue";
import {
  getRssDetail as getRssDetailApi,
} from '@/api/fileServer/rss';
import {ElMessage, ElMessageBox} from "element-plus";
import {addUserTorrent as addUserTorrentApi} from "@/api/fileServer/torrent";
import {CircleCheck, CircleClose} from "@element-plus/icons-vue";

// 接收外部传入的接口函数
const props = defineProps({
  rssInfo: {
    type: Object,
    required: true,
  }
});

// rss订阅列表
const getRssTorrentRelationListLoading = ref(false);
const rssDetail = ref([]);

//获取rss订阅列表
const getRssDetail = () => {
  getRssTorrentRelationListLoading.value = true;
  const rssDetailRequest = {
    rssId: props.rssInfo.rssId
  }
  getRssDetailApi(rssDetailRequest).then((response) => {
    rssDetail.value = response.data;
  }).finally(() => {
    getRssTorrentRelationListLoading.value = false;
  })
}

const downloadTorrent = (torrentInfo) => {
  const addTorrentRequest = {
    userFilePath: rssDetail.value.downloadPath,
    torrentUrl: torrentInfo.torrentUrl
  };

  getRssTorrentRelationListLoading.value = true;
  addUserTorrentApi(addTorrentRequest).then((response) => {
    ElMessage.success("已添加到下载列表，具体进度请在磁力下载管理查看");
    getRssDetail();
  }).finally(() => {
    getRssTorrentRelationListLoading.value = false;
  })
}

onMounted(() => {
  getRssDetail();
})

onBeforeUnmount(() => {
})
</script>

<style scoped>

.table-text {
  width: 100%;
  display: -webkit-box; /* 使用多行文本框 */
  -webkit-box-orient: vertical; /* 垂直排列 */
  -webkit-line-clamp: 4; /* 限制最多显示 2 行 */
  overflow: hidden; /* 隐藏超出文本 */
  text-overflow: ellipsis; /* 超出部分显示省略号 */
}

.table-option {
  display: flex; /* 启用flex布局 */
  flex-wrap: wrap; /* 启用自动换行 */
  justify-content: flex-start; /* 子项水平对齐方式 */
  align-items: flex-start; /* 子项垂直对齐方式 */
}

.rss-table {

}
</style>