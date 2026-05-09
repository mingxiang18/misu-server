<script setup>
import { ref, onMounted, provide } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { getUserInfo, getUserInfoFromToken } from '@/api/user/user'
import SideNav from '@/components/layout/SideNav.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import TabBar from '@/components/layout/TabBar.vue'

const { isMobile, isDesktop } = useBreakpoint()

const userInfo = ref(getUserInfo() || {})
provide('userInfo', userInfo)

onMounted(() => {
  getUserInfoFromToken()
      .then(response => {
        if (response && response.data) userInfo.value = response.data
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
      <main class="app-content">
        <router-view />
      </main>
    </div>

    <TabBar v-if="isMobile" />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  width: 100%;
  min-height: 100svh;
  /* Prefer dvh on supported devices for keyboard safety */
  min-height: 100dvh;
  background: var(--color-bg-base);
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
</style>
