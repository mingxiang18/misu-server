<script setup>
import NetworkBanner from '@/components/layout/NetworkBanner.vue'
</script>

<template>
  <main>
    <NetworkBanner />
    <div class="main-view">
      <router-view></router-view>
    </div>
  </main>
</template>

<style scoped>
.main-view {
  max-height: 100svh; /* 使容器高度为视口高度 */
  max-width: 100vw;  /* 使容器宽度为视口宽度 */
  position: relative;
  z-index: 1;
}
</style>
