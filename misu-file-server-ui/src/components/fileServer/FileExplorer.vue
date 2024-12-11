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
      <div class="file-actions">
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
                    @click="openFile(file)"
                    @contextmenu.prevent="showContextMenu($event, file)"
                    @touchstart="fileTouchStart($event, file)"
                    @touchend="fileTouchEnd"
                    @touchcancel="fileTouchCancel">
        <el-main class="file-card-main">
          <Folder v-if="file.fileType === 'directory'" class="file-show"/>
          <el-image v-if="file.fileType === 'image' && !!file.previewLink" class="file-show"
                    :src="downloadBaseUrl + file.previewLink"
                    fit="contain"
                    loading="lazy" />
          <Picture v-if="file.fileType === 'image' && !file.previewLink" class="file-show"/>
          <Document v-if="file.fileType === 'other'" class="file-show"/>
          <Film v-if="file.fileType === 'video'" class="file-show"/>
        </el-main>
        <el-footer class="file-card-footer">
          <div class="wrap-and-ellipsis" :title="file.fileName">
            {{ file.fileName }}
          </div>
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

    <VideoViewer v-if="videoVisible"
        :video-url="videoUrl"
        @close="videoVisible = false"/>

    <!-- 拖拽区域 -->
    <div class="fullscreen-overlay"
         v-if="pageDragging || dropAreaDragging"
         @dragover.prevent="handleDropAreaDragOver"
         @dragleave="handleDropAreaDragLeave"
         @drop="handleDrop">
      <div
          class="drop-area"
          :class="{'dragging': pageDragging || dropAreaDragging}">
        拖动文件到该位置上传
      </div>
    </div>

    <el-dialog
        v-model="fileUploading"
        title="文件上传列表"
        style="width: 70%;"
        :before-close="fileUploadClose">
      <file-upload
          v-if="fileUploading"
          style="height: 350px;"
          :upload-file-list="uploadFileList"
          @upload-success="queryFileList"
          @upload-all-complete="uploadAllComplete = true"/>
    </el-dialog>

    <!-- 自定义右键菜单 -->
    <div
        v-if="menuVisible"
        ref="rightMenu"
        :style="menuStyles"
        class="context-menu"
    >
      <ul>
        <li @click="onMenuItemClick('download')">下载</li>
        <li @click="onMenuItemClick('rename')">重命名</li>
        <li @click="onMenuItemClick('delete')">删除</li>
        <li @click="onMenuItemClick('share')">分享</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import {Document, Film, Folder, Picture} from "@element-plus/icons-vue";
import {defineProps, onBeforeUnmount, onMounted, ref, watch} from "vue";
import VideoViewer from '@/components/fileServer/VideoViewer.vue'
import FileUpload from '@/components/fileServer/FileUpload.vue'
import {ElMessage, ElMessageBox} from "element-plus";
import {
  getFileList,
  moveFile,
  deleteFile as deleteFileApi,
  createDirectory as createDirectoryApi,
  getFileDownloadLink
} from '@/api/fileServer/fileServer';
import { useRouter, useRoute } from 'vue-router';

// 接收外部传入的接口函数
const props = defineProps({
  openType: {
    type: Number,
    required: true,
  }
});

//当前文件路径
const filePath = ref("/");
//当前目录的文件列表
const fileList = ref([]);
//文件列表的加载函数
const fileListLoading = ref(false);
// 下载文件基本url
const downloadBaseUrl = import.meta.env.VITE_RESOURCE_API

// 图片组件相关参数
const imageIndex = ref(0);
// 图片组件用的链接列表，需要到指定图片才请求接口，会初始化一个空字符串数组，加载到指定图片时，才对指定位置链接赋值
const imageSrcList = ref([]);
// 与上图列表对应，是图片实际地址，加载到指定图片时，将对应位置链接赋值到上面列表对应位置
const imageFullSrcList = ref([]);
const imageViewVisible = ref(false);

//视频组件相关参数
const videoVisible = ref(false);
const videoUrl = ref('');

// 是否存在文件拖拽
const pageDragging = ref(false);
const dropAreaDragging = ref(false);

// 上传文件列表
const fileUploadComponent = ref();
// 文件是否正在上传标识
const fileUploading = ref(false);
// 上传文件列表
const uploadFileList = ref([]);
// 文件列表是否全部上传完成
const uploadAllComplete = ref(true);

// 右键菜单显示
const menuVisible = ref(false);
const rightMenu = ref();
const menuPosition = ref({ top: 0, left: 0 });
const menuStyles = ref({ top: 0, left: 0 });
// 菜单选择的文件
const menuChooseFile = ref(null);
// 移动端菜单长按时间阈值（单位：毫秒）
const LONG_PRESS_TIME = 600;
let menuPressTimer = null;  // 用于存储定时器ID
let isFileLongPress = false;  // 是否长按文件

// 使用 Vue Router
const router = useRouter();
const route = useRoute();

// 展示右键菜单
const showContextMenu = (event, file) => {
  event.preventDefault(); // 阻止默认右键菜单

  const menuWidth = 100; // 假设菜单宽度是 200px
  const menuHeight = 150; // 假设菜单高度是 150px
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

  // 显示菜单
  menuStyles.value = {
    top: `${top}px`,
    left: `${left}px`,
    position: 'fixed', // 固定定位菜单位置
    zIndex: 1000, // 保证菜单显示在最上面
  };

  menuVisible.value = true;
  menuChooseFile.value = file;
};

