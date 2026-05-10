<script setup>
import { Setting } from '@element-plus/icons-vue'
</script>
<template>
  <div class="toolbar">
    <el-dropdown>
      <el-icon class="toolbar-icon">
        <setting />
      </el-icon>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item>个人信息</el-dropdown-item>
          <el-dropdown-item @click="logOutAccount()">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <span class="toolbar-username">{{ user.userName }}</span>
  </div>
</template>

<script>
import { getUserInfo } from '@/api/user/user'
import { logOut } from '@/api/auth/auth'

export default {
  data() {
    return {
      user: {
        userName: '未登录',
      },
    }
  },
  created() {
    this.getUser()
  },
  methods: {
    getUser() {
      this.user.userName = getUserInfo().userName
    },
    logOutAccount() {
      logOut()
      this.$router.push('/login')
    },
  },
}
</script>

<style scoped>
.toolbar {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 100%;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.toolbar-icon {
  font-size: var(--font-size-lg);
  color: var(--color-text-secondary);
}

.toolbar-username {
  max-width: 8em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
