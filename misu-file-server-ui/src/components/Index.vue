<script setup>
import { ref, onMounted, provide } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { cacheUserInfo, getUserInfo, getUserInfoFromToken } from '@/api/user/user'
import SideNav from '@/components/layout/SideNav.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import TabBar from '@/components/layout/TabBar.vue'
import { chatLayout } from '@/components/chat/chatLayout.js'

const { isMobile, isDesktop } = useBreakpoint()

const userInfo = ref(getUserInfo() || {})
provide('userInfo', userInfo)

onMounted(() => {
  getUserInfoFromToken()
      .then(response => {
        if (response && response.data) {
          userInfo.value = response.data
          cacheUserInfo(response.data)
        }
      })
      .catch(() => {})
})
</script>

<template>
  <div
      class="app-shell"
      :class="[isMobile ? 'app-shell-mobile' : 'app-shell-desktop']">
    <SideNav v-if="isDesktop" />

    <div class="app-main-column">
      <PageHeader v-if="isDesktop" />
      <main class="app-content" :class="{ 'no-tab-bar': isMobile && chatLayout.hideTabBar }">
        <router-view />
      </main>
    </div>

    <TabBar v-if="isMobile && !chatLayout.hideTabBar" />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  width: 100%;
  min-height: 100svh;
  /* Prefer dvh on supported devices for keyboard safety */
  min-height: 100dvh;
  background: var(--shell-bg);
}

.app-shell-mobile {
  flex-direction: column;
}

.app-main-column {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.app-content {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}

/* Direct children that don't opt into full-height fill (e.g. Home)
   keep their natural flow; ones that want to fill the viewport
   (BotChat, future VideoRoom / FileExplorer) set flex: 1 1 auto on
   their root. */

.app-shell-mobile .app-content {
  padding-bottom: calc(var(--layout-tab-bar-height) + env(safe-area-inset-bottom));
}

/* 进入会话全屏聊天：TabBar 隐藏，去掉为它预留的底部内边距，聊天直达底部 */
.app-shell-mobile .app-content.no-tab-bar {
  padding-bottom: 0;
}
</style>
