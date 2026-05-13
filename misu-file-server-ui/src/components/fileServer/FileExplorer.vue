<template>
  <div style="height: 100%"
       @dragover.prevent="handlePageDragOver">
    <div class="file-header">
      <div class="file-path-choose" v-show="!searchActive">
        <div style="display: inline;">
          路径：/<span class="choose-text" @click="changeDirectory('')">根目录</span>
          <span v-for="(subFilePath, index) in filePath.split('/').filter(Boolean)" :key="index">
            /<span class="choose-text" @click="changeDirectory(subFilePath, index)">{{ subFilePath }}</span>
          </span>
        </div>
      </div>
      <div v-if="searchActive" class="file-search-info">
        <Search class="file-search-info-icon"/>
        <span>"{{ searchKeyword }}" 的搜索结果（{{ searchTotal }}）</span>
        <el-button size="small" link @click="exitSearch">返回浏览</el-button>
      </div>

      <div v-if="isDesktop" class="file-actions">
        <el-input
            v-model="searchKeyword"
            class="file-search-input"
            placeholder="搜索文件名"
            clearable
            @keyup.enter="runSearch"
            @clear="exitSearch">
          <template #prefix><Search/></template>
        </el-input>
        <el-button v-if="!batchMode" @click="enterBatch">选择</el-button>
        <el-button v-else @click="exitBatch">取消选择</el-button>
        <el-button type="primary" @click="createDirectory" :disabled="batchMode || searchActive">新建目录</el-button>
        <el-upload
            ref="fileUploadComponent"
            :on-change="fileUploadChange"
            :auto-upload=false
            :show-file-list=false
            multiple
            :limit="100">
          <el-button type="primary" :disabled="batchMode">上传</el-button>
        </el-upload>
      </div>
    </div>

    <!-- 配额条（仅私人目录） -->
    <div v-if="showQuota && storageUsage" class="quota-bar">
      <div class="quota-bar-text">
        <span class="quota-bar-used">{{ usageBytesText }}</span>
        <span class="quota-bar-total">{{ quotaText }}</span>
        <span class="quota-bar-count">· {{ fileCountText }}</span>
      </div>
      <el-progress
          v-if="storageUsage.quotaBytes"
          class="quota-bar-progress"
          :percentage="quotaPercent"
          :stroke-width="6"
          :show-text="false"
          :status="quotaPercent >= 90 ? 'exception' : (quotaPercent >= 70 ? 'warning' : '')"/>
    </div>

    <!-- 批量操作栏 -->
    <div v-if="batchMode" class="batch-bar">
      <span class="batch-bar-count">已选 {{ selectedFileKeys.size }} 项</span>
      <el-button size="small" @click="selectAllVisible">全选当前页</el-button>
      <el-button size="small" @click="clearSelection">清空选择</el-button>
      <el-button size="small" type="primary" :disabled="selectedFileKeys.size === 0" @click="openBatchMoveDialog">批量移动到…</el-button>
      <el-button size="small" type="danger" :disabled="selectedFileKeys.size === 0" @click="batchDelete">批量删除</el-button>
    </div>

    <div class="file-container" v-loading="fileListLoading || searchLoading">
      <div
          v-if="!fileListLoading && !searchLoading && displayFileList.length === 0"
          class="file-empty">
        <el-icon class="file-empty-icon"><Folder /></el-icon>
        <p class="file-empty-title">{{ searchActive ? '未找到匹配的文件' : '这个目录还是空的' }}</p>
        <p class="file-empty-hint">{{ searchActive ? '换个关键词试试' : '把文件拖到这里，或点击右下角按钮上传' }}</p>
        <el-button
            v-if="!searchActive && isMobile"
            type="primary"
            size="large"
            class="file-empty-cta"
            @click="triggerMobileUpload">
          上传文件
        </el-button>
        <el-button
            v-if="!searchActive && !isMobile"
            type="primary"
            size="default"
            class="file-empty-cta"
            @click="triggerDesktopUpload">
          上传文件
        </el-button>
      </div>
      <el-container class="file-card"
                    :class="{ 'file-card-selected': isSelected(file), 'file-card-batch': batchMode }"
                    v-for="file in displayFileList" :key="file.fileId || (file.filePath + file.fileName)"
                    @contextmenu.prevent="showContextMenu($event, file)"
                    @touchstart="fileTouchStart($event, file)"
                    @touchend="fileTouchEnd($event, file)"
                    @touchcancel="fileTouchCancel">
        <!-- 批量模式选中标记 -->
        <div v-if="batchMode" class="file-card-checkbox" :class="{ checked: isSelected(file) }">
          <Check v-if="isSelected(file)" class="file-card-check-icon"/>
        </div>
        <el-main class="file-card-main" @click="openFile(file)">
          <Folder v-if="file.fileType === 'directory'" class="file-show file-show-folder"/>
          <el-image v-if="file.fileType === 'image' && !!file.previewLink" class="file-preview"
                    :src="downloadBaseUrl + file.previewLink"
                    fit="cover"
                    loading="lazy" />
          <Picture v-if="file.fileType === 'image' && !file.previewLink" class="file-show"/>
          <Document v-if="file.fileType === 'other'" class="file-show"/>
          <Document v-if="file.fileType === 'document'" class="file-show file-show-document"/>
          <Document v-if="file.fileType === 'text'" class="file-show file-show-text"/>
          <div v-if="file.fileType === 'video'" class="video-file-show">
            <el-image v-if="!!file.videoPreviewLink" class="video-preview"
                      :src="downloadBaseUrl + file.videoPreviewLink"
                      fit="cover"
                      loading="lazy" />
            <Film v-else class="file-show"/>
            <div v-if="file.transcodeState && file.transcodeState !== 'SUCCESS' && file.transcodeState !== 'PASSTHROUGH'" class="video-status-mask">
              <span class="video-status-text">{{ getVideoStatusText(file) }}</span>
              <el-progress v-if="file.transcodeState === 'PROCESSING'"
                           class="video-progress"
                           :percentage="file.transcodeProgress || 0"
                           :show-text="false"
                           :stroke-width="4"/>
            </div>
          </div>
        </el-main>
        <el-footer class="file-card-footer">
          <el-tooltip
              class="box-item"
              effect="dark"
              :content="file.fileName"
              placement="bottom">
            <div class="wrap-and-ellipsis">
              {{ file.fileName }}
            </div>
          </el-tooltip>
        </el-footer>
      </el-container>
    </div>

    <el-image-viewer v-if="imageViewVisible"
                     :zoom-rate="1.2"
                     :max-scale="7"
                     :min-scale="0.2"
                     :url-list="imageSrcList"
                     :initial-index="imageIndex"
                     :hide-on-click-modal="true"
                     @switch="switchImageViewer"
                     @close="closeImageViewer"/>

    <!-- 拖拽区域 (移动端无拖拽场景，仅桌面渲染) -->
    <div class="fullscreen-overlay"
         v-if="isDesktop && (pageDragging || dropAreaDragging)"
         @dragover.prevent="handleDropAreaDragOver"
         @dragleave="handleDropAreaDragLeave"
         @drop="handleDrop">
      <div
          class="drop-area"
          :class="{'dragging': pageDragging || dropAreaDragging}">
        拖动文件到该位置上传
      </div>
    </div>

    <!-- 移动端浮动按钮：新建目录 / 上传文件 -->
    <template v-if="isMobile">
      <!-- 隐藏的上传组件，由 FAB 程序触发 -->
      <el-upload
          ref="fileUploadComponentMobile"
          class="file-upload-hidden"
          :on-change="fileUploadChange"
          :auto-upload="false"
          :show-file-list="false"
          multiple
          :limit="100">
        <button ref="mobileUploadTrigger"
                class="file-upload-hidden-btn"
                type="button"
                aria-hidden="true"
                tabindex="-1"></button>
      </el-upload>

      <el-dropdown class="file-fab-wrap" trigger="click" placement="top-end">
        <button class="file-fab" type="button" aria-label="新建或上传">
          <el-icon><Plus/></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="createDirectory">
              <el-icon><FolderAdd/></el-icon>
              <span>新建目录</span>
            </el-dropdown-item>
            <el-dropdown-item @click="triggerMobileUpload">
              <el-icon><Upload/></el-icon>
              <span>上传文件</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>

    <el-dialog
        v-model="fileUploading"
        title="文件上传列表"
        :width="dialogWidth"
        :before-close="fileUploadClose">
      <file-upload
          v-if="fileUploading"
          style="height: 350px;"
          :upload-file-list="uploadFileList"
          @upload-success="queryFileList"
          @upload-all-complete="uploadAllComplete = true"/>
    </el-dialog>

    <el-dialog
        v-model="moveFileDialogVisible"
        title="移动文件"
        :width="dialogWidth"
        @close="closeMoveFileDialog()">
      <div v-loading="moveFileLoading">
        <el-form :model="moveFileInfo"
                 ref="moveFileFormRef"
                 :rules="moveFileRules"
                 label-width="auto">
          <el-form-item label="文件名称" prop="fileName">
            <el-input v-model="moveFileInfo.fileName"/>
          </el-form-item>
          <el-form-item label="选择目录" prop="newFilePath">
            <file-path-selector v-model="moveFileInfo.newFilePath" :open-type="props.openType"/>
          </el-form-item>
        </el-form>
        <div style="display: flex; justify-content: center;">
          <el-button @click="closeMoveFileDialog()">取消</el-button>
          <el-button type="primary" @click="moveFile()">添加</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog
        v-model="shareToPublicDialogVisible"
        title="共享到公共目录"
        :width="dialogWidth"
        @close="closeShareToPublicDialog()">
      <div v-loading="shareToPublicLoading">
        <el-form :model="shareToPublicInfo"
                 ref="shareToPublicFormRef"
                 :rules="shareToPublicRules"
                 label-width="auto">
          <el-form-item label="共享文件">
            <el-input v-model="shareToPublicInfo.fileName" disabled/>
          </el-form-item>
          <el-form-item label="公共目录" prop="targetDirectoryPath">
            <file-path-selector v-model="shareToPublicInfo.targetDirectoryPath" :open-type="1"/>
          </el-form-item>
        </el-form>
        <div style="display: flex; justify-content: center;">
          <el-button @click="closeShareToPublicDialog()">取消</el-button>
          <el-button type="primary" @click="sharePrivateFileToPublic()">共享</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- 版本历史弹窗 -->
    <el-dialog
        v-model="versionsDialogVisible"
        :width="isMobile ? 'calc(100vw - 32px)' : '640px'"
        title="版本历史">
      <div v-loading="versionsLoading">
        <p style="color: var(--color-text-secondary); margin: 0 0 8px;">
          【{{ versionsTargetFileName }}】最近 {{ versionsList.length }} 个版本（每次覆盖前自动留底，超过 5 个删最旧；&gt;50 MB 不留版本）。
        </p>
        <el-table v-if="versionsList.length > 0" :data="versionsList" stripe size="small">
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatVersionTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ formatVersionBytes(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="原因" width="110">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ versionReasonLabel(row.snapshotReason) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="restoreVersionAction(row)">还原</el-button>
              <el-button size="small" type="danger" link @click="purgeVersionAction(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!versionsLoading" style="text-align: center; color: var(--color-text-tertiary); padding: 32px 0;">
          暂无历史版本
        </div>
      </div>
    </el-dialog>

    <!-- 外链分享弹窗 -->
    <el-dialog
        v-model="externalShareDialogVisible"
        title="外链分享"
        :width="dialogWidth"
        @close="closeExternalShareDialog">
      <div v-loading="externalShareLoading">
        <template v-if="!externalShareResult">
          <p style="color: var(--color-text-secondary); margin: 0 0 12px;">
            为【{{ externalShareForm.fileName }}】生成一个公开链接。任何拿到链接的人在过期前可以下载。
          </p>
          <el-form label-position="top" :model="externalShareForm">
            <el-form-item label="过期时间">
              <el-radio-group v-model="externalShareForm.expireMinutes">
                <el-radio-button v-for="o in externalShareExpireOptions" :key="o.value" :value="o.value">
                  {{ o.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item>
              <el-checkbox v-model="externalShareForm.enablePassword">需要访问密码</el-checkbox>
              <el-input
                  v-if="externalShareForm.enablePassword"
                  v-model="externalShareForm.password"
                  type="password"
                  show-password
                  placeholder="4-32 位"
                  style="margin-top: 6px;"/>
            </el-form-item>

            <el-form-item>
              <el-checkbox v-model="externalShareForm.enableMaxDownloads">限制下载次数</el-checkbox>
              <el-input-number
                  v-if="externalShareForm.enableMaxDownloads"
                  v-model="externalShareForm.maxDownloads"
                  :min="1"
                  :max="100000"
                  placeholder="次数"
                  style="margin-top: 6px; width: 160px;"/>
            </el-form-item>
          </el-form>

          <div style="display: flex; justify-content: center; gap: 8px;">
            <el-button @click="closeExternalShareDialog">取消</el-button>
            <el-button type="primary" @click="submitExternalShare">生成链接</el-button>
          </div>
        </template>

        <template v-else>
          <div style="display: flex; flex-direction: column; align-items: center; gap: 12px;">
            <el-icon style="color: var(--color-success); font-size: 32px;"><CircleCheckFilled/></el-icon>
            <div style="font-weight: 500;">分享创建成功</div>
            <el-input :model-value="externalShareResult.link" readonly>
              <template #append>
                <el-button @click="copyShareLink">复制</el-button>
              </template>
            </el-input>
            <div style="font-size: 12px; color: var(--color-text-tertiary); text-align: center;">
              过期：{{ externalShareResult.share.expireTime?.replace('T',' ').slice(0,19) }}
              <span v-if="externalShareResult.share.hasPassword"> · 需要密码</span>
              <span v-if="externalShareResult.share.maxDownloads"> · 限 {{ externalShareResult.share.maxDownloads }} 次</span>
            </div>
            <el-button type="primary" @click="closeExternalShareDialog">完成</el-button>
          </div>
        </template>
      </div>
    </el-dialog>

    <!-- 批量移动弹窗 -->
    <el-dialog
        v-model="batchMoveDialogVisible"
        title="批量移动到目标目录"
        :width="dialogWidth">
      <div v-loading="batchMoveLoading">
        <p style="color: var(--color-text-secondary); margin: 0 0 12px;">
          将选中的 {{ selectedFileKeys.size }} 项移动到指定目录（保留原文件名）。
        </p>
        <el-form label-width="auto">
          <el-form-item label="目标目录">
            <file-path-selector v-model="batchMoveTargetPath" :open-type="props.openType"/>
          </el-form-item>
        </el-form>
        <div style="display: flex; justify-content: center;">
          <el-button @click="batchMoveDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="batchMove">确认移动</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- 自定义右键菜单 -->
    <div
        v-show="menuVisible"
        ref="rightMenu"
        :style="menuStyles"
        class="context-menu"
    >
      <ul ref="rightMenuUl">
        <li @click="onMenuItemClick('download')">下载</li>
        <li v-if="menuChooseFile && menuChooseFile.fileType === 'directory'" @click="onMenuItemClick('downloadZip')">下载为 ZIP</li>
        <li @click="onMenuItemClick('move')">移动/重命名</li>
        <li v-if="menuChooseFile && menuChooseFile.fileType !== 'directory'" @click="onMenuItemClick('externalShare')">外链分享…</li>
        <li v-if="menuChooseFile && menuChooseFile.fileType !== 'directory'" @click="onMenuItemClick('versions')">版本历史</li>
        <li v-if="canSharePrivateFileToPublic" @click="onMenuItemClick('shareToPublic')">共享到公共目录</li>
        <li @click="onMenuItemClick('delete')">删除</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import {Document, Film, Folder, Picture, Plus, FolderAdd, Upload, Search, Close, Check, Download, CircleCheckFilled} from "@element-plus/icons-vue";
import {computed, defineProps, onBeforeUnmount, onMounted, ref, watch} from "vue";
import { useBreakpoint } from '@/composables/useBreakpoint';
import VideoViewer from '@/components/fileServer/VideoViewer.vue'
import FileUpload from '@/components/fileServer/FileUpload.vue'
import {ElMessage, ElMessageBox} from "element-plus";
import logger from '@/utils/logger';
import {
  getFileList,
  moveFile as moveFileApi,
  deleteFile as deleteFileApi,
  createDirectory as createDirectoryApi,
  sharePrivateFileToPublic as sharePrivateFileToPublicApi
} from '@/api/fileServer/fileServer';
import {
  searchFiles as searchFilesApi,
  getStorageUsage as getStorageUsageApi,
  batchDelete as batchDeleteApi,
  batchMove as batchMoveApi,
  buildDirectoryDownloadUrl
} from '@/api/fileServer/fileServerMvp';
import { createShare as createShareApi } from '@/api/fileServer/fileShareApi';
import { listVersions as listVersionsApi, restoreVersion as restoreVersionApi, purgeVersion as purgeVersionApi } from '@/api/fileServer/fileVersionApi';
import { useRouter, useRoute } from 'vue-router';
import { playMyVideo } from '@/api/fileServer/videoRoom';
import FilePathSelector from "@/components/fileServer/FilePathSelector.vue";
import {addUserTorrent as addUserTorrentApi} from "@/api/fileServer/torrent";
import {getUserInfo} from "@/api/user/user";

// 接收外部传入的接口函数
const props = defineProps({
  openType: {
    type: Number,
    required: true,
  }
});

// 响应式断点
const { isMobile, isDesktop } = useBreakpoint();
// 弹层宽度（移动端 calc(100vw - 32px)，桌面端 480 固定）
const dialogWidth = computed(() => isMobile.value ? 'calc(100vw - 32px)' : '480px');

//当前文件路径
const filePath = ref("/");
//当前目录的文件列表
const fileList = ref([]);
//文件列表的加载函数
const fileListLoading = ref(false);

// ===== M8 搜索 =====
const searchKeyword = ref('');
const searchActive = ref(false);
const searchResults = ref([]);
const searchTotal = ref(0);
const searchLoading = ref(false);

// ===== M10 批量 =====
const batchMode = ref(false);
const selectedFileKeys = ref(new Set());
const isSelected = (file) => selectedFileKeys.value.has(buildApiFilePath(file.filePath, file.fileName));

// ===== M11 配额 =====
const storageUsage = ref(null);
const showQuota = computed(() => props.openType === 0);
const formatBytesPretty = (b) => {
  if (b == null) return '0 B';
  if (b >= 1024 * 1024 * 1024) return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`;
  if (b >= 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${b} B`;
};
const usageBytesText = computed(() => formatBytesPretty(storageUsage.value?.usedBytes || 0));
const quotaText = computed(() => storageUsage.value?.quotaBytes ? `/ ${formatBytesPretty(storageUsage.value.quotaBytes)}` : '/ 不限');
const quotaPercent = computed(() => {
  const used = storageUsage.value?.usedBytes || 0;
  const quota = storageUsage.value?.quotaBytes;
  if (!quota || quota <= 0) return 0;
  return Math.min(100, Math.round(used * 100 / quota));
});
const fileCountText = computed(() => `${storageUsage.value?.fileCount ?? 0} 个文件`);
// 下载文件基本url
const downloadBaseUrl = normalizeResourceBaseUrl(import.meta.env.VITE_RESOURCE_API)

// 图片组件相关参数
const imageIndex = ref(0);
// 图片组件用的链接列表，需要到指定图片才请求接口，会初始化一个空字符串数组，加载到指定图片时，才对指定位置链接赋值
const imageSrcList = ref([]);
// 与上图列表对应，是图片实际地址，加载到指定图片时，将对应位置链接赋值到上面列表对应位置
const imageFullSrcList = ref([]);
const imageViewVisible = ref(false);

// 是否存在文件拖拽
const pageDragging = ref(false);
const dropAreaDragging = ref(false);

// 上传文件列表
const fileUploadComponent = ref();
// 移动端上传组件 ref（FAB 用）
const fileUploadComponentMobile = ref();
const mobileUploadTrigger = ref();
const triggerMobileUpload = () => {
  if (mobileUploadTrigger.value) {
    mobileUploadTrigger.value.click();
  }
};
const triggerDesktopUpload = () => {
  // el-upload 内部按钮的真实 input 在 .el-upload__input 上；用直接 click 即可触发文件选择
  const root = fileUploadComponent.value?.$el || fileUploadComponent.value;
  const input = root && root.querySelector ? root.querySelector('input[type="file"]') : null;
  if (input) input.click();
};
const clearAllUploadComponents = () => {
  if (fileUploadComponent.value && fileUploadComponent.value.clearFiles) {
    fileUploadComponent.value.clearFiles();
  }
  if (fileUploadComponentMobile.value && fileUploadComponentMobile.value.clearFiles) {
    fileUploadComponentMobile.value.clearFiles();
  }
};
// 文件是否正在上传标识
const fileUploading = ref(false);
// 上传文件列表
const uploadFileList = ref([]);
// 文件列表是否全部上传完成
const uploadAllComplete = ref(true);

// 右键菜单显示
const menuVisible = ref(false);
const rightMenu = ref();
const rightMenuUl = ref();
const menuPosition = ref({ top: 0, left: 0 });
const menuStyles = ref({ top: 0, left: 0 });
// 菜单选择的文件
const menuChooseFile = ref(null);
// 移动端菜单长按时间阈值（单位：毫秒）
const LONG_PRESS_TIME = 350;
let menuPressTimer = null;  // 用于存储定时器ID
let isFileLongPress = false;  // 是否长按文件

//移动文件相关
const moveFileFormRef = ref();
const moveFileDialogVisible = ref(false);
const moveFileLoading = ref(false);
const moveFileInfo = ref({
  fileName: null,
  originFileName: null,
  originFilePath: null,
  newFilePath: '/'
});
const moveFileRules = ref({
  fileName: [
    { required: true, message: '文件名称不能为空', trigger: 'blur' },
    {
      pattern: /^(?!.*[\/:*?"<>|])([^\0\\\/:*?"<>|]+(\.[^\\\/:*?"<>|]+)?)$/,
      message: '文件名称存在不合法字符',
      trigger: ['blur', 'change']
    }
  ],
  newFilePath: [
    { required: true, message: '文件路径不能为空', trigger: 'blur' }
  ],
});

//共享到公共目录相关
const shareToPublicFormRef = ref();
const shareToPublicDialogVisible = ref(false);
const shareToPublicLoading = ref(false);
const shareToPublicInfo = ref({
  fileName: null,
  sourceFilePath: null,
  targetDirectoryPath: '/'
});
const shareToPublicRules = ref({
  targetDirectoryPath: [
    { required: true, message: '公共目录不能为空', trigger: 'change' }
  ],
});
const currentUserInfo = ref(getUserInfo());
const canSharePrivateFileToPublic = computed(() => {
  const authorities = currentUserInfo.value.authorities || [];
  return props.openType === 0 && (authorities.includes('ADMIN') || authorities.includes('FILE_ADMIN'));
});

function normalizeResourceBaseUrl(resourceApi) {
  if (!resourceApi) {
    return '';
  }
  try {
    const url = new URL(resourceApi, window.location.origin);
    const currentHost = window.location.hostname;
    const isLocalPage = currentHost === 'localhost' || currentHost === '127.0.0.1';
    const isLocalResource = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
    if (isLocalPage && isLocalResource && url.hostname !== currentHost) {
      url.hostname = currentHost;
      return url.toString();
    }
  } catch (e) {
    return resourceApi;
  }
  return resourceApi;
}

// 使用 Vue Router
const router = useRouter();
const route = useRoute();

// 展示右键菜单
const showContextMenu = (event, file) => {
  event.preventDefault(); // 阻止默认右键菜单

  menuChooseFile.value = file;

  const menuWidth = 120; // 菜单宽度
  const menuHeight = rightMenuUl.value.children.length * 45; // 菜单高度是li的数量 * 高度px
  const offset = 10; // 菜单距离边缘的间隔

  let top;
  let left;
  if (!!event.touches) {
    // 如果是触控，获取第一个触摸点
    const touch = event.touches[0];
    top = touch.clientY + window.scrollY;
    left = touch.clientX + window.scrollX;
  } else {
    top = event.clientY + window.scrollY;
    left = event.clientX + window.scrollX;
  }

  // 调整菜单位置，确保不超出屏幕右边或底部
  if (left + menuWidth > window.innerWidth) {
    left = window.innerWidth - menuWidth - offset;
  }
  if (top + menuHeight > window.innerHeight) {
    top = window.innerHeight - menuHeight - offset;
  }

  // 设置菜单显示位置
  menuPosition.value = { top, left };

  // 显示菜单的style属性
  menuStyles.value = {
    top: `${top}px`,
    left: `${left}px`,
    position: 'fixed', // 固定定位菜单位置
    zIndex: 1000, // 保证菜单显示在最上面
  };

  //显示右键菜单
  menuVisible.value = true;
};

// 触摸开始事件
const fileTouchStart = (event, file) => {
  isFileLongPress = false;
  menuPressTimer = setTimeout(() => {
    // 达到长按阈值时触发长按事件
    isFileLongPress = true;
    menuChooseFile.value = file;
    showContextMenu(event, file);
  }, LONG_PRESS_TIME);
};

// 触摸结束事件
const fileTouchEnd = (event) => {
  // 如果触摸事件结束，清除定时器
  clearTimeout(menuPressTimer);
  // 如果是长按，则阻止默认的上下文菜单
  if (isFileLongPress === true) {
    event.preventDefault();
  }else {
    menuChooseFile.value = null;
  }

  isFileLongPress = false;
};

// 触摸取消事件（如用户滑动时）
const fileTouchCancel = () => {
  // 清除定时器
  clearTimeout(menuPressTimer);

  isFileLongPress = false;
  menuChooseFile.value = null;
};

// 监听组件挂载时添加事件监听器
onMounted(() => {
  document.addEventListener('click', hideContextMenu);
});

// 组件卸载时移除事件监听器
onBeforeUnmount(() => {
  document.removeEventListener('click', hideContextMenu);
  clearTranscodePollTimer();
});

// 隐藏右键菜单
const hideContextMenu = (event) => {
  // 如果点击的是菜单内部，就不隐藏
  if (rightMenu.value && rightMenu.value.contains(event.target)) {
    return;
  }

  menuVisible.value = false;
  menuChooseFile.value = null;
};

const onMenuItemClick = (option) => {
  if (option === 'download') {
    downloadFile(menuChooseFile.value);
  }else if (option === 'downloadZip') {
    downloadDirectoryZip(menuChooseFile.value);
  }else if (option === 'move') {
    moveFileInfo.value = {
      fileName: menuChooseFile.value.fileName,
      originFileName: menuChooseFile.value.fileName,
      originFilePath: menuChooseFile.value.filePath,
      newFilePath: normalizeDirectoryPath(menuChooseFile.value.filePath)
    };
    moveFileDialogVisible.value = true;
  }else if (option === 'delete') {
    deleteFile(menuChooseFile.value);
  }else if (option === 'externalShare') {
    openExternalShareDialog(menuChooseFile.value);
  }else if (option === 'versions') {
    openVersionsDialog(menuChooseFile.value);
  }else if (option === 'shareToPublic') {
    openShareToPublicDialog(menuChooseFile.value);
  }
  // 点击后隐藏菜单
  menuVisible.value = false;
  menuChooseFile.value = null;
};

// M11：目录 ZIP 流式下载
const downloadDirectoryZip = (file) => {
  const zipPath = buildApiFilePath(file.filePath, file.fileName);
  // token 走 cookie，浏览器同 origin 跨域时不会自动带，直接拿出来当 query
  // 简单做法：用 a 标签 + 隐藏 form？这里 backend 已支持 cookie 同 origin 才会自动带
  // 由于 axios 在拦截器里把 token 放在 Authorization header，下载链接得手动用 fetch 拿 blob
  fetch(`${import.meta.env.VITE_BASE_API.replace(/\/$/, '')}/fileServer/file/downloadDirectory?openType=${props.openType}&filePath=${encodeURIComponent(zipPath)}`, {
    headers: {
      'Authorization': `Bearer ${getRawToken()}`
    }
  }).then(async (resp) => {
    if (!resp.ok) {
      const text = await resp.text();
      ElMessage.error(`ZIP 下载失败：${resp.status} ${text.slice(0,100)}`);
      return;
    }
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${file.fileName}.zip`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }).catch(err => {
    ElMessage.error(`ZIP 下载失败：${err.message || err}`);
  });
};

const getRawToken = () => {
  const cookieMatch = document.cookie.split(';').map(c => c.trim()).find(c => c.startsWith('User-Token='));
  if (cookieMatch) return cookieMatch.substring('User-Token='.length);
  return '';
};

// ===== M8：搜索 =====
const runSearch = () => {
  const kw = String(searchKeyword.value || '').trim();
  if (!kw) {
    exitSearch();
    return;
  }
  searchActive.value = true;
  searchLoading.value = true;
  searchFilesApi({ openType: props.openType, keyword: kw, pageNumber: 1, pageSize: 100 })
      .then(resp => {
        searchResults.value = resp.data?.items || [];
        searchTotal.value = resp.data?.total || 0;
      })
      .catch(() => {})
      .finally(() => { searchLoading.value = false; });
};
const exitSearch = () => {
  searchActive.value = false;
  searchKeyword.value = '';
  searchResults.value = [];
  searchTotal.value = 0;
};
const displayFileList = computed(() => searchActive.value ? searchResults.value : fileList.value);

// ===== M10：批量 =====
const batchMoveDialogVisible = ref(false);
const batchMoveLoading = ref(false);
const batchMoveTargetPath = ref('/');

const enterBatch = () => {
  batchMode.value = true;
  selectedFileKeys.value = new Set();
};
const exitBatch = () => {
  batchMode.value = false;
  selectedFileKeys.value = new Set();
};
const toggleSelection = (file) => {
  const key = buildApiFilePath(file.filePath, file.fileName);
  const next = new Set(selectedFileKeys.value);
  if (next.has(key)) next.delete(key); else next.add(key);
  selectedFileKeys.value = next;
};
const clearSelection = () => {
  selectedFileKeys.value = new Set();
};
const selectAllVisible = () => {
  const next = new Set(selectedFileKeys.value);
  for (const f of displayFileList.value) {
    next.add(buildApiFilePath(f.filePath, f.fileName));
  }
  selectedFileKeys.value = next;
};
const batchDelete = () => {
  const paths = Array.from(selectedFileKeys.value);
  if (paths.length === 0) return;
  ElMessageBox.confirm(
      `将批量删除 ${paths.length} 项（可在回收站恢复）。是否继续？`,
      '批量删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => {
    batchDeleteApi(props.openType, paths).then(resp => {
      const r = resp.data || {};
      const msg = r.failureCount > 0
          ? `成功 ${r.successCount}，失败 ${r.failureCount}：${(r.failures||[])[0]?.message || ''}`
          : `已删除 ${r.successCount} 项`;
      r.failureCount > 0 ? ElMessage.warning(msg) : ElMessage.success(msg);
      exitBatch();
      queryFileList();
      loadStorageUsage();
    });
  }).catch(() => {});
};
const openBatchMoveDialog = () => {
  batchMoveTargetPath.value = '/';
  batchMoveDialogVisible.value = true;
};
const batchMove = () => {
  const paths = Array.from(selectedFileKeys.value);
  if (paths.length === 0) {
    batchMoveDialogVisible.value = false;
    return;
  }
  // FilePathSelector 给的是 "/path/" 形式，要去掉首尾斜杠转成 "path" 后端期望
  const target = String(batchMoveTargetPath.value || '/').replace(/^\/+|\/+$/g, '');
  batchMoveLoading.value = true;
  batchMoveApi(props.openType, paths, target).then(resp => {
    const r = resp.data || {};
    const msg = r.failureCount > 0
        ? `成功 ${r.successCount}，失败 ${r.failureCount}：${(r.failures||[])[0]?.message || ''}`
        : `已移动 ${r.successCount} 项`;
    r.failureCount > 0 ? ElMessage.warning(msg) : ElMessage.success(msg);
    batchMoveDialogVisible.value = false;
    exitBatch();
    queryFileList();
  }).finally(() => { batchMoveLoading.value = false; });
};

// ===== M11：配额 =====
const loadStorageUsage = () => {
  if (!showQuota.value) return;
  getStorageUsageApi(props.openType).then(resp => {
    storageUsage.value = resp.data;
  }).catch(() => {});
};

// ===== M14b：外链分享 =====
const externalShareDialogVisible = ref(false);
const externalShareLoading = ref(false);
const externalShareForm = ref({
  fileName: '',
  filePath: '',
  expireMinutes: 1440,
  password: '',
  maxDownloads: null,
  enablePassword: false,
  enableMaxDownloads: false,
});
const externalShareResult = ref(null);

const externalShareExpireOptions = [
  { value: 60,        label: '1 小时' },
  { value: 60 * 24,   label: '1 天' },
  { value: 60 * 24 * 7, label: '7 天' },
  { value: 60 * 24 * 30, label: '30 天' },
];

const openExternalShareDialog = (file) => {
  externalShareForm.value = {
    fileName: file.fileName,
    filePath: buildApiFilePath(file.filePath, file.fileName),
    expireMinutes: 1440,
    password: '',
    maxDownloads: null,
    enablePassword: false,
    enableMaxDownloads: false,
  };
  externalShareResult.value = null;
  externalShareDialogVisible.value = true;
};

const closeExternalShareDialog = () => {
  externalShareDialogVisible.value = false;
  externalShareResult.value = null;
};

const submitExternalShare = () => {
  externalShareLoading.value = true;
  const f = externalShareForm.value;
  createShareApi({
    openType: props.openType,
    filePath: f.filePath,
    expireMinutes: Number(f.expireMinutes) || 1440,
    password: f.enablePassword && f.password ? f.password : undefined,
    maxDownloads: f.enableMaxDownloads && f.maxDownloads ? Number(f.maxDownloads) : undefined,
  }).then(resp => {
    const share = resp.data;
    const link = `${window.location.origin}/share/${share.shareToken}`;
    externalShareResult.value = { share, link };
    ElMessage.success('分享已创建');
  }).finally(() => {
    externalShareLoading.value = false;
  });
};

const copyShareLink = async () => {
  const link = externalShareResult.value?.link;
  if (!link) return;
  try {
    await navigator.clipboard.writeText(link);
    ElMessage.success('链接已复制到剪贴板');
  } catch (e) {
    ElMessage.warning('请手动复制链接');
  }
};

// ===== M18：版本历史 =====
const versionsDialogVisible = ref(false);
const versionsLoading = ref(false);
const versionsTargetFileName = ref('');
const versionsTargetFilePath = ref('');
const versionsList = ref([]);

const openVersionsDialog = (file) => {
  versionsTargetFileName.value = file.fileName;
  versionsTargetFilePath.value = buildApiFilePath(file.filePath, file.fileName);
  versionsList.value = [];
  versionsDialogVisible.value = true;
  loadVersions();
};

const loadVersions = () => {
  versionsLoading.value = true;
  listVersionsApi({ openType: props.openType, filePath: versionsTargetFilePath.value })
      .then(resp => { versionsList.value = resp.data || []; })
      .finally(() => { versionsLoading.value = false; });
};

const restoreVersionAction = (row) => {
  ElMessageBox.confirm(
      `还原到第 ${row.versionNo} 版（${formatVersionTime(row.createTime)}）？当前内容会自动留存为新版本。`,
      '还原版本',
      { confirmButtonText: '还原', cancelButtonText: '取消' }
  ).then(() => {
    versionsLoading.value = true;
    restoreVersionApi(row.id).then(() => {
      ElMessage.success('已还原');
      loadVersions();
      queryFileList(true);
    }).finally(() => { versionsLoading.value = false; });
  }).catch(() => {});
};

const purgeVersionAction = (row) => {
  ElMessageBox.confirm(
      `删除第 ${row.versionNo} 版快照？删除后该版本无法再还原。`,
      '删除版本',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => {
    versionsLoading.value = true;
    purgeVersionApi(row.id).then(() => {
      ElMessage.success('已删除');
      loadVersions();
    }).finally(() => { versionsLoading.value = false; });
  }).catch(() => {});
};

const formatVersionTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '';
const formatVersionBytes = (b) => {
  if (b == null) return '';
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`;
  if (b >= 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${b} B`;
};
const versionReasonLabel = (r) => ({
  OVERWRITE: '覆盖上传',
  TEXT_EDIT: '文本编辑',
  HASH_DEDUP: '秒传覆盖',
  RESTORE_DEMOTE: '还原前留底',
}[r] || (r || '-'));

// 下载文件
const downloadFile = (file) => {
  // 直接跳转到文件下载链接
  window.location.href = downloadBaseUrl + file.downloadLink;
}

//关闭移动文件选择框
const closeMoveFileDialog = () => {
  moveFileInfo.value = {
    fileName: null,
    originFileName: null,
    originFilePath: null,
    newFilePath: '/'
  };
  moveFileDialogVisible.value = false;
}

// 移动文件
const moveFile = () => {
  moveFileFormRef.value.validate((valid, fields) => {
    if (valid) {
      moveFileLoading.value = true;
      moveFileApi(
          buildApiFilePath(moveFileInfo.value.originFilePath, moveFileInfo.value.originFileName),
          buildApiFilePath(moveFileInfo.value.newFilePath, moveFileInfo.value.fileName),
          props.openType).then((response) => {
        ElMessage.success("修改成功");
        //关闭对话框
        closeMoveFileDialog();
        //重新查询文件列表
        queryFileList();
      }).finally(() => {
        moveFileLoading.value = false;
      })
    }
  })
}

// 删除文件
const deleteFile = (file) => {
  ElMessageBox.confirm(
      '删除后文件将无法恢复，是否确认删除文件【<span style="word-break: break-all;">' + file.fileName + '</span>】？',
      '是否确定',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
      }
  ).then(() => {
    //删除文件
    deleteFileApi(buildApiFilePath(file.filePath, file.fileName), props.openType).then((response) => {
      ElMessage.success('删除成功')
      queryFileList();
    })
    return done(true);
  }).catch(() => {
    return false;
  })
}

const openShareToPublicDialog = (file) => {
  if (!file) {
    return;
  }
  shareToPublicInfo.value = {
    fileName: file.fileName,
    sourceFilePath: joinFilePath(file.filePath, file.fileName),
    targetDirectoryPath: normalizeDirectoryPath(filePath.value)
  };
  shareToPublicDialogVisible.value = true;
};

const closeShareToPublicDialog = () => {
  shareToPublicInfo.value = {
    fileName: null,
    sourceFilePath: null,
    targetDirectoryPath: '/'
  };
  shareToPublicDialogVisible.value = false;
};

const sharePrivateFileToPublic = () => {
  shareToPublicFormRef.value.validate((valid) => {
    if (!valid) {
      return;
    }
    shareToPublicLoading.value = true;
    sharePrivateFileToPublicApi(
        shareToPublicInfo.value.sourceFilePath,
        shareToPublicInfo.value.targetDirectoryPath
    ).then(() => {
      ElMessage.success('共享成功');
      closeShareToPublicDialog();
    }).finally(() => {
      shareToPublicLoading.value = false;
    });
  });
};

const joinFilePath = (directoryPath, fileName) => {
  return buildApiFilePath(directoryPath, fileName);
};

const normalizeDirectoryPath = (directoryPath) => {
  let normalized = String(directoryPath || '/').replace(/\\/g, '/').trim();
  if (!normalized) {
    return '/';
  }
  normalized = normalized.replace(/\/+/g, '/');
  if (!normalized.startsWith('/')) {
    normalized = `/${normalized}`;
  }
  if (normalized !== '/' && !normalized.endsWith('/')) {
    normalized = `${normalized}/`;
  }
  return normalized;
};

const buildApiFilePath = (directoryPath, fileName) => {
  const normalizedDirectory = normalizeDirectoryPath(directoryPath);
  const normalizedFileName = String(fileName || '').trim().replace(/^\/+|\/+$/g, '');
  return `${normalizedDirectory}${normalizedFileName}`.replace(/^\/+/, '');
};

// 创建文件目录
const createDirectory = () => {
  ElMessageBox.prompt('请输入目录名称', '创建目录', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPattern: /^(?!.*[\/:*?"<>|])([^\0\\\/:*?"<>|]+(\.[^\\\/:*?"<>|]+)?)$/,
    inputErrorMessage: '目录名称不合法',
  }).then(({ value }) => {
    //移动文件
    createDirectoryApi(filePath.value + value, props.openType).then((response) => {
      ElMessage.success('创建成功')
      queryFileList();
    });
  }).catch(() => {

  })
}

// 修改目录
const changeDirectory = (subFilePath, index = -1) => {
  if (index === -1) {
    filePath.value = '/'; // 跳转到根目录
  } else {
    // 更新目录路径
    filePath.value = filePath.value.split('/').slice(0, index + 2).join('/') + '/';
  }
};

const openFile = (file) => {
  // 批量模式：点击切换选中状态
  if (batchMode.value) {
    toggleSelection(file);
    return;
  }
  //获取扩展名
  const dotIndex = file.fileName.lastIndexOf('.');
  let extName = null;
  if (dotIndex !== -1) {
    extName = file.fileName.substring(dotIndex + 1);  // 从最后一个点后返回扩展名
  }

  if (file.fileType === 'directory') {
    // 如果是目录，打开目录界面
    if (searchActive.value) {
      // 在搜索结果中点目录：跳转浏览到该目录的位置
      filePath.value = file.filePath + file.fileName + '/';
      exitSearch();
    } else {
      filePath.value = file.filePath + file.fileName + '/';
    }
  } else if (file.fileType === 'image') {
    // 打开文件预览
    imageIndex.value = getImageIndex(file);
    imageViewVisible.value = true;

    //加载对应的图片链接到显示链接
    switchImageViewer(imageIndex.value);
  }else if (file.fileType === 'video') {
    openVideoFile(file);
  }else if (file.fileType === 'document' || (!!extName && extName.toLowerCase() === 'pdf')) {
    // F9：PDF 走浏览器原生 iframe 渲染（支持打印/缩放/搜索）
    router.push({ path: '/fileServer/pdfViewer', query: { url: downloadBaseUrl + file.streamLink, name: file.fileName }});
  }else if (file.fileType === 'text') {
    // 文本文件 → 内置编辑器（≤1 MB 可读写）
    const path = buildApiFilePath(file.filePath, file.fileName);
    router.push({ path: '/fileServer/textViewer', query: { openType: props.openType, filePath: path }});
  }else if (!!extName && extName === 'epub'){
    //跳转到epub浏览目录
    router.push({path: '/fileServer/epubViewer', query: { url: downloadBaseUrl + file.streamLink}});
  }else {
    ElMessage.info('当前文件暂不支持预览，可下载后查看');
  }
};

const openVideoFile = (file) => {
  if (file.transcodeState === 'SUCCESS') {
    if (!file.transcodedStreamLink) {
      ElMessage.info('转码视频正在准备在线播放，请稍后刷新');
      return;
    }
    createVideoRoomFromFile(file, file.transcodedStreamLink);
    return;
  }

  if (file.transcodeState === 'TOO_LARGE') {
    ElMessage.warning(file.transcodeMessage || `视频超过 ${formatFileSize(file.transcodeMaxBytes)}，无法在线播放，可下载后观看`);
    return;
  }

  if (file.transcodeState === 'FAILED' || file.transcodeState === 'UNSUPPORTED') {
    ElMessage.warning(file.transcodeMessage || '视频暂时无法在线播放');
    return;
  }

  if (!file.streamLink) {
    ElMessage.info('视频正在准备在线播放，请稍后刷新');
    return;
  }

  if (file.transcodeState === 'WAITING') {
    ElMessage.info('视频正在等待转码，本次播放原视频');
  } else if (file.transcodeState === 'PROCESSING') {
    ElMessage.info(`视频正在转码 ${file.transcodeProgress || 0}%，本次播放原视频`);
  }

  createVideoRoomFromFile(file, file.streamLink);
};

const createVideoRoomFromFile = (file, streamLink) => {
  if (!streamLink) {
    ElMessage.info('视频链接不存在，请稍后刷新');
    return;
  }
  const playMyVideoRequest = {
    roomName: file.fileName,
    filePath: `${file.filePath || ''}${file.fileName}`,
    directoryOpenFlag: props.openType,
    preferTranscoded: file.transcodeState === 'SUCCESS',
  }
  playMyVideo(playMyVideoRequest).then((response) => {
    router.push(`/fileServer/videoRoom/${response.data.roomId}`);
  });
};

const switchImageViewer = (index) => {
  //加载对应的图片链接到显示链接
  imageSrcList.value[index] = imageFullSrcList.value[index]
}

const closeImageViewer = () => {
  imageViewVisible.value = false;
};

// 获取当前目录下的文件
let transcodePollTimer = null;

const queryFileList = (silent = false) => {
  if (!silent) {
    fileListLoading.value = true;
  }
  getFileList(filePath.value, props.openType).then((response) => {
    fileList.value = response.data.sort((a, b) => a.fileName.localeCompare(b.fileName));
    //封装图片列表
    imageFullSrcList.value = fileList.value.filter((file) => file.fileType === "image")
        .map((file) => downloadBaseUrl + file.streamLink);
    //封装空图片列表，到对应图片时才加载
    imageSrcList.value = new Array(imageFullSrcList.value.length).fill("");
  }).catch(() => {
  }).finally(() => {
    if (!silent) {
      fileListLoading.value = false;
    }
    scheduleTranscodePolling();
  });
};

const scheduleTranscodePolling = () => {
  clearTranscodePollTimer();
  const hasRunningTask = fileList.value.some((file) => file.fileType === 'video'
      && ['WAITING', 'PROCESSING'].includes(file.transcodeState));
  if (hasRunningTask) {
    transcodePollTimer = setTimeout(() => queryFileList(true), 4000);
  }
};

const clearTranscodePollTimer = () => {
  if (transcodePollTimer) {
    clearTimeout(transcodePollTimer);
    transcodePollTimer = null;
  }
};

const getVideoStatusText = (file) => {
  if (file.transcodeState === 'WAITING') {
    return '等待转码';
  }
  if (file.transcodeState === 'PROCESSING') {
    return `转码中 ${file.transcodeProgress || 0}%`;
  }
  if (file.transcodeState === 'TOO_LARGE') {
    return '视频过大';
  }
  if (file.transcodeState === 'FAILED') {
    return '转码失败';
  }
  if (file.transcodeState === 'UNSUPPORTED') {
    return '不支持';
  }
  return file.transcodeMessage || '准备中';
};

const formatFileSize = (bytes) => {
  if (!bytes) {
    return '当前上限';
  }
  if (bytes >= 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}GB`;
  }
  return `${Math.round(bytes / 1024 / 1024)}MB`;
};

// 获取当前图片的索引
const getImageIndex = (file) => {
  if (file.fileType !== "image") return -1;
  return imageFullSrcList.value.indexOf(downloadBaseUrl + file.streamLink);
};

// 当文件拖到页面区域时触发
const handlePageDragOver = (event) => {
  // 设置拖动状态为 true，显示遮罩
  pageDragging.value = true;
  dropAreaDragging.value = true;
};

// 当文件拖到放置区域时触发
const handleDropAreaDragOver = (event) => {
  event.preventDefault();
  dropAreaDragging.value = true; // 设置拖动状态为 true，显示遮罩
};

// 当文件离开放置区域时触发
const handleDropAreaDragLeave = () => {
  pageDragging.value = false;
  dropAreaDragging.value = false; // 设置拖动状态为 false，隐藏遮罩
};

// 处理文件拖放
const handleDrop = (event) => {
  event.preventDefault();
  pageDragging.value = false; // 文件放置后，关闭遮罩
  dropAreaDragging.value = false; // 文件放置后，关闭遮罩

  // 获取拖放的文件
  const files = event.dataTransfer.files;
  // 获取所有拖拽项
  const items = event.dataTransfer.items;
  logger.debug("Dropped files:", files);

  if (files.length === 0) {
    ElMessage({ message: '不存在可上传的文件', type: 'warning' })
    return;
  }

  // 这里可以添加处理文件的逻辑，比如上传
  let allValid = true;

  // 遍历所有拖拽的项目，检查是否为文件夹或其他类型
  for (let i = 0; i < items.length; i++) {
    const item = items[i];

    // 检查是否是文件夹
    const entry = item.webkitGetAsEntry && item.webkitGetAsEntry();
    if (entry && entry.isDirectory) {
      ElMessage({ message: '浏览器无法上传文件夹，请选择文件后重新上传', type: 'warning' })
      allValid = false;
      return;
    }
  }

  //清空上传文件列表
  uploadFileList.value = [];
  //将每个文件封装一个form数据，添加到上传文件列表
  for (let i = 0; i < files.length; i++) {
    let file = files[i];

    const formData = new FormData();
    formData.append("file", file);
    formData.append("fileName", file.name);
    formData.append("filePath", filePath.value);
    formData.append("coverFlag", false);
    formData.append("openType", props.openType);
    uploadFileList.value.push(formData);
  }
  //打开上传子界面
  fileUploading.value = true;
  //将文件上传完成状态置为false
  uploadAllComplete.value = false;
};

// 文件上传
const fileUploadChange = (file, fileList) => {
  if (fileList.length > 0) {
    //清空上传文件列表
    uploadFileList.value = [];
    //将每个文件封装一个form数据，添加到上传文件列表
    fileList.forEach((file) => {
      const formData = new FormData();
      formData.append("file", file.raw); // 'raw' 对应的是后端的 MultipartFile 字段
      formData.append("fileName", file.name);
      formData.append("filePath", filePath.value);
      formData.append("coverFlag", false);
      formData.append("openType", props.openType);
      uploadFileList.value.push(formData);
    });
    //打开上传子界面
    fileUploading.value = true;
    //将文件上传完成状态置为false
    uploadAllComplete.value = false;
  }
}

// 关闭文件上传
const fileUploadClose = (done) => {
  if (uploadAllComplete.value === true) {
    clearAllUploadComponents();
    fileUploading.value = false;
    return done(true);
  }else {
    ElMessageBox.confirm(
        '因为浏览器限制，关闭该界面后，未上传完成的文件将会取消，是否确认关闭?',
        '确认关闭',
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning',
        }
    ).then(() => {
      clearAllUploadComponents();
      fileUploading.value = false;
      return done(true);
    }).catch(() => {
      return false;
    })
  }
}

// 监听路径变化，重新获取文件列表
watch(filePath, (newPath) => {
  // 获取当前路径的前缀部分，假设当前路径是以 '/files' 开头
  const pathSplit = route.path.split('/');
  const currentPathPrefix = pathSplit[1] + '/' + pathSplit[2];

  router.push(`/${currentPathPrefix}${newPath}`);
});

// 监听 URL 中的文件路径，初始化 filePath
watch(() => route.params.path, (newPath) => {
  if (Array.isArray(newPath)) {
    // 合并路径数组
    filePath.value = '/' + newPath.join('/') + '/';
  } else if (newPath) {
    // 单一路径处理
    filePath.value = `/${newPath}/`;
  } else {
    filePath.value = `/`;
  }

  // 重新获取文件列表
  queryFileList();
}, { immediate: true });

// 初始化文件列表 + 配额
queryFileList();
loadStorageUsage();

// 切换 openType（仅 prop 变化时触发，例如从私人切到公共）也要重新拉配额
watch(() => props.openType, () => {
  exitBatch();
  exitSearch();
  loadStorageUsage();
});
</script>

<style scoped>
/* ---------- Header ---------- */
.file-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  background: var(--color-bg-base);
  border-bottom: 1px solid var(--color-border-subtle);
}

@media (max-width: 640px) {
  .file-header {
    padding: var(--space-3) var(--space-4);
  }
}

/* ---------- Path breadcrumb ---------- */
.file-path-choose {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  overflow-x: auto;
  white-space: nowrap;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.file-path-choose::-webkit-scrollbar {
  display: none;
}

.choose-text {
  color: var(--color-text-secondary);
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--radius-sm);
  transition: color var(--duration-fast) var(--ease-standard),
              background var(--duration-fast) var(--ease-standard);
}

.choose-text:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}

/* Last segment (current directory) gets accent.
   Two cases:
   - At root: the only span is `<span class="choose-text">根目录</span>`,
     so target it directly.
   - Deeper: the last span is a wrapper that contains the choose-text
     inside (`<span>/<span class="choose-text">name</span></span>`). */
.file-path-choose > div > span.choose-text:last-of-type,
.file-path-choose > div > span:last-of-type .choose-text {
  color: var(--accent);
  font-weight: var(--font-weight-medium);
}

/* ---------- Action buttons (desktop only) ---------- */
.file-actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
  align-items: center;
}

.file-search-input {
  width: 240px;
}

.file-search-info {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  min-width: 0;
}

.file-search-info-icon {
  width: 16px;
  height: 16px;
  color: var(--accent);
}

/* 配额条 */
.quota-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px var(--space-5);
  background: var(--color-bg-base);
  border-bottom: 1px solid var(--color-border-subtle);
}

.quota-bar-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.quota-bar-used {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.quota-bar-total {
  color: var(--color-text-tertiary);
}

.quota-bar-count {
  color: var(--color-text-tertiary);
  margin-left: 8px;
}

.quota-bar-progress {
  width: 280px;
  max-width: 100%;
}

/* 批量栏 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px var(--space-5);
  background: var(--accent-soft);
  border-bottom: 1px solid var(--color-border-subtle);
}

.batch-bar-count {
  font-size: var(--font-size-sm);
  color: var(--accent);
  font-weight: var(--font-weight-medium);
  margin-right: var(--space-2);
}

/* 卡片选中态 */
.file-card.file-card-batch {
  cursor: pointer;
  position: relative;
}

.file-card.file-card-selected {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.file-card-checkbox {
  position: absolute;
  top: 8px;
  left: 8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(255,255,255,0.9);
  border: 1.5px solid var(--color-border-default);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 5;
  pointer-events: none;
}

.file-card-checkbox.checked {
  background: var(--accent);
  border-color: var(--accent);
}

.file-card-check-icon {
  width: 14px;
  height: 14px;
  color: white;
}

/* ---------- Empty state ---------- */
.file-empty {
  /* 让空态独占整个 grid，居中对齐 */
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-12) var(--space-4);
  min-height: 320px;
  color: var(--color-text-secondary);
  text-align: center;
}

.file-empty-icon {
  font-size: 64px;
  color: var(--color-border-strong);
  margin-bottom: var(--space-2);
}

.file-empty-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.file-empty-hint {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}

.file-empty-cta {
  margin-top: var(--space-4);
  min-width: 160px;
}

/* ---------- Grid container ---------- */
.file-container {
  padding: var(--space-4) var(--space-5) var(--space-6);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: var(--space-3);
  align-content: start;
}

@media (max-width: 640px) {
  .file-container {
    padding: var(--space-3) var(--space-4) var(--space-12);
    grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
    gap: var(--space-2);
  }
}

/* ---------- File card ---------- */
.file-card {
  display: flex;
  flex-direction: column;
  width: 100%;
  /* Card aspect ratio close to original 108x130 (~ 4:5) */
  aspect-ratio: 4 / 5;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  user-select: none;
  -webkit-touch-callout: none;
  transition:
      border-color var(--duration-fast) var(--ease-standard),
      background var(--duration-fast) var(--ease-standard),
      transform var(--duration-fast) var(--ease-standard);
}

.file-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border-default);
  transform: translateY(-1px);
}

.file-card:active {
  transform: translateY(0);
}

.file-card-main {
  flex: 0 0 65%;
  width: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
  /* Override Element Plus el-main defaults: 20px padding + box-shadow */
  padding: 0 !important;
  background: var(--color-bg-muted);
  box-shadow: none !important;
}

.file-card-footer {
  flex: 0 0 35%;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  /* Override Element Plus el-footer default 0 60px padding + height */
  padding: var(--space-2) var(--space-3) !important;
  height: auto !important;
  background: var(--color-bg-surface);
}

@media (max-width: 640px) {
  .file-card-footer {
    padding: var(--space-1) var(--space-2) !important;
  }
}

/* Element Plus 图标线条风格，密度收紧后用 40px (桌面) / 32px (移动)，
   配合更紧凑的卡片宽度让一屏能看到更多文件。
   颜色：文件夹用 accent（导航主体），其他类型用 text-secondary 中性色。 */
.file-show {
  width: 40px;
  height: 40px;
  color: var(--color-text-secondary);
}

@media (max-width: 640px) {
  .file-show {
    width: 32px;
    height: 32px;
  }
}

.file-show-folder {
  color: var(--accent);
}

/* el-image 预览缩略图：填满整个 card-main，不受 file-show 48px 限制 */
.file-preview {
  width: 100%;
  height: 100%;
}

/* ---------- Video preview tile ---------- */
.video-file-show {
  position: relative;
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
}

.video-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-status-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: var(--space-2);
  background: rgba(31, 27, 22, 0.55);
  box-sizing: border-box;
}

.video-status-text {
  color: #fff;
  text-align: center;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  word-break: break-word;
}

.video-progress {
  width: 100%;
}

/* ---------- File name (footer) ---------- */
.wrap-and-ellipsis {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  text-overflow: ellipsis;
  word-wrap: break-word;
  word-break: break-all;
  text-align: center;
  font-size: var(--font-size-sm);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
}

@media (max-width: 640px) {
  .wrap-and-ellipsis {
    font-size: var(--font-size-xs);
  }
}

/* ---------- Fullscreen drag overlay (desktop only) ---------- */
.fullscreen-overlay {
  position: fixed;
  inset: 0;
  background: rgba(31, 27, 22, 0.45);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: var(--z-overlay);
}

.drop-area {
  width: calc(100% - var(--space-12));
  height: calc(100% - var(--space-12));
  border: 2px dashed rgba(255, 255, 255, 0.5);
  border-radius: var(--radius-lg);
  display: flex;
  justify-content: center;
  align-items: center;
  color: #fff;
  font-size: var(--font-size-lg);
  background: transparent;
  transition: background var(--duration-base) var(--ease-standard),
              border-color var(--duration-base) var(--ease-standard);
}

.drop-area.dragging {
  background: rgba(194, 65, 12, 0.16);
  border-color: var(--accent);
}

/* ---------- Custom right-click menu ---------- */
.context-menu {
  position: fixed;
  z-index: var(--z-dialog);
  width: 160px;
  padding: var(--space-1) 0;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.context-menu ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.context-menu li {
  padding: var(--space-2) var(--space-4);
  cursor: pointer;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard);
}

.context-menu li:hover {
  background: var(--color-bg-hover);
}

/* ---------- Mobile FAB ---------- */
.file-fab-wrap {
  position: fixed;
  right: var(--space-4);
  /* Lift above the tab bar + safe area */
  bottom: calc(var(--layout-tab-bar-height) + var(--space-4) + env(safe-area-inset-bottom));
  z-index: var(--z-sticky);
}

.file-fab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-pill);
  background: var(--accent);
  color: var(--color-text-on-accent);
  box-shadow: var(--shadow-lg);
  transition: background var(--duration-fast) var(--ease-standard),
              transform var(--duration-fast) var(--ease-standard);
}

.file-fab:active {
  background: var(--accent-strong);
  transform: scale(0.96);
}

.file-fab :deep(svg) {
  width: 22px;
  height: 22px;
}

/* Hidden el-upload trigger (for FAB-driven upload) */
.file-upload-hidden {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
  pointer-events: none;
}

.file-upload-hidden-btn {
  width: 0;
  height: 0;
  opacity: 0;
}

/* ---------- Loading mask uses surface, not a hard white ---------- */
:deep(.el-loading-mask) {
  background-color: rgba(250, 248, 245, 0.7);
}

</style>
