<template>
  <div class="epub-reader">
    <!-- EPUB 内容展示 -->
    <div class="reader-container" ref="readerContainer">
      <!-- This will hold the rendered EPUB content -->
    </div>

    <!-- 翻页控制按钮 -->
    <div class="controls">
      <el-button @click="previousPage">上一页</el-button>
      <el-button @click="openToc">目录</el-button>
      <el-button @click="nextPage">下一页</el-button>
    </div>
  </div>

  <!-- 目录对话框 -->
  <el-dialog
      v-model="tocVisible"
      style="width: 80%;"
      title="目录">

    <!-- 目录展示 -->
    <div class="toc-container">
      <el-tree
          :data="toc"
          node-key="href"
          :props="tocProps"
          :default-expanded-keys="[selectedChapter]"
          @nodeClick="goToChapter"
          check-strictly>
        <template #default="{ node, data }">
          <span class="custom-tree-node" :class="getNodeClass(node)">
            <span>{{ node.label }}</span>
          </span>
        </template>
      </el-tree>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref, onMounted} from 'vue';
import Epub from 'epubjs';
import {ElMessage} from "element-plus";
import { useRoute } from 'vue-router';

// 接收外部传入的接口函数
const route = useRoute();
const url = route.query.url;  // 获取文件请求url

const readerContainer = ref();  // 用来挂载 EPUB 渲染内容的元素
let book = null;
let rendition = null;

//目录相关
const tocVisible = ref(false);
const toc = ref([]);  // 存储目录内容
const selectedChapter = ref('');  // 当前选中的章节ID

const tocProps = {
  children: 'subitems',
  label: 'label',
  id: 'href',
}

// 返回节点的 CSS 类
const getNodeClass = (node) => {
  // 如果节点的 id 等于当前选中的章节 ID，则为该节点添加选中样式
  return node.data.href === selectedChapter.value ? 'selected-node' : '';
};

// 选择文件并加载
const onFileSelected = () => {
  fetch(url)
      .then(response => {
        if (!response.ok) {
          ElMessage.error("网络异常，无法获取到文件");
        }
        return response.arrayBuffer();  // 获取文件的二进制数据
      })
      .then(file => {
        loadEpub(file);
      })
      .catch(error => {
        console.error('Error fetching file:', error);
        ElMessage.error("epub载入失败");
      });
};

// 加载 EPUB 文件
const loadEpub = (file) => {
  book = Epub(file);  // 使用 epub.js 加载 EPUB 文件

  // 初始化 EPUB 渲染
  rendition = book.renderTo(readerContainer.value, {
    width: '100%',
    height: '100%',
    flow: 'scrolled', // 设置为上下滚动
  });

  // 获取书籍的元数据（如作者、标题等）
  book.loaded.metadata.then((metadata) => {
    console.log('Metadata:', metadata);
  });

  // 获取目录并存储到 toc 中
  book.loaded.navigation.then((tocData) => {
    toc.value = tocData.toc;
  });

  // 显示书籍的第一个章节
  rendition.display();

  // 监听章节变化，更新目录选中项
  rendition.on('relocated', (location) => {
    const currentSection = location.start.href;
    if (currentSection) {
      selectedChapter.value = currentSection; // 更新当前选中的章节
    }
  });
};

// 跳转到指定章节
const goToChapter = (data) => {
  rendition.display(data.href);
  selectedChapter.value = data.href;  // 更新选中的章节
};

// 打开目录
const openToc = () => {
  tocVisible.value = true;
};

// 显示上一页
const previousPage = () => {
  if (rendition) {
    rendition.prev();
  }
};

// 显示下一页
const nextPage = () => {
  if (rendition) {
    rendition.next();
  }
};

onMounted(() => {
  onFileSelected();
});
</script>

<style scoped>
.epub-reader {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 5px;
  height: 100%;
}

.reader-container {
  border: 1px solid #ccc;
  width: 100%;
  height: 92%;
  overflow: hidden;
}

.toc-container {
  width: 100%;
}

.controls {
  display: flex;
  justify-content: space-between; /* 平均分配每个项目的空间 */
  width: 100%;
  padding-top: 10px;
}

.selected-node {
  color: #3498db;
}
</style>
