<template>
  <!-- 文件路径选择器 -->
  <el-cascader
      class="custom-cascader"
      v-model="selectedFilePathInternal"
      :options="directoryOptions"
      @change="handleChange"
      placeholder="请选择文件路径"
      :props="cascaderProps"
  />
</template>

<script setup>
import {defineEmits, defineProps, onMounted, ref, watch} from "vue";
import {getFileList as getFileListApi} from '@/api/fileServer/fileServer';

// 接收外部传入的接口函数
const props = defineProps({
  openType: {
    type: Number,
    required: true,
  },
  modelValue: {  // 接收外部传入的 selectedFilePath
    type: String,
    default: () => '/',  // 默认值为根目录
  },
});

// 发送值给父组件的事件
const emit = defineEmits(['update:modelValue']);  // 定义事件，用于更新外部绑定的值

// 内部用于双向绑定的变量
const selectedFilePathInternal = ref(['/']);  // Cascader 需要路径数组
const directoryOptions = ref([{
  value: '/',
  label: '根目录',
  children: []
}]); // 初始化根目录的选项
const fileListLoading = ref(false);   // 加载状态

// Cascader 组件的属性配置
const cascaderProps = {
  checkStrictly: true,
  lazy: true,
  lazyLoad(node, resolve) {
    queryFileDirectoryList(toDirectoryPath(node.value)).then(directories => {
      // 为当前目录添加子目录
      const children = directories.map((file) => ({
        value: joinNodeValue(file.filePath, file.fileName),
        label: file.fileName,
        children: []
      }));
      resolve(children);
    });
  },
};

// 获取当前目录下的文件夹，子目录通过路径递归加载
const queryFileDirectoryList = async (path) => {
  fileListLoading.value = true;
  return await getFileListApi(toDirectoryPath(path), props.openType)
      .then((response) => {
        fileListLoading.value = false;
        return response.data.filter((file) => file.fileType === "directory");
      })
      .catch((error) => {
        fileListLoading.value = false;
        console.error(error)
      });
};

// 处理路径变化，加载选中目录的子目录
const handleChange = (value) => {
  const targetPath = Array.isArray(value) && value.length > 0 ? value[value.length - 1] : '/';
  emit('update:modelValue', toDirectoryPath(targetPath));
};

// 在组件挂载时加载根目录
onMounted(() => {
  const normalizedPath = toDirectoryPath(props.modelValue);
  selectedFilePathInternal.value = toPathNodes(normalizedPath);
  if (normalizedPath !== props.modelValue) {
    emit('update:modelValue', normalizedPath);
  }
});

// 监听外部传入的 modelValue（用于响应父组件的更新）
watch(() => props.modelValue, (newValue) => {
  const normalizedPath = toDirectoryPath(newValue);
  const nextNodes = toPathNodes(normalizedPath);
  const currentNodes = selectedFilePathInternal.value;
  if (JSON.stringify(nextNodes) !== JSON.stringify(currentNodes)) {
    selectedFilePathInternal.value = nextNodes;
  }
});

const toDirectoryPath = (path) => {
  let normalized = String(path || '/').replace(/\\/g, '/').trim();
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

const toPathNodes = (path) => {
  const normalized = toDirectoryPath(path);
  if (normalized === '/') {
    return ['/'];
  }
  const parts = normalized.split('/').filter(Boolean);
  const nodes = ['/'];
  let current = '';
  parts.forEach((part) => {
    current = `${current}/${part}`;
    nodes.push(current);
  });
  return nodes;
};

const joinNodeValue = (filePath, fileName) => {
  const base = toDirectoryPath(filePath);
  return `${base}${fileName}`.replace(/\/+/g, '/').replace(/\/$/, '');
};
</script>

<style>
/* 使用 !important 强制覆盖样式 */
.el-cascader-panel {
  max-width: 80vw !important;
  overflow-x: auto !important;
}
</style>
