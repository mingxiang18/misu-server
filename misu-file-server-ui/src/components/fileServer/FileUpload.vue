<template>
  <div>
    <div class="upload-container" style="max-height: 100%;overflow-y: auto">
      <div class="upload-card" v-for="(fileUploadState, index) in fileUploadStateList" :key="index">
        <div class="upload-content">
          <div>{{ fileUploadState.name }}</div>
          <el-progress style="padding-top: 5px"
                       :percentage="fileUploadState.progress"
                       :status="getProgressStatusFromUploadState(fileUploadState.uploadState)">
            {{ getUploadStateShowFromUploadState(fileUploadState) }}
          </el-progress>
          <div style="padding-top: 10px;" v-if="fileUploadState.uploadState === 4">
            <span class="re-upload-action"
                  @click="coverUpload(fileUploadState)">覆盖</span>
            <span class="re-upload-action" style="padding-left: 10px;"
                  @click="renameAndUpload(fileUploadState)">重命名</span>
          </div>
          <div style="padding-top: 10px;" v-if="fileUploadState.uploadState === 3">
            <span class="re-upload-action"
                  @click="uploadFileAndUpdateState(fileUploadState, fileUploadState.form)">重试</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { uploadFile } from '@/api/fileServer/fileServer';
import {ElMessageBox} from "element-plus";

// 声明自定义事件
const emit = defineEmits(['uploadSuccess', 'uploadAllComplete']);

// 接收外部传入的接口函数
const props = defineProps({
  uploadFileList: {
    type: Array,
    required: true,
  }
});

const fileUploadStateList = ref([])

const chunkSize = 1024 * 1024; // 上传分片大小为1MB

//将文件form封装为文件上传状态列表
const formatFileStateList = () => {
  props.uploadFileList.forEach((uploadFile) => {
    // 提取 formData 中的属性
    const fileState = {
      name: uploadFile.get("fileName"),
      uploadState: 0,  //0-等待上传，1-正在上传，2-上传成功，3-上传失败，4-存在同名文件
      progress: 0, // 上传进度
      failMessage: "",
      form: uploadFile,
    };

    // 将文件状态添加到文件上传状态列表
    fileUploadStateList.value.push(fileState);
  });
}

//重命名文件并上传
const renameAndUpload = (fileUploadState) => {
  ElMessageBox.prompt('请输入修改后的名称', '修改文件名称', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: fileUploadState.name,
    inputPattern: /^(?!.*[\/:*?"<>|])([^\0\\\/:*?"<>|]+(\.[^\\\/:*?"<>|]+)?)$/,
    inputErrorMessage: '文件名称不合法',
  }).then(({ value }) => {
    fileUploadState.name = value;
    fileUploadState.form.delete("fileName");
    fileUploadState.form.append("fileName", value);

    //上传文件
    uploadFileAndUpdateState(fileUploadState, fileUploadState.form)
  }).catch(() => {

  })
}

//覆盖上传
const coverUpload = (fileUploadState) => {
  const clonedFormData = new FormData();

  //拷贝完整的form数据
  fileUploadState.form.forEach((value, key) => {
    if (key === 'coverFlag') {
      //设置coverFlag为true
      clonedFormData.append("coverFlag", true);
    }else {
      // 使用 append 添加键值对
      clonedFormData.append(key, value);
    }
  });

  //上传文件
  uploadFileAndUpdateState(fileUploadState, clonedFormData)
}

//上传文件并更新状态
const uploadFileAndUpdateState = async (fileUploadState, form) => {
  //将文件设置为正在上传状态
  fileUploadState.uploadState = 1;

  // 获取文件和分片大小
  const file = form.get("file");
  const totalChunks = Math.ceil(file.size / chunkSize);

  // 上传每个分片
  for (let i = 0; i < totalChunks; i++) {
    //计算分片的开始和结束位置，切割出分片
    const start = i * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    const chunk = file.slice(start, end);

    const chunkFormData = new FormData();
    chunkFormData.append('file', chunk);
    chunkFormData.append('fileName', form.get("fileName"));
    chunkFormData.append('chunkIndex', i); // 当前分片索引
    chunkFormData.append('totalChunks', totalChunks); // 总分片数
    chunkFormData.append('fileSize', file.size);
    chunkFormData.append('filePath', form.get("filePath"));
    chunkFormData.append('coverFlag', form.get("coverFlag"));
    chunkFormData.append('openType', form.get("openType"));

    try {
      // 上传当前分片
      const response = await uploadFile(chunkFormData);

      // 如果当前分片上传成功，更新状态
      if (response.data.uploadState === 1) {
        // 更新进度
        fileUploadState.progress = Math.round(((i + 1) / totalChunks) * 100);
        //如果当前已经是该文件最后一个分片，更新状态为上传完成
        if (i === totalChunks - 1) {
          fileUploadState.uploadState = 2;
          // 每上传成功，触发上传成功事件
          emit('uploadSuccess');
        }
      } else if (response.data.uploadState === 2) {
        fileUploadState.uploadState = 4; // 文件已存在
        fileUploadState.failMessage = '文件已存在';
        break; // 终止上传
      } else {
        fileUploadState.uploadState = 3; // 上传失败
        fileUploadState.failMessage = response.data.uploadStateMessage;
        break; // 终止上传
      }
    } catch (error) {
      fileUploadState.uploadState = 3; // 上传失败
      fileUploadState.failMessage = '服务器异常';
      break; // 终止上传
    }
  }

  let uploadAllComplete = true;
  //判断是否存在未完成上传的文件
  for (const fileUploadState of fileUploadStateList.value) {
    if (fileUploadState.uploadState !== 2) {
      uploadAllComplete = false;
    }
  }
  if (uploadAllComplete === true) {
    emit('uploadAllComplete');
  }
}

//从文件上传状态获取描述信息
const getUploadStateShowFromUploadState = (fileUploadState) => {
  if (fileUploadState.uploadState === 0) {
    return '等待上传';
  }else if (fileUploadState.uploadState === 1) {
    return '正在上传';
  }else if (fileUploadState.uploadState === 2) {
    return '上传成功';
  }else {
    return '上传失败，' + fileUploadState.failMessage;
  }
}

//从文件上传状态获取进度条显示状态信息
const getProgressStatusFromUploadState = (uploadState) => {
  if (uploadState === 0) {
    return '';
  }else if (uploadState === 1) {
    return '';
  }else if (uploadState === 2) {
    return 'success';
  }else {
    return 'exception';
  }
}

//开始上传文件
const startUploadFile = async () => {
  for (const fileUploadState of fileUploadStateList.value) {
    //上传文件
    await uploadFileAndUpdateState(fileUploadState, fileUploadState.form)
  }
}

formatFileStateList();
startUploadFile();
</script>

<style scoped>
.upload-container {
  display: flex;
  gap: 1px;
  flex-wrap: wrap; /* 自动换行 */
  justify-content: flex-start; /* 让最后一行的卡片左对齐 */
  width: 100%; /* 父容器宽度为 100% */
}

.upload-card {
  flex: 0 0 calc(100% - 10px); /* 每个卡片占据一行，但确保有间隙 */
  box-sizing: border-box; /* 确保宽高不受内边距影响 */
  box-shadow: var(--el-box-shadow-lighter);
  max-width: 95%;
  margin: 5px; /* 每个卡片的外边距，给左右和上下加点空隙 */
}

.upload-content {
  padding: 10px;
}

.re-upload-action {
  color: var(--el-color-danger);
  cursor: pointer;
  text-decoration-line: underline;
}

</style>
