<template>
  <div class="upload-host">
    <div class="upload-container">
      <div class="upload-card" v-for="(fileUploadState, index) in fileUploadStateList" :key="index">
        <div class="upload-content">
          <div class="upload-name">{{ fileUploadState.name }}</div>
          <el-progress class="upload-progress"
                       :percentage="fileUploadState.progress"
                       :status="getProgressStatusFromUploadState(fileUploadState.uploadState)">
            {{ getUploadStateShowFromUploadState(fileUploadState) }}
          </el-progress>
          <div class="upload-actions" v-if="fileUploadState.uploadState === 4">
            <span class="re-upload-action"
                  @click="coverUpload(fileUploadState)">覆盖</span>
            <span class="re-upload-action"
                  @click="renameAndUpload(fileUploadState)">重命名</span>
          </div>
          <div class="upload-actions" v-if="fileUploadState.uploadState === 3">
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
import SparkMD5 from 'spark-md5';
import { uploadFile } from '@/api/fileServer/fileServer';
import { checkUploadByHash, getUploadStatus } from '@/api/fileServer/fileServerMvp';
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

// 分片 4MB：高延迟链路下摊薄每请求的建连/往返开销；单片须远小于 Cloudflare 源站 100s 超时（4MB 最坏 ~80s）
const chunkSize = 4 * 1024 * 1024;
// 高延迟链路（如 Cloudflare 隧道）下并发上传分片，吞吐随并发线性提升；后端每用户上限 8，取 4 留余量
const UPLOAD_CONCURRENCY = 4;

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

// M12：分片增量算 MD5（spark-md5），10MB 一片，控制单次同步耗时
const HASH_CHUNK_SIZE = 10 * 1024 * 1024;
const computeFileMd5 = async (file) => {
  const spark = new SparkMD5.ArrayBuffer();
  const totalChunks = Math.ceil(file.size / HASH_CHUNK_SIZE);
  for (let i = 0; i < totalChunks; i++) {
    const start = i * HASH_CHUNK_SIZE;
    const end = Math.min(start + HASH_CHUNK_SIZE, file.size);
    const buf = await file.slice(start, end).arrayBuffer();
    spark.append(buf);
  }
  return spark.end();
};

