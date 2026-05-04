<template>
  <div class="torrent-management-header">
    <div class="torrent-management-header-choose">
      <el-select
          v-model="torrentQueryParam.completeState"
          placeholder="完成状态"
          style="width: 110px"
          placement="bottom"
          clearable
          @change="getTorrentList()"
      >
        <el-option
            v-for="item in completeStateOption"
            :key="item.value"
            :label="item.label"
            :value="item.value"
        />
      </el-select>
    </div>
    <div class="torrent-management-header-actions">
      <el-button :disabled="chooseTorrentList.length === 0" @click="batchStopTorrentDownload">批量暂停</el-button>
      <el-button :disabled="chooseTorrentList.length === 0" @click="batchStartTorrentDownload">批量开始</el-button>
      <el-button type="primary" @click="addTorrentVisible = true">添加磁力链接</el-button>
    </div>
  </div>

  <div style="width: 100%; padding-top: 10px">
    <el-input v-model="torrentQueryParam.keyword" @change="getTorrentList" placeholder="筛选关键字" clearable>
      <template #append>
        <el-button @click="getTorrentList" :icon="Search" />
      </template>
    </el-input>
  </div>

  <div class="torrent-table torrent-table-desktop">
    <el-table v-loading="getTorrentListLoading"
              :data="torrentList.list"
              @selection-change="handleTorrentSelectionChange"
              style="width: 100%">
      <el-table-column type="selection" width="40"/>
      <el-table-column prop="torrentName" label="文件名称" min-width="80">
        <template #default="scope">
          <el-tooltip
              class="box-item"
              effect="dark"
              :content="scope.row.torrentName"
              placement="bottom">
            <div class="table-text">
              <span style="word-break: break-all;">{{ scope.row.torrentName }}</span>
            </div>
          </el-tooltip>

        </template>
      </el-table-column>
      <el-table-column prop="totalSize" label="大小" min-width="60">
        <template #default="scope">
          {{ formatFileSize(scope.row.totalSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="userFileState" label="状态"  min-width="80">
        <template #default="scope">
          {{ formatTorrentState(scope.row) }}
        </template>
      </el-table-column>
      <el-table-column prop="serverFileProgress" label="下载进度" min-width="120">
        <template #default="scope">
          <el-progress style="padding-top: 5px"
                       :percentage="formatProgressPercentage(scope.row)"
                       :status="formatProgressStatus(scope.row)">
          </el-progress>
          <div>↓ {{ formatFileSize(scope.row.serverFileDownloadSpeed) }}/s</div>
        </template>
      </el-table-column>
      <el-table-column prop="userFilePath" label="用户文件路径" min-width="80"/>
      <el-table-column prop="torrentHash" label="磁力hash" min-width="55">
        <template #default="scope">
          <el-tooltip
              class="box-item"
              effect="dark"
              :content="scope.row.torrentHash"
              placement="bottom">
            <div class="table-text">
              <span style="word-break: break-all;">{{ scope.row.torrentHash }}</span>
            </div>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="60" fixed="right">
        <template #default="scope">
          <div class="table-option">
            <div><el-button link type="primary" @click="openTorrentDetail(scope.row)">详情</el-button></div>
            <div><el-button link type="warning"
                            @click="stopTorrentDownload(scope.row)"
                            v-if="scope.row.serverFileState === 20 || scope.row.serverFileState === 0">暂停</el-button></div>
            <div><el-button link type="success"
                            @click="startTorrentDownload(scope.row)"
                            v-if="scope.row.serverFileState === 10 || scope.row.serverFileState === 99">
              {{ scope.row.serverFileState === 99 ? '重新下载' : '开始' }}
            </el-button></div>
            <div><el-button link type="warning"
                            @click="retryTorrentSync(scope.row)"
                            v-if="scope.row.userFileState === 2 && scope.row.serverFileState === 30">重试同步</el-button></div>
            <div><el-button link type="danger" @click="deleteUserTorrent(scope.row)" v-if="scope.row.userFileState === 0">删除</el-button></div>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div class="torrent-table-tail">
      <el-pagination background
                     layout="prev, pager, next"
                     v-model:current-page="torrentQueryParam.pageNum"
                     v-model:page-size="torrentQueryParam.pageSize"
                     :total="torrentList.total"
                     :pager-count=5
                     @change="getTorrentList()"/>
    </div>
  </div>

  <div class="torrent-mobile-list" v-loading="getTorrentListLoading">
    <div class="torrent-mobile-card" v-for="item in torrentList.list" :key="item.userTorrentId">
      <div class="mobile-card-title">{{ item.torrentName || item.torrentHash }}</div>
      <div class="mobile-card-meta">
        <span>{{ formatFileSize(item.totalSize) }}</span>
        <span>{{ formatTorrentState(item) }}</span>
      </div>
      <el-progress
          :percentage="formatProgressPercentage(item)"
          :status="formatProgressStatus(item)" />
      <div class="mobile-card-path">{{ item.userFilePath }}</div>
      <div class="mobile-card-actions">
        <el-button link type="primary" @click="openTorrentDetail(item)">详情</el-button>
        <el-button link type="warning"
                   @click="stopTorrentDownload(item)"
                   v-if="item.serverFileState === 20 || item.serverFileState === 0">暂停</el-button>
        <el-button link type="success"
                   @click="startTorrentDownload(item)"
                   v-if="item.serverFileState === 10 || item.serverFileState === 99">
          {{ item.serverFileState === 99 ? '重新下载' : '开始' }}
        </el-button>
        <el-button link type="warning"
                   @click="retryTorrentSync(item)"
                   v-if="item.userFileState === 2 && item.serverFileState === 30">重试同步</el-button>
        <el-button link type="danger" @click="deleteUserTorrent(item)" v-if="item.userFileState === 0">删除</el-button>
      </div>
    </div>
    <div class="torrent-table-tail">
      <el-pagination background
                     layout="prev, pager, next"
                     v-model:current-page="torrentQueryParam.pageNum"
                     v-model:page-size="torrentQueryParam.pageSize"
                     :total="torrentList.total"
                     :pager-count=5
                     @change="getTorrentList()"/>
    </div>
  </div>

  <el-dialog
      v-model="torrentDetailVisible"
      title="磁力信息详情"
      style="width: 90%;">
    <div v-loading="torrentDetailLoading">
    <div class="torrent-detail-actions">
      <el-button type="primary" @click="refreshTorrentDetail">刷新状态</el-button>
      <el-button type="danger" @click="deleteServerTorrentTask(false)">删除服务器任务</el-button>
      <el-button type="danger" @click="deleteServerTorrentTask(true)">删除服务器任务和源文件</el-button>
    </div>
    <el-descriptions column="1" border>
      <el-descriptions-item label="用户文件目录">{{ torrentDetail.userFilePath }}</el-descriptions-item>
      <el-descriptions-item label="文件同步状态">{{ formatUserFileState(torrentDetail.userFileState) }}</el-descriptions-item>
      <el-descriptions-item v-if="torrentDetail.failedReason" label="同步失败原因">{{ torrentDetail.failedReason }}</el-descriptions-item>
      <el-descriptions-item label="服务器文件状态">{{ formatTorrentState(torrentDetail) }}</el-descriptions-item>
      <el-descriptions-item v-if="torrentDetail.remark" label="服务器备注">{{ torrentDetail.remark }}</el-descriptions-item>
      <el-descriptions-item label="文件hash">
        <span style="word-break: break-all;">{{ torrentDetail.torrentHash }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="文件名称">
        <span style="word-break: break-all;">{{ torrentDetail.torrentName }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="文件大小">{{ formatFileSize(torrentDetail.totalSize) }}</el-descriptions-item>
      <el-descriptions-item label="下载进度">{{ formatProgressPercentage(torrentDetail) }}%</el-descriptions-item>
      <el-descriptions-item label="下载速度">{{ formatFileSize(torrentDetail.serverFileDownloadSpeed) }}/s</el-descriptions-item>
      <el-descriptions-item label="磁力链接">
        <span style="word-break: break-all;">{{ torrentDetail.torrentUrl }}</span>
      </el-descriptions-item>
    </el-descriptions>
    </div>
  </el-dialog>

  <el-dialog
      v-model="addTorrentVisible"
      title="添加磁力链接"
      style="width: 90%;">
    <div v-loading="addTorrentLoading">
      <el-form :model="addTorrentInfo"
               ref="addTorrentFormRef"
               :rules="addTorrentRules"
               label-width="auto">
        <el-form-item label="保存目录" prop="userFilePath">
          <file-path-selector v-model="addTorrentInfo.userFilePath" :open-type='0'/>
        </el-form-item>
        <el-form-item label="磁力链接" prop="torrentUrl">
          <el-input :autosize="{ minRows: 4 }"
                    type="textarea"
                    v-model="addTorrentInfo.torrentUrl"/>
        </el-form-item>
      </el-form>
      <div style="display: flex; justify-content: center;">
        <el-button @click="addTorrentVisible = false">取消</el-button>
        <el-button type="primary" @click="addTorrent()">添加</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import {onMounted, onBeforeUnmount, ref, onUnmounted} from "vue";
import {
  getTorrentList as getTorrentListApi,
  getTorrentDetail as getTorrentDetailApi,
  addUserTorrent as addUserTorrentApi,
  updateUserTorrent as updateUserTorrentApi,
  batchUpdateUserTorrent as batchUpdateUserTorrentApi,
  removeUserTorrent as removeUserTorrentApi,
  retryUserTorrentSync as retryUserTorrentSyncApi,
  refreshUserTorrentState as refreshUserTorrentStateApi,
  deleteServerTorrent as deleteServerTorrentApi
} from '@/api/fileServer/torrent';
import { getUserInfo } from '@/api/user/user';
import { Search } from "@element-plus/icons-vue";
import {ElMessage, ElMessageBox} from "element-plus";
import FilePathSelector from "@/components/fileServer/FilePathSelector.vue";

const completeStateOption = ref([
  {label: "未完成", value: 0},
  {label: "已完成", value: 30},
  {label: "失败", value: 99}
])

// torrent列表获取定时器
let getTorrentListTimer = null;

// torrent列表
const getTorrentListLoading = ref(false);
const torrentQueryParam = ref({
  completeState: null,
  pageNum: 1,
  pageSize: 10
})
const torrentList = ref({
  "total": 0,
  "totalPages": 0,
  "pageSize": 0,
  "pageNumber": 0,
  "list": []
});
const chooseTorrentList = ref([]);

const addTorrentRules = {
  userFilePath: [
    { required: true, message: '保存目录不能为空', trigger: 'blur' }
  ],
  torrentUrl: [
    { required: true, message: '磁力链接不能为空', trigger: 'blur' },
    {
      pattern: /^magnet:\?xt=urn:btih:.*$/,
      message: '请输入有效的磁力链接',
      trigger: ['blur', 'change']
    }
  ],
}

//是否展示torrent详情
const torrentDetailVisible = ref(false);
const torrentDetailLoading = ref(false);
const torrentDetail = ref({});

const addTorrentVisible = ref(false);
const addTorrentLoading = ref(false);
const addTorrentFormRef = ref();
const addTorrentInfo = ref({
  userFilePath: '/',
  torrentUrl: ''
});

//添加torrent链接
const addTorrent = () => {
  addTorrentFormRef.value.validate((valid, fields) => {
    if (valid) {
      addTorrentLoading.value = true;
      addUserTorrentApi(addTorrentInfo.value).then((response) => {
        ElMessage.success("添加成功");
        addTorrentInfo.value = {
          userFilePath: '/',
          torrentUrl: ''
        };
        addTorrentVisible.value = false;
        getTorrentList();
      }).finally(() => {
        addTorrentLoading.value = false;
      })
    }
  })
}

//获取torrent列表
const getTorrentList = (needLoading = true) => {
  if (needLoading) {
    getTorrentListLoading.value = true;
  }
  getTorrentListApi(torrentQueryParam.value).then((response) => {
    torrentList.value = response.data;
  }).finally(() => {
    getTorrentListLoading.value = false;
  })
}

const handleTorrentSelectionChange = (selection) => {
  chooseTorrentList.value = selection;
}

//打开torrent详情
const openTorrentDetail = (torrentInfo) => {
  torrentDetail.value = torrentInfo;
  torrentDetailVisible.value = true;
  getTorrentDetail(torrentInfo.userTorrentId);
}

const getTorrentDetail = (userTorrentId) => {
  torrentDetailLoading.value = true;
  getTorrentDetailApi({userTorrentId}).then((response) => {
    torrentDetail.value = response.data;
  }).finally(() => {
    torrentDetailLoading.value = false;
  })
}

const refreshTorrentDetail = () => {
  if (!torrentDetail.value.userTorrentId) {
    return;
  }
  torrentDetailLoading.value = true;
  refreshUserTorrentStateApi({userTorrentId: torrentDetail.value.userTorrentId}).then((response) => {
    torrentDetail.value = response.data;
    ElMessage.success("刷新成功");
    getTorrentList(false);
  }).finally(() => {
    torrentDetailLoading.value = false;
  })
}

const stopTorrentDownload = (torrentInfo) => {
  const stopTorrentDownloadRequest = {
    userTorrentId: torrentInfo.userTorrentId,
    serverFileState: 10
  }
  updateUserTorrentApi(stopTorrentDownloadRequest).then((response) => {
    getTorrentList();
  })
}

const startTorrentDownload = (torrentInfo) => {
  const startTorrentDownloadRequest = {
    userTorrentId: torrentInfo.userTorrentId,
    serverFileState: 20
  }
  updateUserTorrentApi(startTorrentDownloadRequest).then((response) => {
    getTorrentList();
  })
}

const batchStopTorrentDownload = () => {
  batchUpdateTorrentState(10);
}

const batchStartTorrentDownload = () => {
  batchUpdateTorrentState(20);
}

const batchUpdateTorrentState = (serverFileState) => {
  const request = {
    userTorrentIdList: chooseTorrentList.value.map((item) => item.userTorrentId),
    serverFileState
  }
  batchUpdateUserTorrentApi(request).then(() => {
    ElMessage.success("批量操作成功");
    getTorrentList();
  })
}

const deleteUserTorrent = (torrentInfo) => {
  ElMessageBox.confirm(
      '是否确定删除当前磁力链接下载？',
      '是否确定',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
      }
  ).then(() => {
    const removeUserTorrentRequest = {
      userTorrentId: torrentInfo.userTorrentId
    }
    removeUserTorrentApi(removeUserTorrentRequest).then((response) => {
      ElMessage.success("删除成功");
      getTorrentList();
    })
  }).catch((error) => {
    return false;
  })
}

const retryTorrentSync = (torrentInfo) => {
  const retryUserTorrentSyncRequest = {
    userTorrentId: torrentInfo.userTorrentId
  }
  retryUserTorrentSyncApi(retryUserTorrentSyncRequest).then(() => {
    ElMessage.success("已提交重试同步");
    getTorrentList();
  })
}

const deleteServerTorrentTask = (deleteFiles) => {
  const message = deleteFiles
      ? '该操作会删除qBittorrent任务并尝试删除服务器源文件，但不会删除用户文件目录中已同步的文件。是否继续？'
      : '该操作只删除qBittorrent任务，不删除已下载源文件，也不会删除用户文件目录中的记录。是否继续？'
  ElMessageBox.confirm(
      message,
      '删除服务器任务',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
  ).then(() => {
    deleteServerTorrentApi({
      userTorrentId: torrentDetail.value.userTorrentId,
      deleteFiles
    }).then(() => {
      ElMessage.success("服务器任务已删除");
      torrentDetailVisible.value = false;
      getTorrentList();
    })
  }).catch(() => {
    return false;
  })
}

const formatFileSize = (bytes) => {
  if (!bytes) {
    return '0B';
  }
  // 单位数组，从小到大
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB'];

  // 计算当前单位对应的大小（1024的幂）
  let size = bytes;
  let unitIndex = 0;

  // 通过除以1024，找到合适的单位
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }

  // 返回格式化后的字符串，保留1位小数
  return `${size.toFixed(1)} ${units[unitIndex]}`;
}

const formatUserFileState = (userFileState) => {
  if (userFileState === 0) {
    return "等待同步";
  }else if (userFileState === 1) {
    return "文件已同步";
  }else if (userFileState === 2) {
    return "同步失败";
  }else {
    return "未知状态";
  }
}

const formatProgressPercentage = (torrentInfo) => {
  if (torrentInfo.serverFileState === 30 || torrentInfo.userFileState === 1) {
    return 100;
  }else if (torrentInfo.serverFileProgress === null || torrentInfo.serverFileProgress === undefined) {
    return 0;
  }else {
    return parseFloat((torrentInfo.serverFileProgress * 100).toFixed(1));
  }
}

const formatProgressStatus = (torrentInfo) => {
  if (torrentInfo.serverFileState === 30 && torrentInfo.userFileState !== 2) {
    return 'success';
  }else if (torrentInfo.serverFileState === 99 || torrentInfo.userFileState === 2) {
    return 'exception';
  }else {
    return '';
  }
}

const formatTorrentState = (torrentInfo) => {
  if (torrentInfo.userFileState === 0) {
    if (torrentInfo.serverFileState === 10) {
      return "已暂停";
    }else if (torrentInfo.serverFileState < 30) {
      //判断状态后返回
      if (torrentInfo.torrentState === 'error') {
        return "发生错误";
      }else if (torrentInfo.torrentState === 'missingFiles') {
        return "torrent文件丢失";
      }else if (torrentInfo.torrentState === 'uploading') {
        return "下载完成，等待文件同步";//正在上传
      }else if (torrentInfo.torrentState === 'pausedUP') {
        return "下载完成，等待文件同步";//已暂停并已完成下载
      }else if (torrentInfo.torrentState === 'queuedUP') {
        return "下载完成，等待文件同步";//已排队等待上传
      }else if (torrentInfo.torrentState === 'stalledUP') {
        return "下载完成，等待文件同步";//正在种子 Torrent 中，但未建立任何连接
      }else if (torrentInfo.torrentState === 'checkingUP') {
        return "下载完成，正在检查";
      }else if (torrentInfo.torrentState === 'stoppedUP') {
        return "下载完成，等待文件同步";
      }else if (torrentInfo.torrentState === 'forcedUP') {
        return "下载完成，等待文件同步";//Torrent 被迫上传并忽略队列限制
      }else if (torrentInfo.torrentState === 'allocating') {
        return "磁盘空间分配中";
      }else if (torrentInfo.torrentState === 'downloading') {
        return "下载中";
      }else if (torrentInfo.torrentState === 'metaDL') {
        return "元数据获取中";
      }else if (torrentInfo.torrentState === 'stoppedDL') {
        return "已暂停，未完成下载";
      }else if (torrentInfo.torrentState === 'pausedDL') {
        return "已暂停，未完成下载";
      }else if (torrentInfo.torrentState === 'queuedDL') {
        return "排队中";
      }else if (torrentInfo.torrentState === 'stalledDL') {
        return "下载中，但未建立任何连接";
      }else if (torrentInfo.torrentState === 'checkingDL') {
        return "下载中";//与 checkingUP 相同，但 torrent 尚未完成下载
      }else if (torrentInfo.torrentState === 'forcedDL') {
        return "下载中";//Torrent 被强制下载以忽略队列限制
      }else if (torrentInfo.torrentState === 'checkingResumeData') {
        return "恢复数据检查中";//在 qBt 启动时检查恢复数据
      }else if (torrentInfo.torrentState === 'moving') {
        return "文件移动中";
      }else {
        return "未知状态：" + torrentInfo.torrentState;
      }
    }else if (torrentInfo.serverFileState === 30){
      return "下载完成，等待文件同步";
    }else if (torrentInfo.serverFileState === 99){
      return "下载失败，失败原因：" + torrentInfo.remark;
    }
    return "未下载";
  } else if (torrentInfo.userFileState === 1) {
    return "已完成";
  } else if (torrentInfo.userFileState === 2) {
    return "文件同步失败，失败原因：" + torrentInfo.failedReason;
  }
}

onMounted(() => {
  getTorrentList();

  //定时器，每5秒执行一次，获取下载状态
  getTorrentListTimer = setInterval(() => {
    //如果torrentList的list列表中存在数据不是完成状态，则调用获取getTorrentList
    if (torrentList.value.list.some((item) => item.serverFileState < 30 && item.serverFileState !== 10)) {
      getTorrentList(false);
    }
  }, 5000);
})

onBeforeUnmount(() => {
  // 清理定时器
  clearInterval(getTorrentListTimer);
  getTorrentListTimer = null;
})

</script>

<style scoped>
.torrent-management-header {
  display: flex; /* 使用 flexbox 排列子元素 */
  justify-content: space-between; /* 左侧是路径，右侧是按钮区域 */
  align-items: center; /* 垂直居中 */
}

.torrent-management-header-choose {
  display: flex;
  align-items: center;
}

.torrent-management-header-actions {
  display: flex; /* 用 flex 布局让按钮排成一行 */
  gap: 10px; /* 按钮之间的间距 */
  padding-left: 10px;
}

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

.torrent-table {
  padding-top: 10px;
}

.torrent-mobile-list {
  display: none;
}

.torrent-table-tail {
  padding-top: 10px;
  display: flex; /* 启用flex布局 */
  flex-wrap: wrap; /* 启用自动换行 */
  justify-content: flex-end; /* 子项水平对齐方式 */
}

.torrent-detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 10px;
}

@media (max-width: 760px) {
  .torrent-management-header {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .torrent-management-header-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    padding-left: 0;
  }

  .torrent-management-header-actions .el-button {
    margin-left: 0;
    min-height: 36px;
  }

  .torrent-management-header-actions .el-button:last-child {
    grid-column: 1 / -1;
  }

  .torrent-table-desktop {
    display: none;
  }

  .torrent-mobile-list {
    display: grid;
    gap: 10px;
    padding-top: 10px;
  }

  .torrent-mobile-card {
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
    padding: 12px;
    background: var(--el-bg-color);
  }

  .mobile-card-title {
    font-weight: 600;
    line-height: 1.4;
    word-break: break-all;
  }

  .mobile-card-meta {
    color: var(--el-text-color-secondary);
    display: flex;
    gap: 10px;
    justify-content: space-between;
    padding: 8px 0;
  }

  .mobile-card-path {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    padding-top: 8px;
    word-break: break-all;
  }

  .mobile-card-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding-top: 8px;
  }

  .mobile-card-actions .el-button {
    margin-left: 0;
  }

  .torrent-detail-actions {
    display: grid;
    grid-template-columns: 1fr;
  }
}
</style>
