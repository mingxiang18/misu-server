<template>
  <div class="chat-container">
    <!-- 聊天记录 -->
    <div class="messages"  ref="messagesContainer">
      <div
          v-for="(message, index) in messages"
          :key="index"
          :class="['message', message.isSelf ? 'right' : 'left']"
      >
        <!-- 机器人头像 -->
        <div class="avatar" v-if="!message.isSelf">
          <el-icon size="20px"><Service /></el-icon>
        </div>

        <div class="message-content">
          <!-- 遍历所有消息 -->
          <div v-for="(content, index) in message.content" :key="index">
            <!-- 处理 @ 用户类型的消息 -->
<!--            <span v-if="content.type === 'at'" class="at-message">@{{ content.data }}</span>-->

            <!-- 处理普通文本消息 -->
            <span v-if="content.type === 'text'" class="text-message" v-html="formatText(content.data)"></span>

            <!-- 处理图片（Base64编码）消息 -->
            <img v-if="content.type === 'localImage'" :src="'data:image/png;base64,' + content.data" alt="Image" class="image-message" />

            <!-- 处理网络图片消息 -->
            <img v-if="content.type === 'netImage'" :src="content.data" alt="Image" class="image-message" />
          </div>
        </div>

        <!-- 用户头像 -->
        <div class="avatar" v-if="message.isSelf">
          <el-icon size="20px"><User /></el-icon>
        </div>
      </div>
    </div>

    <!-- 输入框 -->
    <div class="input-container">
      <div class="input-action">
        <!-- 图片上传按钮 -->
        <el-upload action="#"
                   v-model:file-list="imageList"
                   ref="imageUpload"
                   list-type="picture-card"
                   :auto-upload="false"
                   :on-exceed="handleImageExceed"
                   :limit="1">
          <el-icon size="25px"><Picture /></el-icon>

          <template #file="{ file }">
            <div>
              <img class="el-upload-list__item-thumbnail" :src="file.url" alt="" />
              <span class="el-upload-list__item-actions">
          <span class="el-upload-list__item-delete"
              @click="handleImageRemove(file)"
          >
            <el-icon><Delete /></el-icon>
          </span>
        </span>
            </div>
          </template>
        </el-upload>
      </div>

      <el-input
          v-model="newMessage"
          :autosize="{ minRows: 1 }"
          type="textarea"
          placeholder="请输入消息"
          @keyup.enter="sendSocketMessage"
      ></el-input>
    </div>
  </div>
</template>

<script setup>
import {onMounted, onUnmounted, ref} from "vue";
import {Service, User, Delete, Picture} from "@element-plus/icons-vue";
import {getBotAccessToken, getServerWebSocketUrl} from "@/api/bot/bot";
import {ElMessage} from "element-plus";

const imageUpload = ref([])
const imageList = ref([])

const messages = ref([
  { content: [{data: "这里是冥想bb哟，有什么可以帮你的吗？", type: "text"}], isSelf: false },
]);

const messagesContainer = ref();
const botAccessToken = ref(null);
const newMessage = ref("");

// WebSocket 实例
let socket = null;

// 执行图片移除
const handleImageRemove = (file) => {
  imageUpload.value.clearFiles();
}

// 执行图片选择
const handleImageExceed = (files) => {
  imageUpload.value.clearFiles();
  const file = files[0];

  if (file.type !== 'image/jpeg' || file.type !== 'image/png') {
    ElMessage.error('图片格式不正确! 支持格式: JPEG, PNG')
    return;
  } else if (file.size / 1024 / 1024 > 20) {
    ElMessage.error('图片大小不能超过20MB!')
    return;
  }

  imageUpload.value.handleStart(file)
}

const scrollToBottom = () => {
  messagesContainer.value.scrollTo({ top: messagesContainer.value.scrollHeight, behavior: 'smooth' });
}

//发送socket消息
const sendSocketMessage = () => {
  //如果输入框没有消息则不发送
  if (!newMessage.value || !newMessage.value.trim()) {
    return
  }

  const message = {
    messageId: crypto.randomUUID(),
    messageContentList: [],
  };
  //添加文字消息
  message.messageContentList.push(
      {
        type: "text",
        data: newMessage.value,
      },
  )
  //如果图片消息不为空，添加图片消息
  if (imageList.value.length > 0) {
    // 创建 FileReader 实例
    const reader = new FileReader();

    // 当文件读取完成后，转换成 Base64 编码
    reader.onloadend = () => {
      // 将读取到的 Base64 数据存储到 message.messageContentList 中
      message.messageContentList.push({
        type: 'localImage',
        data: reader.result.split(',')[1], // 去掉 "data:image/png;base64," 前缀，只保留 Base64 字符串
      });
      sendMessage(message);
    };

    // 读取文件并转换为 Base64
    reader.readAsDataURL(imageList.value[0].raw);
  }else {
    sendMessage(message);
  }
}

