<template>
  <el-container class="layout-container-demo">
    <el-menu
        v-if="!isSmallScreen"
        class="el-menu-vertical"
        :collapse="isCollapse">
      <el-sub-menu index="1">
        <template #title>
          <el-icon><message /></el-icon>
          <span>Navigator One</span>
        </template>
        <el-menu-item-group>
          <template #title>Group 1</template>
          <el-menu-item index="1-1">Option 1</el-menu-item>
          <el-menu-item index="1-2">Option 2</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="Group 2">
          <el-menu-item index="1-3">Option 3</el-menu-item>
        </el-menu-item-group>
      </el-sub-menu>
      <el-sub-menu index="2">
        <template #title>
          <el-icon><icon-menu /></el-icon>
          <span>文件服务器</span>
        </template>
        <el-menu-item index="2-1" @click="navigateTo('/fileServer/publicDirectory')">公共目录</el-menu-item>
        <el-menu-item index="2-2" @click="navigateTo('/fileServer/privateDirectory')">私人目录</el-menu-item>
      </el-sub-menu>
      <el-menu-item index="3" @click="navigateTo('/userManagement')">
        <el-icon><setting /></el-icon>
        <span>用户管理</span>
      </el-menu-item>
    </el-menu>

    <el-container>
      <el-header style="text-align: right; font-size: 12px" v-if="!isSmallScreen">
        <UserToolBar></UserToolBar>
      </el-header>

      <el-main>
        <router-view></router-view>
      </el-main>

      <el-footer v-if="isSmallScreen" class="index-footer">
        <div class="index-footer-item">
          <el-dropdown style="height: 50%;">
            <div><Folder class="index-footer-icon"/></div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="navigateTo('/fileServer/publicDirectory')">公共目录</el-dropdown-item>
                <el-dropdown-item @click="navigateTo('/fileServer/privateDirectory')">私人目录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="index-footer-item">
          <el-dropdown style="height: 50%;">
            <div><User class="index-footer-icon"/></div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item>个人信息</el-dropdown-item>
                <el-dropdown-item @click="logOutAccount()">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-footer>
    </el-container>
  </el-container>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {User, Folder, Document, Menu as IconMenu, Message, Setting} from '@element-plus/icons-vue'
import UserToolBar from "@/components/user/UserToolBar.vue";
import {logOut} from "@/api/auth/auth";
import { useRouter } from 'vue-router'

const router = useRouter()

const isCollapse = ref(false)

const isSmallScreen = ref(false)

onMounted(() => {
  isSmallScreen.value = window.innerWidth <= 482;

  // 监听窗口尺寸变化
  window.addEventListener('resize', () => {
    isSmallScreen.value = window.innerWidth <= 482;
  });
});

const logOutAccount = () => {
  logOut();
  router.push('/login');
}

const navigateTo = (route) => {
  router.push(route);
}
</script>

<style scoped>

.layout-container-demo .el-aside {
  color: var(--el-text-color-primary);
  background: var(--el-color-primary-light-8);
}
.el-menu-vertical:not(.el-menu--collapse) {
  min-width: 170px;
}

.layout-container-demo .el-container {
  padding: 0;
  height: 100svh;     /* 占屏幕高度 */
}

.layout-container-demo .el-header {
  position: relative;
  background-color: var(--el-color-primary-light-7);
  color: var(--el-text-color-primary);
  height: 10%;     /* 占屏幕高度10% */
}

.layout-container-demo .el-main {
  padding: 0;
  height: 90%;     /* 占屏幕高度90% */
}

.index-footer {
  position: relative;
  background-color: white;
  box-shadow: var(--el-box-shadow-dark);
  height: 10%;     /* 占屏幕高度10% */
  display: flex;
  justify-content: space-between; /* 平均分配每个项目的空间 */
  align-items: center; /* 垂直居中对齐 */
}

.index-footer-item {
  height: 100%;
  display: flex; /* 使用 flex 布局 */
  flex-direction: column; /* 让图标和文字垂直排列 */
  justify-content: center; /* 使内容垂直居中 */
  align-items: center; /* 使内容水平居中 */
  flex: 1; /* 让每个 item 占据相等空间 */
  cursor: pointer; /* 鼠标悬停时变为手型 */
}

.index-footer-icon {
  height: 100%;
}

:deep(:focus-visible) {
  outline: none;
}
</style>
