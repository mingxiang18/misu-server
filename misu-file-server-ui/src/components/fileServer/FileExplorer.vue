<template>
  <div style="height: 100%"
       @dragover.prevent="handlePageDragOver">
    <div class="file-header">
      <div class="file-path-choose">
        <div style="display: inline;">
          路径：/<span class="choose-text" @click="changeDirectory('')">根目录</span>
          <span v-for="(subFilePath, index) in filePath.split('/').filter(Boolean)" :key="index">
            /<span class="choose-text" @click="changeDirectory(subFilePath, index)">{{ subFilePath }}</span>
          </span>
        </div>
      </div>
      <div v-if="isDesktop" class="file-actions">
        <el-button type="primary" @click="createDirectory">新建目录</el-button>
        <el-upload
            ref="fileUploadComponent"
            :on-change="fileUploadChange"
            :auto-upload=false
            :show-file-list=false
            multiple
            :limit="100">
          <el-button type="primary">上传</el-button>
        </el-upload>
      </div>
    </div>

    <div class="file-container" v-loading="fileListLoading">
      <el-container class="file-card" v-for="file in fileList" :key="file.filePath"
                    @contextmenu.prevent="showContextMenu($event, file)"
                    @touchstart="fileTouchStart($event, file)"
                    @touchend="fileTouchEnd($event, file)"
                    @touchcancel="fileTouchCancel">
        <el-main class="file-card-main" @click="openFile(file)">
          <Folder v-if="file.fileType === 'directory'" class="file-show file-show-folder"/>
          <el-image v-if="file.fileType === 'image' && !!file.previewLink" class="file-preview"
                    :src="downloadBaseUrl + file.previewLink"
                    fit="cover"
                    loading="lazy" />
          <Picture v-if="file.fileType === 'image' && !file.previewLink" class="file-show"/>
          <Document v-if="file.fileType === 'other'" class="file-show"/>
          <div v-if="file.fileType === 'video'" class="video-file-show">
            <el-image v-if="!!file.videoPreviewLink" class="video-preview"
                      :src="downloadBaseUrl + file.videoPreviewLink"
                      fit="cover"
                      loading="lazy" />
            <Film v-else class="file-show"/>
            <div v-if="file.transcodeState && file.transcodeState !== 'SUCCESS'" class="video-status-mask">
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

    <!-- 自定义右键菜单 -->
    <div
        v-show="menuVisible"
        ref="rightMenu"
        :style="menuStyles"
        class="context-menu"
    >
      <ul ref="rightMenuUl">
        <li @click="onMenuItemClick('download')">下载</li>
        <li @click="onMenuItemClick('move')">移动/重命名</li>
        <li @click="onMenuItemClick('share')">分享</li>
        <li v-if="canSharePrivateFileToPublic" @click="onMenuItemClick('shareToPublic')">共享到公共目录</li>
        <li @click="onMenuItemClick('delete')">删除</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import {Document, Film, Folder, Picture, Plus, FolderAdd, Upload} from "@element-plus/icons-vue";
import {computed, defineProps, onBeforeUnmount, onMounted, ref, watch} from "vue";
import { useBreakpoint } from '@/composables/useBreakpoint';
import VideoViewer from '@/components/fileServer/VideoViewer.vue'
import FileUpload from '@/components/fileServer/FileUpload.vue'
import {ElMessage, ElMessageBox} from "element-plus";
import {
  getFileList,
  moveFile as moveFileApi,
  deleteFile as deleteFileApi,
  createDirectory as createDirectoryApi,
  getFileDownloadLink,
  sharePrivateFileToPublic as sharePrivateFileToPublicApi
} from '@/api/fileServer/fileServer';
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
  }else if (option === 'share') {
    shareFile(menuChooseFile.value);
  }else if (option === 'shareToPublic') {
    openShareToPublicDialog(menuChooseFile.value);
  }
  // 点击后隐藏菜单
  menuVisible.value = false;
  menuChooseFile.value = null;
};

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

// 分享文件
const shareFile = (file) => {
  getFileDownloadLink(buildApiFilePath(file.filePath, file.fileName), props.openType).then((response) => {
    let shareLink = downloadBaseUrl + response.data;

    ElMessageBox.alert(
        '当前链接1天内有效：<a style="word-break: break-all;">' + shareLink + '</a>',
        '分享',
        {
          dangerouslyUseHTMLString: true,
        }
    )
  }).catch(() => {
    ElMessage.error('获取分享链接失败')
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
  //获取扩展名
  const dotIndex = file.fileName.lastIndexOf('.');
  let extName = null;
  if (dotIndex !== -1) {
    extName = file.fileName.substring(dotIndex + 1);  // 从最后一个点后返回扩展名
  }

  if (file.fileType === 'directory') {
    // 如果是目录，打开目录界面
    filePath.value = file.filePath + file.fileName + '/';
  } else if (file.fileType === 'image') {
    // 打开文件预览
    imageIndex.value = getImageIndex(file);
    imageViewVisible.value = true;

    //加载对应的图片链接到显示链接
    switchImageViewer(imageIndex.value);
  }else if (file.fileType === 'video') {
    openVideoFile(file);
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
  console.log("Dropped files:", files);

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

// 初始化文件列表
queryFileList();
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
}

/* ---------- Grid container ---------- */
.file-container {
  padding: var(--space-4) var(--space-5) var(--space-6);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-3);
  align-content: start;
}

@media (max-width: 640px) {
  .file-container {
    padding: var(--space-3) var(--space-4) var(--space-12);
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
    gap: var(--space-3);
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

/* Element Plus 图标是线条风格，原 80% 大小在大卡片里显得空洞细弱。
   固定到 48px（移动 40px），让图标视觉更聚焦。
   颜色：文件夹用 accent（导航主体），其他类型用 text-secondary 中性色。 */
.file-show {
  width: 48px;
  height: 48px;
  color: var(--color-text-secondary);
}

@media (max-width: 640px) {
  .file-show {
    width: 40px;
    height: 40px;
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
