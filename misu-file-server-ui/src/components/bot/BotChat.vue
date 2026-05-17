<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { Service, Picture, Close, Promotion, Document, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getBotAccessToken, getServerWebSocketUrl } from '@/api/bot/bot'
import logger from '@/utils/logger'

/* ------------------ State ------------------ */
const messages = ref([
  { content: [{ data: '这里是冥想bb哟，有什么可以帮你的吗？', type: 'text' }], isSelf: false }
])

const messagesContainer = ref(null)
const newMessage = ref('')

const imageUpload = ref(null)
const imageList = ref([])

const fileUpload = ref(null)
const fileList = ref([])

const botAccessToken = ref(null)
let socket = null

// 单个附件上限 20MB
const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

// 聊天记录本地留存：24 小时内重进页面恢复历史，超时则丢弃
const CHAT_HISTORY_KEY = 'bot-chat-history'
const CHAT_HISTORY_TTL = 24 * 60 * 60 * 1000
// 最多留存的消息条数，避免 localStorage 无限增长
const MAX_PERSISTED_MESSAGES = 200

const suggestions = [
  '推荐一段冥想',
  '怎样放松心情',
  '你能做什么'
]

const canSend = computed(() => {
  return (newMessage.value && newMessage.value.trim().length > 0)
      || imageList.value.length > 0
      || fileList.value.length > 0
})

/* ------------------ Helpers ------------------ */
const formatText = (text) => (text || '').replace(/\n/g, '<br>')

// 把文件读成 base64（去掉 data:*;base64, 前缀）
const readBase64 = (file) => new Promise((resolve, reject) => {
  const reader = new FileReader()
  reader.onloadend = () => resolve(reader.result.split(',')[1])
  reader.onerror = reject
  reader.readAsDataURL(file)
})

// 人类可读的文件大小
const formatSize = (bytes) => {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

// 文件附件的下载地址：netFile 直接用 URL，localFile 拼成 base64 data URL
const fileHref = (content) => {
  if (content.type === 'netFile') return content.data
  return 'data:' + (content.mimeType || 'application/octet-stream') + ';base64,' + content.data
}

const scrollToBottom = () => {
  // nextTick 等 Vue 完成 DOM patch；rAF 再让浏览器跑一帧 layout，
  // scrollHeight 才是新加消息后的真实值。
  nextTick(() => {
    requestAnimationFrame(() => {
      const el = messagesContainer.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    })
  })
}

// 任意路径新增/重置消息（首次加载、自己发、bot 回、离线占位）都通过
// length 变化统一触发滚到底，避免漏点。
watch(() => messages.value.length, () => {
  scrollToBottom()
})

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

/* ------------------ File upload ------------------ */
const handleFileRemove = () => {
  fileList.value = []
  if (fileUpload.value && fileUpload.value.clearFiles) {
    fileUpload.value.clearFiles()
  }
}

const handleFileExceed = (files) => {
  if (fileUpload.value && fileUpload.value.clearFiles) {
    fileUpload.value.clearFiles()
  }
  const file = files[0]
  if (file.size > MAX_ATTACHMENT_BYTES) {
    ElMessage.error('文件大小不能超过 20MB')
    return
  }
  fileUpload.value.handleStart(file)
}

/* ------------------ Send ------------------ */
const sendQuick = (text) => {
  newMessage.value = text
  sendSocketMessage()
}

const sendSocketMessage = async () => {
  if (!canSend.value) return

  const message = {
    messageId: crypto.randomUUID(),
    messageContentList: []
  }

  if (newMessage.value && newMessage.value.trim()) {
    message.messageContentList.push({ type: 'text', data: newMessage.value })
  }

  try {
    if (imageList.value.length > 0) {
      const raw = imageList.value[0].raw
      message.messageContentList.push({
        type: 'localImage',
        data: await readBase64(raw),
        fileName: raw.name,
        mimeType: raw.type,
        size: raw.size
      })
    }
    if (fileList.value.length > 0) {
      const raw = fileList.value[0].raw
      message.messageContentList.push({
        type: 'localFile',
        data: await readBase64(raw),
        fileName: raw.name,
        mimeType: raw.type,
        size: raw.size
      })
    }
  } catch (err) {
    logger.error('读取附件失败:', err)
    ElMessage.error('附件读取失败，请重试')
    return
  }

  sendMessage(message)
}

const sendMessage = (message) => {
  messages.value.push({
    content: message.messageContentList,
    isSelf: true
  })
  scrollToBottom()

  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message))
  } else {
    // 主动重连，告知用户
    if (!reconnectAt.value) initSocket()
    messages.value.push({
      content: [{ data: '冥想bb已离线，正在重连中', type: 'text' }],
      isSelf: false
    })
    scrollToBottom()
  }

  newMessage.value = ''
  handleImageRemove()
  handleFileRemove()
}

