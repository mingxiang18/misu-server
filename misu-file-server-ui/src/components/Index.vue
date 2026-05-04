<template>
  <el-container class="layout-container-demo">
    <el-menu
        v-if="!isSmallScreen"
        class="el-menu-vertical"
        :collapse="isCollapse">
      <el-sub-menu>
        <template #title>
          <el-icon><icon-menu /></el-icon>
          <span>文件服务器</span>
        </template>
        <el-menu-item v-for="fileServerMenu in fileServerMenuInfoList" @click="navigateTo(fileServerMenu.toUrl)">
          {{ fileServerMenu.menuName }}
        </el-menu-item>
      </el-sub-menu>
      <el-menu-item @click="navigateTo('/bot')">
        <el-icon><ChatDotRound /></el-icon>
        <span>机器人</span>
      </el-menu-item>
      <el-menu-item @click="navigateTo('/languageLearn')">
        <el-icon><Memo /></el-icon>
        <span>语言学习</span>
      </el-menu-item>
      <el-menu-item v-if="isAdmin" @click="navigateTo('/userManagement')">
        <el-icon><setting /></el-icon>
        <span>用户管理</span>
      </el-menu-item>
    </el-menu>

    <el-container>
      <el-header class="index-header" v-if="!isSmallScreen">
        <UserToolBar></UserToolBar>
      </el-header>

      <el-main>
        <router-view></router-view>
      </el-main>

      <el-footer v-if="isSmallScreen" class="index-footer">
        <div class="index-footer-item">
          <el-dropdown>
            <el-icon :size="menuIconSize" :color="menuIconColor"><Folder /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="fileServerMenu in fileServerMenuInfoList" @click="navigateTo(fileServerMenu.toUrl)">
                  {{ fileServerMenu.menuName }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="index-footer-item">
          <el-icon :size="menuIconSize" :color="menuIconColor" @click="navigateTo('/bot')"><ChatDotRound /></el-icon>
        </div>
        <div class="index-footer-item">
          <el-icon :size="menuIconSize" :color="menuIconColor" @click="navigateTo('/languageLearn')"><Memo /></el-icon>
        </div>
        <div class="index-footer-item">
          <el-dropdown>
            <el-icon :size="menuIconSize" :color="menuIconColor"><User /></el-icon>
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
import {computed, onMounted, ref} from 'vue'
import {User, Folder, Document, Menu as IconMenu, Message, Setting, ChatDotRound, Memo} from '@element-plus/icons-vue'
import UserToolBar from "@/components/user/UserToolBar.vue";
import {logOut} from "@/api/auth/auth";
import {getHistoryVideoRoomFromCookie} from "@/api/fileServer/videoRoom";
import { useRouter } from 'vue-router';
import { getUserInfo, getUserInfoFromToken } from '@/api/user/user';

const router = useRouter()

const userInfo = ref(getUserInfo());
const isAdmin = computed(() => (userInfo.value.authorities || []).includes('ADMIN'));

const isCollapse = ref(false);

const isSmallScreen = ref(false);

const menuIconColor = ref('#53565a');
const menuIconSize = ref(30);

const fileServerMenuInfoList = [
  {
    "menuName": "公共目录",
    "toUrl": "/fileServer/publicDirectory"
  },
  {
    "menuName": "私人目录",
    "toUrl": "/fileServer/privateDirectory"
  },
  {
    "menuName": "磁力下载",
    "toUrl": "/fileServer/torrentManagement"
  },
  {
    "menuName": "放映室",
    "toUrl": "/fileServer/videoRoom"
  }
]

onMounted(() => {
  getUserInfoFromToken().then(response => {
    userInfo.value = response.data;
  });

  isSmallScreen.value = window.innerWidth <= 650;

  // 监听窗口尺寸变化
  window.addEventListener('resize', () => {
    isSmallScreen.value = window.innerWidth <= 650;
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
  background-color: #daddf1;
  box-shadow: var(--el-box-shadow-light);
  color: var(--el-text-color-primary);
  height: 10%;     /* 占屏幕高度10% */
}

.layout-container-demo .el-main {
  padding: 0;
  height: 90%;     /* 占屏幕高度90% */
}

.index-header {
  text-align: right;
  font-size: 12px;
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
  height: 30px;
  width: 30px;
}

:deep(:focus-visible) {
  outline: none;
}
</style>