//发送消息
const sendMessage = (message) => {
  // 添加到聊天列表
  const sendMessage = {
    content: message.messageContentList,
    isSelf: true
  };
  messages.value.push(sendMessage);
  setTimeout(() => scrollToBottom(), 100);

  if (!!socket) {
    console.log("发送消息", message)
    // 向socket发送消息
    socket.send(JSON.stringify(message));
  } else {
    closeSocket();
    initSocket();
    const message = {
      content: [{data: "冥想bb已离线，正在重连中", type: "text"}],
      isSelf: false
    };
    messages.value.push(message);

    setTimeout(() => scrollToBottom(), 100);
  }

  //清空消息内容
  newMessage.value = null;
  imageList.value = [];
}

//初始化socket连接
const initSocket = () => {
  getServerWebSocketUrl().then((response) => {
    //获取服务端socket连接地址
    const socketUrl = response.data;
    //连接socket
    socket = new WebSocket(socketUrl); // 替换为实际 WebSocket 服务器地址

    // 监听 WebSocket 打开连接
    socket.onopen = () => {
      console.log("bot的WebSocket 连接已打开");
      //获取bot连接用的token
      getBotAccessToken().then((response) => {
        botAccessToken.value = response.data;
        //发送认证消息
        socket.send(JSON.stringify({ type: "auth", token: botAccessToken.value }));
      });
    };

    // 监听 WebSocket 消息
    socket.onmessage = handleIncomingMessage;

    // 监听 WebSocket 错误
    socket.onerror = (error) => {
      console.error("bot的WebSocket 错误:", error);
    };

    // 监听 WebSocket 关闭连接
    socket.onclose = () => {
      console.log("bot的WebSocket 连接已关闭");
      closeSocket();
    };
  });
}

//处理socket收到的消息
const handleIncomingMessage = (event) => {
  console.log("bot的WebSocket收到消息:", event.data)
  const message = {
    content: JSON.parse(event.data).messageList,
    isSelf: false
  };
  messages.value.push(message);

  setTimeout(() => scrollToBottom(), 100);
}

// 将消息中的换行符 \n 替换为 <br>
const formatText = (text) => {
  return text.replace(/\n/g, '<br>');
}

//关闭socket连接
const closeSocket = () => {
  if (!!socket) {
    socket.close();
  }
  socket = null;
}

onMounted(() => {
  initSocket();
})

onUnmounted(() => {
  closeSocket();
})

</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  margin: 0 auto;
  border: 1px solid #ddd;
  border-radius: 10px;
  padding: 10px;
}

.messages {
  flex-grow: 1;
  overflow-y: auto;
}

.message {
  display: flex;
  align-items: flex-start;
  margin: 10px 0;
}

.message.left {
  justify-content: flex-start;
}

.message.right {
  justify-content: flex-end;
}

.avatar {
  margin-right: 10px;
  margin-left: 10px;
  margin-top: 10px;
}

.message-content {
  max-width: 80%;
  padding: 10px;
  border-radius: 10px;
  background-color: #f1f1f1;
  word-break: break-all;
}

.message.right .message-content {
  background-color: #3b8bfe;
  color: white;
}

.at-message {
  font-weight: bold;
  color: #007bff;
}

.image-message {
  max-width: 100%;
  height: auto;
  display: block;
  margin-top: 5px;
}

.input-container {
  display: flex;
  padding-top: 10px;
  padding-left: 10px;
  padding-right: 10px;
  flex-wrap: wrap; /* 自动换行 */
}

.input-action {
  display: flex;
  justify-content: flex-end;
  width: 100%;
  padding-top: 5px
}

input {
  flex-grow: 1;
  padding: 10px;
  border-radius: 5px;
  border: 1px solid #ddd;
}

:deep(.el-upload) {
  width: 30px;
  height: 30px;
}
:deep(.el-upload-list--picture-card) {
  --el-upload-list-picture-card-size: 40px;
}
</style>