/* ------------------ Socket with exponential backoff ------------------ */
const MAX_RECONNECT_ATTEMPTS = 5
const RECONNECT_BASE_MS = 1000
const reconnectAttempts = ref(0)
const reconnectAt = ref(null) // 当前 setTimeout id（非 0 表示退避中）
const reconnectExhausted = ref(false)
let intentionalClose = false

const initSocket = () => {
  intentionalClose = false
  getServerWebSocketUrl().then((response) => {
    const socketUrl = response.data
    socket = new WebSocket(socketUrl)

    socket.onopen = () => {
      logger.info('bot WebSocket connected')
      reconnectAttempts.value = 0
      reconnectExhausted.value = false
      getBotAccessToken().then((response) => {
        botAccessToken.value = response.data
        socket.send(JSON.stringify({ type: 'auth', token: botAccessToken.value }))
      })
    }
    socket.onmessage = handleIncomingMessage
    socket.onerror = (err) => logger.error('bot WebSocket error:', err)
    socket.onclose = () => {
      logger.info('bot WebSocket closed')
      socket = null
      if (!intentionalClose) scheduleReconnect()
    }
  }).catch((err) => {
    logger.error('Failed to fetch bot WS url:', err)
    if (!intentionalClose) scheduleReconnect()
  })
}

const scheduleReconnect = () => {
  if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
    reconnectExhausted.value = true
    messages.value.push({
      content: [{ data: '多次重连失败，请点击下方"重新连接"再试一次', type: 'text' }],
      isSelf: false
    })
    scrollToBottom()
    return
  }
  // 1s, 2s, 4s, 8s, 16s
  const delay = RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts.value)
  reconnectAttempts.value += 1
  reconnectAt.value = setTimeout(() => {
    reconnectAt.value = null
    initSocket()
  }, delay)
}

const manualReconnect = () => {
  reconnectAttempts.value = 0
  reconnectExhausted.value = false
  if (reconnectAt.value) {
    clearTimeout(reconnectAt.value)
    reconnectAt.value = null
  }
  initSocket()
}

const handleIncomingMessage = (event) => {
  const payload = JSON.parse(event.data)
  const list = payload.messageList || []

  // 流式帧：同一 streamId 的 start/delta/end 续写到同一气泡
  if (payload.streamId) {
    const deltaText = list
        .filter((c) => c.type === 'text')
        .map((c) => c.data)
        .join('')

    let bubble = messages.value.find((m) => m.streamId === payload.streamId)
    if (!bubble) {
      bubble = {
        streamId: payload.streamId,
        streaming: true,
        content: [{ type: 'text', data: '' }],
        isSelf: false
      }
      messages.value.push(bubble)
    }
    const textItem = bubble.content.find((c) => c.type === 'text')
    if (textItem) {
      textItem.data += deltaText
    }
    if (payload.streamState === 'end') {
      bubble.streaming = false
    }
    scrollToBottom()
    return
  }

  // 普通一次成型消息
  messages.value.push({ content: list, isSelf: false })
  scrollToBottom()
}

const closeSocket = () => {
  intentionalClose = true
  if (reconnectAt.value) {
    clearTimeout(reconnectAt.value)
    reconnectAt.value = null
  }
  if (socket) socket.close()
  socket = null
}

