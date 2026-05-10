<template>
  <div class="quiz-container">
    <el-card shadow="hover" class="card" v-loading="loading">
      <div class="mode-switch">
        <el-radio-group v-model="mode">
          <el-radio-button label="it_to_zh">意大利语 → 中文</el-radio-button>
          <el-radio-button label="zh_to_it">中文 → 意大利语</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 词库为空时的提示 -->
      <div v-if="wordList.length === 0" class="empty-message">
        词库为空，请加载词汇数据！
      </div>

      <!-- 显示当前单词 -->
      <div v-else class="word-display" @click="showDetail(currentWord)">
        <span class="prompt-word">
          {{ mode === 'it_to_zh' ? currentWord.it : currentWord.cn }}
        </span>
      </div>

      <el-input
          v-model="userAnswer"
          placeholder="请输入答案"
          @keyup.enter="checkAnswer"
          :disabled="wordList.length === 0"
      />

      <div class="action-buttons">
        <el-button type="primary" @click="checkAnswer" :disabled="wordList.length === 0">提交</el-button>
        <el-button @click="revealAnswer" type="info" :disabled="wordList.length === 0">显示答案</el-button>
        <el-button @click="nextWord" type="success" :disabled="wordList.length === 0">下一题</el-button>
      </div>

      <div v-if="feedback" class="feedback" :class="{ correct: isCorrect, wrong: !isCorrect }">
        {{ feedback }}
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
          <div v-html="formattedDescription"></div>  <!-- 使用 v-html 渲染 -->
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'

const { isMobile } = useBreakpoint()

// 接收父组件传入的词库
const props = defineProps({
  wordList: {
    type: Array,
    default: () => []
  }
})

const mode = ref('it_to_zh') // 或 'zh_to_it'
const currentIndex = ref(0)
const userAnswer = ref('')
const feedback = ref('')
const isCorrect = ref(false)
const loading = ref(false)

const dialogVisible = ref(false)  // 控制详情对话框的显示
const selectedWord = ref({})  // 存储选中的单词

// 监听词库变化
watch(() => props.wordList, (newWordList) => {
  if (newWordList.length > 0) {
    // 如果词库有数据，初始化当前单词
    currentIndex.value = Math.floor(Math.random() * newWordList.length)
  }
})

const wordList = computed(() => props.wordList)
const currentWord = computed(() => wordList.value[currentIndex.value])

const checkAnswer = () => {
  const correct =
      mode.value === 'it_to_zh'
          ? currentWord.value.cn
          : currentWord.value.it

  if (userAnswer.value.trim().toLowerCase() === correct.toLowerCase()) {
    feedback.value = '✅ 回答正确！'
    isCorrect.value = true
  } else {
    feedback.value = `❌ 回答错误，正确答案是：${correct}`
    isCorrect.value = false
  }
}

const revealAnswer = () => {
  const correct =
      mode.value === 'it_to_zh'
          ? currentWord.value.cn
          : currentWord.value.it
  feedback.value = `💡 正确答案是：${correct}`
  isCorrect.value = false
}

const nextWord = () => {
  currentIndex.value = Math.floor(Math.random() * wordList.value.length)
  userAnswer.value = ''
  feedback.value = ''
}

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

</script>

<style scoped>
.quiz-container {
  padding-top: var(--space-3);
}

.card {
  padding: var(--space-5);
  text-align: center;
}

@media (max-width: 640px) {
  .card {
    padding: var(--space-4) var(--space-3);
  }
}

.word-display {
  font-size: var(--font-size-2xl);
  margin: var(--space-5) 0;
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  word-break: break-word;
}

.prompt-word {
  color: var(--accent);
}

.action-buttons {
  margin-top: var(--space-4);
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
}

.action-buttons .el-button {
  min-height: 44px;
}

@media (max-width: 640px) {
  .action-buttons {
    /* 三个按钮在 360 不挤压：等宽自适应 */
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: var(--space-2);
  }

  .action-buttons .el-button {
    margin-left: 0;
    width: 100%;
  }
}

.feedback {
  margin-top: var(--space-5);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}

.correct {
  color: var(--color-success);
}

.wrong {
  color: var(--color-danger);
}

.mode-switch {
  margin-bottom: var(--space-4);
}

.empty-message {
  color: var(--color-danger);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  margin-top: var(--space-5);
}

/* 防 iOS 输入框缩放 */
.quiz-container :deep(.el-input__inner) {
  font-size: var(--font-size-md);
}
</style>
