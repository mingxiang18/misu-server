<template>
  <div class="word-list-container">
    <el-card shadow="hover">
      <!-- 搜索栏 -->
      <el-input
          v-model="searchQuery"
          placeholder="搜索单词或释义"
          clearable
          class="search-input"
      >
        <template #append>
          <el-button :icon="Search" />
        </template>
      </el-input>

      <!-- 桌面表格 -->
      <el-table
          v-if="isDesktop"
          :data="paginatedWords"
          class="word-table"
          stripe
          v-loading="loading"
          :empty-text="'暂时没有记录学习单词'"
      >
        <el-table-column prop="it" label="意大利语"/>
        <el-table-column prop="cn" label="中文翻译" min-width="180"/>
        <el-table-column label="操作" min-width="120">
          <template #default="scope">
            <div class="table-option">
              <el-button link type="primary" @click="playAudio(scope.row.it)">发音</el-button>
              <el-button link type="primary" @click="showDetail(scope.row)">详情</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 移动卡片 -->
      <div v-else class="word-cards" v-loading="loading">
        <div v-if="!loading && paginatedWords.length === 0" class="word-empty">
          暂时没有记录学习单词
        </div>
        <article v-for="(word, idx) in paginatedWords" :key="`${word.it}-${idx}`" class="word-card">
          <div class="word-card-it">{{ word.it }}</div>
          <div class="word-card-cn">{{ word.cn }}</div>
          <div class="word-card-actions">
            <el-button size="small" type="primary" plain @click="playAudio(word.it)">发音</el-button>
            <el-button size="small" type="primary" plain @click="showDetail(word)">详情</el-button>
          </div>
        </article>
      </div>

      <!-- 分页 -->
      <div class="pagination">
        <el-pagination
            background
            :small="isMobile"
            layout="prev, pager, next"
            :page-size="pageSize"
            :pager-count="5"
            :total="filteredWords.length"
            v-model:current-page="currentPage"
        />
      </div>
    </el-card>

    <!-- 单词详情对话框 -->
    <el-dialog
        v-model="dialogVisible"
        :width="isMobile ? undefined : 'min(640px, 80vw)'"
        title="单词详情"
    >
      <el-descriptions column="1" border>
        <el-descriptions-item label="意大利语">{{ selectedWord.it }}</el-descriptions-item>
        <el-descriptions-item label="详细释义">
          <div v-html="formattedDescription"></div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Search } from "@element-plus/icons-vue";
import { useBreakpoint } from '@/composables/useBreakpoint';

const { isMobile, isDesktop } = useBreakpoint();

// 通过 props 接收父组件传入的词汇列表
const props = defineProps({
  wordList: {
    type: Array,
    required: true
  }
})

const currentPage = ref(1)
const pageSize = 10
const loading = ref(false)
const searchQuery = ref('')  // 搜索查询
const dialogVisible = ref(false)  // 控制详情对话框的显示
const selectedWord = ref({})  // 存储选中的单词

// 筛选出符合搜索条件的单词（按意大利语和中文翻译）
const filteredWords = computed(() => {
  if (!searchQuery.value) {
    return props.wordList
  }

  const query = searchQuery.value.toLowerCase()
  currentPage.value = 1

  // 给每个单词计算匹配度并排序
  return props.wordList
      .map(word => {
        const italian = word.it.toLowerCase()
        const chinese = word.cn.toLowerCase()

        // 计算意大利语匹配度
        const italianScore = italian.startsWith(query) ? 2 : italian.includes(query) ? 1 : 0
        // 计算中文翻译匹配度
        const chineseScore = chinese.startsWith(query) ? 2 : chinese.includes(query) ? 1 : 0

        // 计算总的匹配度分数（意大利语 + 中文）
        const score = italianScore + chineseScore

        return {
          word,
          score
        }
      })
      .filter(word => word.score > 0)  // 过滤掉没有匹配的单词
      .sort((a, b) => b.score - a.score) // 按照匹配度从高到低排序
      .map(word => word.word) // 返回排好序的单词
})

const paginatedWords = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredWords.value.slice(start, start + pageSize)
})

// 播放意大利语单词音频（简单语音合成）
const playAudio = (text) => {
  const utter = new SpeechSynthesisUtterance(text)
  const voicesList = window.speechSynthesis.getVoices()
  utter.lang = 'it-IT' // 意大利语发音
  utter.voice = voicesList.find((voice) => voice.lang === 'it-IT')
  window.speechSynthesis.speak(utter)
}

// 显示单词详情
const showDetail = (word) => {
  selectedWord.value = word
  dialogVisible.value = true
}

// 格式化释义，将换行符替换为 <br> 标签
const formattedDescription = computed(() => {
  if (selectedWord.value.des) {
    return selectedWord.value.des.replace(/\n/g, '<br>')  // 替换换行符
  }
  return ''
})

// 当 props 中的 words 数据变化时，重置分页
watch(() => props.wordList, () => {
  currentPage.value = 1
})
</script>

<style scoped>
.word-list-container {
  padding-top: var(--space-3);
}

.search-input {
  margin-bottom: var(--space-4);
}

.word-table {
  width: 100%;
}

.pagination {
  margin-top: var(--space-4);
  display: flex;
  justify-content: flex-end;
}

.table-option {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

/* ---------- Mobile cards ---------- */
.word-cards {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.word-empty {
  padding: var(--space-12) var(--space-4);
  text-align: center;
  color: var(--color-text-tertiary);
}

.word-card {
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.word-card-it {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  word-break: break-word;
}

.word-card-cn {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  word-break: break-word;
}

.word-card-actions {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-2);
}

@media (max-width: 640px) {
  .pagination {
    justify-content: center;
  }
}
</style>