/* ------------------ Chat history persistence ------------------ */
// 读取本地留存的聊天记录；超过留存时长则丢弃
const loadHistory = () => {
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_KEY)
    if (!raw) return null
    const saved = JSON.parse(raw)
    if (!saved || !saved.savedAt || !Array.isArray(saved.messages)) return null
    if (Date.now() - saved.savedAt > CHAT_HISTORY_TTL) {
      localStorage.removeItem(CHAT_HISTORY_KEY)
      return null
    }
    // 恢复时清掉流式中间态，避免残留闪烁光标
    return saved.messages.map((m) => ({ ...m, streaming: false }))
  } catch (err) {
    logger.error('读取聊天记录失败:', err)
    return null
  }
}

// 把当前聊天记录写入本地；localStorage 配额不足（多为附件 base64 过大）时
// 丢掉最旧的四分之一再重试，保证文本记录尽量留存
const persistHistory = () => {
  let list = messages.value.slice(-MAX_PERSISTED_MESSAGES)
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify({
        savedAt: Date.now(),
        messages: list
      }))
      return
    } catch (err) {
      if (list.length <= 1) {
        logger.error('聊天记录写入失败:', err)
        return
      }
      list = list.slice(Math.ceil(list.length / 4))
    }
  }
}

let persistTimer = null
// 流式 delta 会高频改动 messages，去抖后再落盘
const schedulePersist = () => {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    persistHistory()
  }, 600)
}

watch(messages, schedulePersist, { deep: true })

onMounted(() => {
  const restored = loadHistory()
  if (restored && restored.length > 0) {
    messages.value = restored
  }
  initSocket()
  scrollToBottom()
})

onUnmounted(() => {
  // 离开页面时若仍有未落盘的去抖任务，立即补写一次
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
    persistHistory()
  }
  closeSocket()
})
</script>

<template>
  <div class="bot-chat">
    <!-- Messages -->
    <div ref="messagesContainer" class="bot-messages">
      <!-- Inner uses margin-top:auto so content sticks to the bottom when
           there are few messages, but still scrolls correctly once it
           overflows the container. -->
      <div class="bot-messages-inner">
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
                  :class="{ 'bot-text-streaming': message.streaming }"
                  v-html="formatText(content.data)"></span>
              <img
                  v-else-if="content.type === 'localImage'"
                  class="bot-image"
                  :src="'data:' + (content.mimeType || 'image/png') + ';base64,' + content.data"
                  alt=""/>
              <img
                  v-else-if="content.type === 'netImage'"
                  class="bot-image"
                  :src="content.data"
                  alt=""/>
              <a
                  v-else-if="content.type === 'localFile' || content.type === 'netFile'"
                  class="bot-file"
                  :href="fileHref(content)"
                  :download="content.fileName || 'file'"
                  target="_blank"
                  rel="noopener">
                <span class="bot-file-icon" aria-hidden="true"><Document/></span>
                <span class="bot-file-meta">
                  <span class="bot-file-name">{{ content.fileName || '文件' }}</span>
                  <span v-if="content.size" class="bot-file-size">{{ formatSize(content.size) }}</span>
                </span>
                <span class="bot-file-dl" aria-hidden="true"><Download/></span>
              </a>
            </template>
          </div>
        </div>

        <!-- Suggestions: only on first turn (when only greeting is present) -->
        <div v-if="messages.length === 1" class="bot-suggestions">
          <button
              v-for="s in suggestions"
              :key="s"
              class="bot-chip"
              @click="sendQuick(s)">
            {{ s }}
          </button>
        </div>

        <!-- 重连耗尽时的手动重试 -->
        <div v-if="reconnectExhausted" class="bot-reconnect">
          <button class="bot-chip bot-chip-warn" type="button" @click="manualReconnect">重新连接</button>
        </div>
      </div>
    </div>

    <!-- Input -->
    <div class="bot-input-wrap">
      <!-- Attachment preview -->
      <div v-if="imageList.length > 0 || fileList.length > 0" class="bot-attach-preview">
        <div v-if="imageList.length > 0" class="bot-image-thumb">
          <img :src="imageList[0].url" alt=""/>
          <button class="bot-attach-remove" type="button" @click="handleImageRemove" aria-label="移除图片">
            <Close/>
          </button>
        </div>
        <div v-if="fileList.length > 0" class="bot-file-chip">
          <span class="bot-file-chip-icon" aria-hidden="true"><Document/></span>
          <span class="bot-file-chip-name">{{ fileList[0].name }}</span>
          <button class="bot-attach-remove static" type="button" @click="handleFileRemove" aria-label="移除文件">
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

        <el-upload
            ref="fileUpload"
            v-model:file-list="fileList"
            class="bot-upload"
            action="#"
            :auto-upload="false"
            :show-file-list="false"
            :on-exceed="handleFileExceed"
            :limit="1">
          <button class="bot-input-btn" type="button" aria-label="上传文件">
            <Document/>
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
  /* flex-basis: 0 instead of auto — so the chat container's intrinsic
     content height (which can be large with many messages) does NOT
     contribute to the flex parent's scroll height. The chat fills
     exactly the available .app-content slot, and overflow stays
     internal in .bot-messages. Without basis:0 the page would
     scroll instead of the messages list when the conversation grew. */
  flex: 1 1 0;
  min-height: 0;
  background: var(--color-bg-base);
}

