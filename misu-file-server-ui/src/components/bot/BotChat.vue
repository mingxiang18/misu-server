<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { Service, Picture, Close, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getBotAccessToken, getServerWebSocketUrl } from '@/api/bot/bot'

/* ------------------ State ------------------ */
const messages = ref([
  { content: [{ data: '这里是冥想bb哟，有什么可以帮你的吗？', type: 'text' }], isSelf: false }
])

const messagesContainer = ref(null)
const newMessage = ref('')

const imageUpload = ref(null)
const imageList = ref([])

const botAccessToken = ref(null)
let socket = null

const suggestions = [
  '推荐一段冥想',
  '怎样放松心情',
  '你能做什么'
]

const canSend = computed(() => {
  return (newMessage.value && newMessage.value.trim().length > 0) || imageList.value.length > 0
})

/* ------------------ Helpers ------------------ */
const formatText = (text) => (text || '').replace(/\n/g, '<br>')

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTo({
        top: messagesContainer.value.scrollHeight,
        behavior: 'smooth'
      })
    }
  })
}

/* ------------------ Image upload ------------------ */
const handleImageRemove = () => {
  imageList.value = []
  if (imageUpload.value && imageUpload.value.clearFiles) {
    imageUpload.value.clearFiles()
  }
}

const handleImageExceed = (files) => {
  if (imageUpload.value && imageUpload.value.clearFiles) {
    imageUpload.value.clearFiles()
  }
  const file = files[0]

  if (!/^image\/(jpeg|png)$/.test(file.type)) {
    ElMessage.error('图片格式不正确！仅支持 JPEG / PNG')
    return
  }
  if (file.size / 1024 / 1024 > 20) {
    ElMessage.error('图片大小不能超过 20MB')
    return
  }

  imageUpload.value.handleStart(file)
}

/* ------------------ Send ------------------ */
const sendQuick = (text) => {
  newMessage.value = text
  sendSocketMessage()
}

const sendSocketMessage = () => {
  if (!canSend.value) return

  const message = {
    messageId: crypto.randomUUID(),
    messageContentList: []
  }

  if (newMessage.value && newMessage.value.trim()) {
    message.messageContentList.push({ type: 'text', data: newMessage.value })
  }

  if (imageList.value.length > 0) {
    const reader = new FileReader()
    reader.onloadend = () => {
      message.messageContentList.push({
        type: 'localImage',
        data: reader.result.split(',')[1]
      })
      sendMessage(message)
    }
    reader.readAsDataURL(imageList.value[0].raw)
  } else {
    sendMessage(message)
  }
}

const sendMessage = (message) => {
  messages.value.push({
    content: message.messageContentList,
    isSelf: true
  })
  scrollToBottom()

  if (socket) {
    socket.send(JSON.stringify(message))
  } else {
    closeSocket()
    initSocket()
    messages.value.push({
      content: [{ data: '冥想bb已离线，正在重连中', type: 'text' }],
      isSelf: false
    })
    scrollToBottom()
  }

  newMessage.value = ''
  imageList.value = []
}

/* ------------------ Socket ------------------ */
const initSocket = () => {
  getServerWebSocketUrl().then((response) => {
    const socketUrl = response.data
    socket = new WebSocket(socketUrl)

    socket.onopen = () => {
      console.log('bot WebSocket connected')
      getBotAccessToken().then((response) => {
        botAccessToken.value = response.data
        socket.send(JSON.stringify({ type: 'auth', token: botAccessToken.value }))
      })
    }
    socket.onmessage = handleIncomingMessage
    socket.onerror = (err) => console.error('bot WebSocket error:', err)
    socket.onclose = () => {
      console.log('bot WebSocket closed')
      closeSocket()
    }
  })
}

const handleIncomingMessage = (event) => {
  const message = {
    content: JSON.parse(event.data).messageList,
    isSelf: false
  }
  messages.value.push(message)
  scrollToBottom()
}

const closeSocket = () => {
  if (socket) socket.close()
  socket = null
}

onMounted(() => {
  initSocket()
  scrollToBottom()
})

onUnmounted(() => {
  closeSocket()
})
</script>

