<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Folder, VideoCamera, ChatDotRound, Memo } from '@element-plus/icons-vue'
import { getUserInfo } from '@/api/user/user'

const router = useRouter()

const userName = ref('')
onMounted(() => {
  const u = getUserInfo() || {}
  userName.value = u.userName || ''
})

const cards = [
  {
    key: 'files',
    title: '文件',
    subtitle: '我的私人目录与公共目录',
    icon: Folder,
    to: '/fileServer/privateDirectory'
  },
  {
    key: 'videoRoom',
    title: '放映室',
    subtitle: '进房一起看',
    icon: VideoCamera,
    to: '/fileServer/videoRoom'
  },
  {
    key: 'bot',
    title: '机器人',
    subtitle: '与 Bot 对话',
    icon: ChatDotRound,
    to: '/bot'
  },
  {
    key: 'learn',
    title: '学习',
    subtitle: '每日单词与测验',
    icon: Memo,
    to: '/languageLearn'
  }
]

const greet = () => {
  const h = new Date().getHours()
  if (h < 5) return '深夜好'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

const goTo = (path) => router.push(path)
</script>

<template>
  <div class="home">
    <header class="home-header">
      <h1 class="home-title">{{ greet() }}{{ userName ? '，' + userName : '' }}</h1>
      <p class="home-subtitle">开始使用你的工具</p>
    </header>

    <section class="home-grid">
      <button
        v-for="card in cards"
        :key="card.key"
        class="home-card"
        @click="goTo(card.to)"
      >
        <span class="home-card-icon">
          <component :is="card.icon" />
        </span>
        <span class="home-card-meta">
          <span class="home-card-title">{{ card.title }}</span>
          <span class="home-card-subtitle">{{ card.subtitle }}</span>
        </span>
      </button>
    </section>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-5) var(--space-12);
}

.home-header {
  margin-bottom: var(--space-8);
}

.home-title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}

.home-subtitle {
  margin-top: var(--space-2);
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
}

/* Mobile: 2 列；Desktop: 4 列 */
.home-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
}

@media (min-width: 641px) {
  .home-grid {
    grid-template-columns: repeat(4, 1fr);
    gap: var(--space-5);
  }
}

.home-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-5);
  min-height: 144px;
  padding: var(--space-5);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

@media (min-width: 641px) {
  .home-card {
    min-height: 220px;
  }
}

.home-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border-default);
  transform: translateY(-1px);
}

.home-card:active {
  background: var(--color-bg-pressed);
  transform: translateY(0);
}

.home-card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: var(--radius-md);
  font-size: 22px;
}

.home-card-icon :deep(svg) {
  width: 20px;
  height: 20px;
}

.home-card-meta {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  width: 100%;
}

.home-card-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.home-card-subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
