<template>
  <div class="login-main">
    <div class="login-container">
      <el-card class="login-card" shadow="always">
        <div class="login-card-row">
          <!-- 账号输入框 -->
          <span>账号：</span>
          <el-input placeholder="请输入内容" v-model="userName" clearable id="account"></el-input>
        </div>

        <!-- 密码输入框 -->
        <div class="login-card-row">
          <span>密码：</span>
          <el-input placeholder="请输入密码" v-model="password"  id="password" show-password></el-input>
        </div>
        <div class="login-card-row">
          <!-- 登录按钮 -->
          <el-button id="btn" type="primary" @click="handleLogin()">登录</el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script>
import {login} from '@/api/auth/auth'
import {setToken} from '@/api/auth/token'

/* 导出组件,并为组件定义数据,函数,生命周期函数 */
export default {
  data() {
    return {
      userName: '',
      password: '',
      captchaCode: '123'
    }
  },
  methods: {
    handleLogin() {
      if (this.userName.length === 0) {
        this.$message({
          message: '账号不能为空!',
          type: 'warning'
        });
        return;
      }
      if (this.password.length === 0) {
        this.$message({
          message: '密码不能为空!',
          type: 'warning'
        });
        return;
      }
      //后端交互
      login(this.userName, this.password, this.captchaCode).then(response => {
        //设置token
        setToken(response.data.token)
        //跳转首页
        this.$router.push("/");
      });

    }
  }
}
</script>

<style scoped>
.el-input {
  width: 200px;
}

.login-main {
  height: 100svh; /* 使容器高度为视口高度 */
  width: 100vw;  /* 使容器宽度为视口宽度 */
}

.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  width: 100%;
  background-color: #ced5e8; /* 可自定义背景颜色 */
  margin: 0; /* 确保没有外边距 */
}

.login-card {
  display: flex;
  width: 300px; /* 自定义登录卡片宽度 */
  align-items: center;
  padding: 10px;
}

.login-card-row {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 250px;
  padding-top: 10px;
}
</style>