<template>
  <div class="bot-chat">
    <!-- Messages -->
    <div ref="messagesContainer" class="bot-messages">
      <div
          v-for="(message, index) in messages"
          :key="index"
          class="bot-row"
          :class="message.isSelf ? 'self' : 'bot'">
        <div v-if="!message.isSelf" class="bot-avatar" aria-hidden="true">
          <Service />
        </div>

        <div class="bot-bubble" :class="{ self: message.isSelf }">
          <template v-for="(content, ci) in message.content" :key="ci">
            <span
                v-if="content.type === 'text'"
                class="bot-text"
                v-html="formatText(content.data)"></span>
            <img
                v-else-if="content.type === 'localImage'"
                class="bot-image"
                :src="'data:image/png;base64,' + content.data"
                alt=""/>
            <img
                v-else-if="content.type === 'netImage'"
                class="bot-image"
                :src="content.data"
                alt=""/>
          </template>
        </div>
      </div>

      <!-- Suggestions: only show on first turn (when only the greeting is present) -->
      <div v-if="messages.length === 1" class="bot-suggestions">
        <button
            v-for="s in suggestions"
            :key="s"
            class="bot-chip"
            @click="sendQuick(s)">
          {{ s }}
        </button>
      </div>
    </div>

    <!-- Input -->
    <div class="bot-input-wrap">
      <!-- Image preview chip -->
      <div v-if="imageList.length > 0" class="bot-image-preview">
        <div class="bot-image-thumb">
          <img :src="imageList[0].url" alt=""/>
          <button class="bot-image-remove" type="button" @click="handleImageRemove" aria-label="移除图片">
            <Close/>
          </button>
        </div>
      </div>

      <div class="bot-input-row">
        <el-upload
            ref="imageUpload"
            v-model:file-list="imageList"
            class="bot-upload"
            action="#"
            :auto-upload="false"
            :show-file-list="false"
            :on-exceed="handleImageExceed"
            :limit="1"
            accept="image/png,image/jpeg">
          <button class="bot-input-btn" type="button" aria-label="上传图片">
            <Picture/>
          </button>
        </el-upload>

        <el-input
            v-model="newMessage"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            placeholder="请输入消息"
            class="bot-input-text"
            resize="none"
            @keydown.enter.exact.prevent="sendSocketMessage"/>

        <button
            class="bot-input-send"
            type="button"
            :disabled="!canSend"
            aria-label="发送"
            @click="sendSocketMessage">
          <Promotion/>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bot-chat {
  display: flex;
  flex-direction: column;
  width: 100%;
  /* Fill the flex parent (.app-content) instead of relying on height:100%,
     which doesn't propagate cleanly through .app-content's overflow-y:auto. */
  flex: 1 1 auto;
  min-height: 0;
  background: var(--color-bg-base);
}

/* ---------- Messages ---------- */
.bot-messages {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: var(--space-4) var(--space-4) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.bot-row {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  max-width: 100%;
}

.bot-row.self {
  justify-content: flex-end;
}

.bot-row.bot {
  justify-content: flex-start;
}

.bot-avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
  color: var(--accent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.bot-avatar :deep(svg) {
  width: 16px;
  height: 16px;
}

.bot-bubble {
  max-width: 75%;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-lg);
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  font-size: var(--font-size-base);
  line-height: var(--line-height-normal);
  word-break: break-word;
  white-space: pre-wrap;
}

.bot-bubble.self {
  background: var(--accent-soft);
  color: var(--color-text-primary);
}

.bot-text {
  white-space: pre-wrap;
}

.bot-image {
  max-width: 100%;
  height: auto;
  border-radius: var(--radius-md);
  display: block;
  margin-top: var(--space-1);
}

/* ---------- Suggestions ---------- */
.bot-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding-left: calc(32px + var(--space-2));
  margin-top: calc(-1 * var(--space-2));
}

.bot-chip {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-pill);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  transition:
      background var(--duration-fast) var(--ease-standard),
      color var(--duration-fast) var(--ease-standard),
      border-color var(--duration-fast) var(--ease-standard);
}

.bot-chip:hover,
.bot-chip:active {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
  border-color: var(--color-border-strong);
}

/* ---------- Input ---------- */
.bot-input-wrap {
  flex-shrink: 0;
  background: var(--color-bg-surface);
  border-top: 1px solid var(--color-border-subtle);
  padding-bottom: env(safe-area-inset-bottom);
}

.bot-image-preview {
  display: flex;
  padding: var(--space-3) var(--space-3) 0;
}

.bot-image-thumb {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border-default);
}

.bot-image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bot-image-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(31, 27, 22, 0.6);
  color: #fff;
  border-radius: var(--radius-pill);
  cursor: pointer;
}

.bot-image-remove :deep(svg) {
  width: 12px;
  height: 12px;
}

.bot-input-row {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
}

.bot-input-btn {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  color: var(--color-text-secondary);
  background: transparent;
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard);
}

.bot-input-btn:hover,
.bot-input-btn:active {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.bot-input-btn :deep(svg) {
  width: 20px;
  height: 20px;
}

.bot-input-text {
  flex: 1 1 auto;
  min-width: 0;
}

.bot-input-text :deep(.el-textarea__inner) {
  border-radius: var(--radius-lg);
  background: var(--color-bg-muted);
  border-color: transparent;
  font-size: var(--font-size-md);
  padding: 10px var(--space-3);
  box-shadow: none;
  resize: none;
  line-height: var(--line-height-normal);
}

.bot-input-text :deep(.el-textarea__inner:focus) {
  background: var(--color-bg-surface);
  border-color: var(--color-border-strong);
}

.bot-input-send {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  background: var(--accent);
  color: var(--color-text-on-accent);
  transition:
      background var(--duration-fast) var(--ease-standard),
      opacity var(--duration-fast) var(--ease-standard);
}

.bot-input-send:disabled {
  background: var(--color-border-default);
  color: var(--color-text-on-accent);
  cursor: not-allowed;
  opacity: 0.7;
}

.bot-input-send:not(:disabled):hover,
.bot-input-send:not(:disabled):active {
  background: var(--accent-strong);
}

.bot-input-send :deep(svg) {
  width: 18px;
  height: 18px;
}

/* Hide el-upload's default trigger styling so our button is the only visible UI */
:deep(.bot-upload .el-upload) {
  width: auto;
  height: auto;
  border: none;
  background: transparent;
}
</style>