//上传文件并更新状态
const uploadFileAndUpdateState = async (fileUploadState, form) => {
  //将文件设置为正在上传状态
  fileUploadState.uploadState = 1;

  // 获取文件和分片大小
  const file = form.get("file");
  const totalChunks = Math.ceil(file.size / chunkSize);

  // M12：上传前先做哈希秒传探测；命中 → 跳过分片上传
  if (file.size > 0 && fileUploadState._md5Tried !== true) {
    fileUploadState._md5Tried = true;
    try {
      fileUploadState.failMessage = '正在校验秒传...';
      const md5 = await computeFileMd5(file);
      const filePathRaw = String(form.get('filePath') || '/').replace(/^\/+|\/+$/g, '');
      const checkResp = await checkUploadByHash({
        openType: Number(form.get('openType')),
        fileName: form.get('fileName'),
        filePath: filePathRaw,
        fileMd5: md5,
        fileSize: file.size,
        coverFlag: form.get('coverFlag') === 'true' || form.get('coverFlag') === true
      });
      const state = checkResp.data?.state;
      if (state === 1) {
        // 秒传成功 — 用 5 区分原本的 2（完整上传成功）
        fileUploadState.progress = 100;
        fileUploadState.uploadState = 5;
        fileUploadState.failMessage = '';
        emit('uploadSuccess');
        // 整体收尾判断（2 / 5 都算完成）
        let allDone = fileUploadStateList.value.every(s => s.uploadState === 2 || s.uploadState === 5);
        if (allDone) emit('uploadAllComplete');
        return;
      } else if (state === 2) {
        // 同名冲突，等用户选覆盖/重命名
        fileUploadState.uploadState = 4;
        fileUploadState.failMessage = '文件已存在';
        return;
      }
      // state === 0 → 未命中，落后续分片上传逻辑
      fileUploadState.failMessage = '';
    } catch (err) {
      // hash check 异常不阻塞上传，按原流程继续
      fileUploadState.failMessage = '';
    }
  }

  // F8 续传探测：先查后端已落盘的分片索引，跳过已传部分
  let alreadyUploaded = new Set();
  try {
    const statusResp = await getUploadStatus({
      openType: Number(form.get('openType')),
      fileName: form.get('fileName'),
      filePath: String(form.get('filePath') || '/').replace(/^\/+|\/+$/g, ''),
      totalChunks
    });
    alreadyUploaded = new Set(statusResp.data?.uploadedChunks || []);
    if (alreadyUploaded.size > 0) {
      // 进度条立刻反映已传部分
      fileUploadState.progress = Math.round((alreadyUploaded.size / totalChunks) * 100);
      fileUploadState.failMessage = `从第 ${alreadyUploaded.size} / ${totalChunks} 片继续`;
    }
    // 边界：所有分片都在但合并没完成（进程崩溃 / 网络中断在合并阶段）→ 重传最后一片触发后端合并
    if (alreadyUploaded.size >= totalChunks) {
      alreadyUploaded.delete(totalChunks - 1);
    }
  } catch (e) {
    // 探测失败不阻断，回落到从 0 开始重传
    alreadyUploaded = new Set();
  }

  // 待传分片索引（跳过已落盘的），后端支持乱序上传、全片到齐才合并，故可并发
  const pendingChunks = [];
  for (let i = 0; i < totalChunks; i++) {
    if (!alreadyUploaded.has(i)) pendingChunks.push(i);
  }

  let completedChunks = alreadyUploaded.size; // 已落盘的也计入进度
  let aborted = false;
  let abortResult = null; // { uploadState, failMessage }

  // 上传单个分片
  const uploadChunk = async (i) => {
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
      const response = await uploadFile(chunkFormData);
      if (response.data.uploadState === 1) {
        completedChunks++;
        fileUploadState.progress = Math.round((completedChunks / totalChunks) * 100);
      } else if (response.data.uploadState === 2) {
        aborted = true;
        abortResult = { uploadState: 4, failMessage: '文件已存在' };
      } else {
        aborted = true;
        abortResult = { uploadState: 3, failMessage: response.data.uploadStateMessage };
      }
    } catch (error) {
      aborted = true;
      abortResult = { uploadState: 3, failMessage: '服务器异常' };
    }
  };

  // 并发池：最多 UPLOAD_CONCURRENCY 片同时在飞；任一片失败即停止取新任务
  let cursor = 0;
  const worker = async () => {
    while (!aborted && cursor < pendingChunks.length) {
      await uploadChunk(pendingChunks[cursor++]);
    }
  };
  const poolSize = Math.min(UPLOAD_CONCURRENCY, pendingChunks.length) || 1;
  await Promise.all(Array.from({ length: poolSize }, () => worker()));

  if (aborted) {
    fileUploadState.uploadState = abortResult.uploadState;
    fileUploadState.failMessage = abortResult.failMessage;
  } else {
    fileUploadState.progress = 100;
    fileUploadState.uploadState = 2;
    emit('uploadSuccess');
  }

  let uploadAllComplete = true;
  //判断是否存在未完成上传的文件（2 = 普通上传成功；5 = 秒传成功）
  for (const fileUploadState of fileUploadStateList.value) {
    if (fileUploadState.uploadState !== 2 && fileUploadState.uploadState !== 5) {
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
    return fileUploadState.failMessage || '正在上传';
  }else if (fileUploadState.uploadState === 2) {
    return '上传成功';
  }else if (fileUploadState.uploadState === 5) {
    return '⚡ 秒传成功';
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
  }else if (uploadState === 2 || uploadState === 5) {
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
.upload-host {
  /* 把列表限定在弹窗或宿主容器内的可见高度，列表过长不撑大父级 */
  max-height: min(60vh, 480px);
  overflow-y: auto;
}

.upload-container {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  width: 100%;
}

.upload-card {
  width: 100%;
  box-sizing: border-box;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}

.upload-content {
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.upload-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-all;
  font-weight: var(--font-weight-medium);
}

.upload-progress {
  margin-top: var(--space-1);
}

.upload-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-1);
}

.re-upload-action {
  color: var(--color-danger);
  cursor: pointer;
  text-decoration-line: underline;
  font-size: var(--font-size-sm);
  /* 移动端命中区域 */
  min-height: 32px;
  display: inline-flex;
  align-items: center;
}
</style>
