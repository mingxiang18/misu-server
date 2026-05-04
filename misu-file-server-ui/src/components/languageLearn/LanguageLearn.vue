<template>
  <div class="excel-upload-container">
    <el-tabs v-model="selectedOption">
      <el-tab-pane label="自定义词库" name="uploadWord" class="button-actions">
        <el-upload
            action=""
            :before-upload="beforeUpload"
            :on-change="handleFileChange"
            :show-file-list="false"
        >
          <el-button slot="trigger" type="primary">上传词库</el-button>
        </el-upload>
        <!-- 下载模板按钮 -->
        <el-button type="info" @click="downloadTemplate">下载词库模板</el-button>
      </el-tab-pane>
      <el-tab-pane label="全部词库" name="allWord">
      </el-tab-pane>
    </el-tabs>

    <!-- 显示词库表格 -->
    <WordQuiz :wordList="jsonData" v-loading="loading"></WordQuiz>
    <DailyLearn :wordList="jsonData" v-loading="loading"></DailyLearn>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElUpload, ElButton } from 'element-plus'
import DailyLearn from "@/components/languageLearn/DailyLearn.vue";
import WordQuiz from "@/components/languageLearn/WordQuiz.vue";
import * as XLSX from "xlsx";

const loading = ref(false)

// 用来存储解析后的 JSON 数据
const jsonData = ref([])

// 用户选择的操作：上传文件还是选择默认词库
const selectedOption = ref('uploadWord')

// 处理文件上传
const beforeUpload = (file) => {
  const isExcel = file.type === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' || file.type === 'application/vnd.ms-excel'
  if (!isExcel) {
    ElMessage.error('只支持上传 Excel 文件!')
  }
  return isExcel
}

// 处理文件变化
const handleFileChange = (file, fileList) => {
  const reader = new FileReader()
  reader.onload = (e) => {
    const data = e.target.result
    const workbook = XLSX.read(data, { type: 'binary' })

    const sheetName = workbook.SheetNames[0]
    const worksheet = workbook.Sheets[sheetName]

    const rawData = XLSX.utils.sheet_to_json(worksheet)

    // 转换为自定义的 it、cn、des 格式
    const transformedData = rawData.map(row => ({
      it: row['单词'], // 假设Excel中的Key列是意大利语
      cn: row['中文释义'], // 简单释义
      des: row['详细描述'] // 详细释义
    }))

    // 更新 jsonData
    jsonData.value = transformedData
  }

  reader.readAsBinaryString(file.raw)
}

// 加载默认的词库数据
const loadDefaultWords = async () => {
  loading.value = true

  try {
    // 使用 fetch 获取 public 目录下的 JSON 文件
    const response = await fetch('/italian_dictionary.json')

    // 确保文件请求成功
    if (!response.ok) {
      throw new Error('文件加载失败')
    }

    // 解析 JSON 数据
    const data = await response.json()

    // 处理加载的 JSON 数据
    jsonData.value = data
  } catch (error) {
    ElMessage.error('加载词库失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 直接下载 public 目录下的模板文件
const downloadTemplate = async () => {
  const templateUrl = '/italian_word_template.xlsx';

  try {
    // 发起请求获取文件数据
    const response = await fetch(templateUrl);

    // 检查请求是否成功
    if (!response.ok) {
      throw new Error('模板文件加载失败');
    }

    // 获取文件的二进制数据
    const blob = await response.blob();

    // 创建一个临时链接，触发文件下载
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'italian_word_template.xlsx'; // 设置文件下载的名称
    link.click();

    // 释放掉之前创建的对象URL
    URL.revokeObjectURL(link.href);

    // 给用户反馈
    ElMessage.success('模板下载开始');

  } catch (error) {
    ElMessage.error('下载失败: ' + error.message);
  }
}

// 当 props 中的 words 数据变化时，重置分页
watch(() => selectedOption.value, (newSelectedOption) => {
  if (newSelectedOption === 'uploadWord') {
    jsonData.value = []
  } else {
    loadDefaultWords()
  }
})
</script>

<style scoped>
.excel-upload-container {
  padding: 20px;
}
.button-actions {
  display: flex; /* 用 flex 布局让按钮排成一行 */
  gap: 10px; /* 按钮之间的间距 */
}
</style>