// 触摸开始事件
const fileTouchStart = (event, file) => {
  menuChooseFile.value = file;

  isFileLongPress = false;
  menuPressTimer = setTimeout(() => {
    // 达到长按阈值时触发长按事件
    isFileLongPress = true;
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
  }else if (option === 'rename') {
    renameFile(menuChooseFile.value);
  }else if (option === 'delete') {
    deleteFile(menuChooseFile.value);
  }else if (option === 'share') {
    shareFile(menuChooseFile.value);
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

// 重命名文件
const renameFile = (file) => {
  ElMessageBox.prompt('请输入修改后的名称', '修改文件名称', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: file.fileName,
    inputPattern: /^(?!.*[\/:*?"<>|])([^\0\\\/:*?"<>|]+(\.[^\\\/:*?"<>|]+)?)$/,
    inputErrorMessage: '文件名称不合法',
  }).then(({ value }) => {
    if (value === file.fileName) {
      ElMessage.warning('文件名称不存在修改')
      return;
    }

    //移动文件
    moveFile(file.filePath + file.fileName, file.filePath + value, props.openType).then((response) => {
      ElMessage.success('修改成功')
      queryFileList();
    });
  }).catch(() => {

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
    deleteFileApi(file.filePath + file.fileName, props.openType).then((response) => {
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
  getFileDownloadLink(file.filePath + file.fileName, props.openType).then((response) => {
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
  if (file.fileType === 'directory') {
    // 如果是目录，打开目录界面
    filePath.value = file.filePath + file.fileName + '/';
  } else {
    // 打开文件预览
    if (file.fileType === 'image') {
      imageIndex.value = getImageIndex(file);
      imageViewVisible.value = true;

      //加载对应的图片链接到显示链接
      switchImageViewer(imageIndex.value);
    }else if (file.fileType === 'video') {
      videoVisible.value = true;
      videoUrl.value = downloadBaseUrl + file.downloadLink
    }
  }
};

const switchImageViewer = (index) => {
  //加载对应的图片链接到显示链接
  imageSrcList.value[index] = imageFullSrcList.value[index]
}

const closeImageViewer = () => {
  imageViewVisible.value = false;
};

// 获取当前目录下的文件
const queryFileList = () => {
  fileListLoading.value = true;
  getFileList(filePath.value, props.openType).then((response) => {
    fileListLoading.value = false;
    fileList.value = response.data;
    //封装图片列表
    imageFullSrcList.value = fileList.value.filter((file) => file.fileType === "image")
        .map((file) => downloadBaseUrl + file.downloadLink);
    //封装空图片列表，到对应图片时才加载
    imageSrcList.value = new Array(imageFullSrcList.value.length).fill("");
  }).catch(() => {
    fileListLoading.value = false;
  });
};

// 获取当前图片的索引
const getImageIndex = (file) => {
  if (file.fileType !== "image") return -1;
  return imageFullSrcList.value.indexOf(downloadBaseUrl + file.downloadLink);
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
    fileUploadComponent.value.clearFiles();
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
      fileUploadComponent.value.clearFiles();
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
.file-header {
  display: flex; /* 使用 flexbox 排列子元素 */
  justify-content: space-between; /* 左侧是路径，右侧是按钮区域 */
  align-items: center; /* 垂直居中 */
}

.file-path-choose {
  display: flex;
  padding-left: 20px;
  align-items: center;
}

.file-actions {
  display: flex; /* 用 flex 布局让按钮排成一行 */
  gap: 10px; /* 按钮之间的间距 */
  padding-left: 30px;
  padding-right: 20px;
  padding-top: 10px;
}

.choose-text {
  color: var(--el-color-primary);
  cursor: pointer; /* 鼠标悬停时变为手型 */
}

.file-container {
  padding-top: 20px;
  padding-left: 20px;
  padding-bottom: 20px;
  display: flex;
  flex-wrap: wrap; /* 自动换行 */
  gap: 15px; /* 卡片之间的间距 */
  justify-content: flex-start; /* 让最后一行的卡片左对齐 */
}

.file-show {
  max-width: 80%;
  max-height: 100%;
}

.file-card {
  min-width: 108px;
  max-width: 108px;
  height: 130px;
  box-sizing: border-box; /* 确保宽高不受内边距影响 */
  box-shadow: var(--el-box-shadow-lighter);
  cursor: pointer; /* 鼠标悬停时变为手型 */

  -webkit-touch-callout:none;
  -webkit-user-select:none;
  -khtml-user-select:none;
  -moz-user-select:none;
  -ms-user-select:none;
  user-select:none;

}

.file-card-main {
  width: 100%;
  height: 65%;
  box-shadow: var(--el-box-shadow-lighter);
  display: grid;
  place-items: center; /* 水平和垂直居中 */
}

.file-card-footer {
  width: 100%;
  height: 35%;
}

.wrap-and-ellipsis {
  padding-top: 5px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2; /* 控制最多显示的行数 */
  overflow: hidden;
  text-overflow: ellipsis;
  word-wrap: break-word;
  text-align: center;
  font-size: 13px;
}

.fullscreen-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999; /* 保证它在最上层 */
}

/* 基本样式 */
.drop-area {
  width: 95%;
  height: 95%;
  border: 2px dashed #ccc;
  transition: background-color 0.3s;
  text-align: center;
  line-height: 200px;
  position: relative;
  top: 0;
  left: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  color: white;
  font-size: 18px;
  pointer-events: auto;
}

.dragging {
  background-color: rgba(0, 0, 0, 0.1); /* 拖拽时背景颜色 */
}

.context-menu {
  position: fixed;
  z-index: 1000; /* 确保菜单在最上层 */
  background-color: white;
  border: 1px solid #ccc;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
  width: 100px; /* 固定宽度 */
}

.context-menu ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.context-menu li {
  padding: 8px 16px;
  cursor: pointer;
}

.context-menu li:hover {
  background-color: #f0f0f0;
}

</style>