/* ---------- Messages ---------- */
.bot-messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* margin-top:auto pushes the conversation to the bottom of the scroll
   container when content is shorter than the viewport. When content is
   taller, the auto margin collapses to 0 and the container scrolls
   normally — this is the standard pattern for chat UIs and avoids the
   browser bug with `justify-content: flex-end` + overflow that prevents
   scrolling up to read older messages. */
.bot-messages-inner {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-4) var(--space-3);
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

/* 流式回复进行中：文末闪烁光标 */
.bot-text-streaming::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent);
  animation: bot-caret-blink 1s steps(1) infinite;
}

@keyframes bot-caret-blink {
  50% { opacity: 0; }
}

.bot-image {
  max-width: 100%;
  height: auto;
  border-radius: var(--radius-md);
  display: block;
  margin-top: var(--space-1);
}

/* ---------- File card (in bubble) ---------- */
.bot-file {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-default);
  color: var(--color-text-primary);
  text-decoration: none;
  max-width: 240px;
}

.bot-file:hover,
.bot-file:active {
  background: var(--color-bg-hover);
  border-color: var(--color-border-strong);
}

.bot-file-icon {
  flex-shrink: 0;
  display: inline-flex;
  color: var(--accent);
}

.bot-file-icon :deep(svg) {
  width: 22px;
  height: 22px;
}

.bot-file-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1 1 auto;
}

.bot-file-name {
  font-size: var(--font-size-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bot-file-size {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.bot-file-dl {
  flex-shrink: 0;
  display: inline-flex;
  color: var(--color-text-secondary);
}

.bot-file-dl :deep(svg) {
  width: 16px;
  height: 16px;
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

.bot-chip-warn {
  color: var(--color-danger);
  border-color: var(--color-danger);
}

.bot-reconnect {
  display: flex;
  justify-content: center;
  margin-top: var(--space-3);
}

/* ---------- Input ---------- */
.bot-input-wrap {
  flex-shrink: 0;
  background: var(--color-bg-surface);
  border-top: 1px solid var(--color-border-subtle);
  padding-bottom: env(safe-area-inset-bottom);
}

.bot-attach-preview {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
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

.bot-attach-remove {
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
  flex-shrink: 0;
}

.bot-attach-remove.static {
  position: static;
}

.bot-attach-remove :deep(svg) {
  width: 12px;
  height: 12px;
}

.bot-file-chip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 220px;
  height: 36px;
  padding: 0 var(--space-2) 0 var(--space-3);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-default);
  background: var(--color-bg-muted);
}

.bot-file-chip-icon {
  flex-shrink: 0;
  display: inline-flex;
  color: var(--accent);
}

.bot-file-chip-icon :deep(svg) {
  width: 16px;
  height: 16px;
}

.bot-file-chip-name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
