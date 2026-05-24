<script setup>
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { Picture, Close, Promotion, Document, Download, ArrowLeft, More, Camera, RefreshRight, Folder, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ChatAvatar from './ChatAvatar.vue'
import ChatUsageBar from './ChatUsageBar.vue'
import { botProfile, setBotAvatar } from './botProfile.js'
import { getAccessToken, getServerWebSocketUrl, pageMessages, uploadChatFile, uploadChatFileChunked, chatFileUrl } from '@/api/chat/chat'
import { readMsgs, writeMsgs } from './chatCache.js'
import logger from '@/utils/logger'

const props = defineProps({
  conversation: { type: Object, default: null },
  currentUser: { type: Object, required: true },
  members: { type: Array, default: () => [] },
  isMobile: { type: Boolean, default: false },
  // 仅 ADMIN：是否允许编辑 bb 头像
  canEditBotAvatar: { type: Boolean, default: false }
})
const emit = defineEmits(['back', 'open-members', 'open-files', 'incoming'])

/* ------------------ State ------------------ */
const messages = ref([])
const messagesContainer = ref(null)
const newMessage = ref('')
const imageList = ref([])
const imageUpload = ref(null)
const fileList = ref([])
const fileUpload = ref(null)
const sending = ref(false)        // 发送/上传在途：防重复点击
const uploadProgress = ref(0)     // 附件上传进度 0-100
const showMention = ref(false)
const mentionQuery = ref('')
const pendingAtIds = ref([])
const loadingMore = ref(false)
const noMore = ref(false)

const cacheScope = () => (props.currentUser && props.currentUser.userName) || 'me'

const isGroup = computed(() => props.conversation && props.conversation.type === 'GROUP')
const botName = computed(() => botProfile.name)
const subtitle = computed(() => {
  if (!props.conversation) return ''
  if (isGroup.value) return `${props.conversation.memberCount || (props.conversation.members ? props.conversation.members.length : 0)} 名成员 · 含冥想bb`
  return connStatus.value === 'open' ? '在线 · 随时陪你聊' : (connStatus.value === 'offline' ? '连接已断开' : '连接中…')
})
// 群成员（来自服务端 listMembers，含 self 标记 + bb）；@ 列表排除自己，并按输入的 @查询 过滤
const mentionTargets = computed(() => {
  if (!isGroup.value) return []
  const q = mentionQuery.value.trim().toLowerCase()
  return (props.members || []).filter((m) => {
    if (m.self) return false
    if (!q) return true
    const name = (m.botFlag ? botName.value : (m.nickName || m.userName || '')).toLowerCase()
    return name.includes(q)
  })
})

/* ------------------ Connection state ------------------ */
const connStatus = ref('connecting') // connecting | open | offline
let socket = null
let authed = false
let authFallbackTimer = null
const AUTH_FALLBACK_MS = 2500
const pendingQueue = []
const sentMessages = new Map()
const retryCounts = new Map()
const MAX_BOT_RETRY = 3
const BOT_RETRY_DELAY_MS = 1500
const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

const canSend = computed(() => (newMessage.value && newMessage.value.trim().length > 0) || imageList.value.length > 0 || fileList.value.length > 0)

/* ------------------ Helpers ------------------ */
const escapeHtml = (s) => (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const formatText = (text) =>
    escapeHtml(text)
        .replace(/(@[^\s@]+)/g, '<span class="bot-mention">$1</span>')
        .replace(/\n/g, '<br>')

const formatSize = (bytes) => {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const fmtTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
const nowLabel = () => fmtTime(new Date().toISOString())

const readBase64 = (file) => new Promise((resolve, reject) => {
  const reader = new FileReader()
  reader.onloadend = () => resolve(reader.result.split(',')[1])
  reader.onerror = reject
  reader.readAsDataURL(file)
})

// 文件下载地址：新引用走磁盘下载接口；旧数据兼容（netFile 外链 / localFile base64）
const fileHref = (c) => {
  if (c.type === 'chatFile') return chatFileUrl(c.data)
  if (c.type === 'netFile') return c.data
  return 'data:' + (c.mimeType || 'application/octet-stream') + ';base64,' + c.data
}
// 图片地址：新引用走磁盘接口（cookie 鉴权内联显示）；旧数据兼容
const imageSrc = (c) => {
  if (c.type === 'chatImage') return chatFileUrl(c.data)
  if (c.type === 'localImage') return 'data:' + (c.mimeType || 'image/png') + ';base64,' + c.data
  return c.data
}

const scrollToBottom = () => {
  nextTick(() => requestAnimationFrame(() => {
    const el = messagesContainer.value
    if (el) el.scrollTop = el.scrollHeight
  }))
}
watch(() => messages.value.length, scrollToBottom)

// 消息变动去抖回写缓存（仅作秒显用）
let cacheTimer = null
const scheduleCache = () => {
  if (!props.conversation) return
  clearTimeout(cacheTimer)
  const convId = props.conversation.id
  cacheTimer = setTimeout(() => writeMsgs(cacheScope(), convId, messages.value), 800)
}
watch(() => messages.value.length, scheduleCache)

const senderName = (m) => {
  if (m.senderType === 'BOT') return botName.value
  return (m.sender && (m.sender.nickName || m.sender.userName)) || m.senderUserId || ''
}

/* ------------------ History ------------------ */
const mapServerMessage = (dto) => ({
  id: dto.id,
  clientMessageId: dto.clientMessageId,
  content: dto.content || [],
  senderType: dto.senderType,
  senderUserId: dto.senderUserId,
  sender: { userId: dto.senderUserId, nickName: dto.senderNickName, avatar: dto.senderAvatar },
  // self 由服务端按已登录用户判定（前端 currentUser.userId 可能尚未就绪，不可靠）
  isSelf: dto.self === true || (dto.senderType === 'USER' && props.currentUser.userId != null && String(dto.senderUserId) === String(props.currentUser.userId)),
  streamId: dto.streamId,
  streaming: false,
  time: fmtTime(dto.createTime)
})

const curConvId = () => (props.conversation ? props.conversation.id : null)

const loadHistory = async (convId) => {
  if (!convId) { messages.value = []; return }
  noMore.value = false
  // 切会话立刻切到「新会话」的缓存秒显；没有缓存就清空，绝不残留上一个会话的记录
  const cached = readMsgs(cacheScope(), convId)
  messages.value = (cached && cached.length) ? cached : []
  if (messages.value.length) scrollToBottom()
  try {
    const res = await pageMessages(convId, { size: 50 })
    // 防竞态：接口返回时若已切到别的会话，丢弃这次结果
    if (curConvId() !== convId) return
    messages.value = (res.data || []).map(mapServerMessage)
    writeMsgs(cacheScope(), convId, messages.value)
  } catch (err) {
    logger.error('加载历史消息失败:', err)
    if (curConvId() === convId && !(cached && cached.length)) messages.value = []
  }
  if (curConvId() === convId) scrollToBottom()
}

// 上滑到顶加载更早的消息（游标 beforeId = 当前最旧的服务端消息 id）
const oldestServerId = () => {
  for (const m of messages.value) {
    if (typeof m.id === 'number') return m.id
  }
  return null
}
const loadOlder = async () => {
  if (loadingMore.value || noMore.value || !props.conversation) return
  const beforeId = oldestServerId()
  if (beforeId == null) return
  loadingMore.value = true
  const el = messagesContainer.value
  const prevHeight = el ? el.scrollHeight : 0
  const prevTop = el ? el.scrollTop : 0
  try {
    const res = await pageMessages(props.conversation.id, { beforeId, size: 30 })
    const older = (res.data || []).map(mapServerMessage)
    if (older.length === 0) {
      noMore.value = true
    } else {
      messages.value = [...older, ...messages.value]
      // 保持视口位置：补回新增内容的高度
      nextTick(() => requestAnimationFrame(() => {
        const e2 = messagesContainer.value
        if (e2) e2.scrollTop = prevTop + (e2.scrollHeight - prevHeight)
      }))
    }
  } catch (err) {
    logger.error('加载更早消息失败:', err)
  } finally {
    loadingMore.value = false
  }
}
const onMessagesScroll = (e) => {
  if (e.target.scrollTop <= 48) loadOlder()
}

watch(() => props.conversation && props.conversation.id, (id) => {
  showMention.value = false
  loadHistory(id)
}, { immediate: true })

/* ------------------ Send ------------------ */
const sendMessage = async () => {
  if (sending.value) return                       // 防重复发送：在途时忽略再次点击/回车
  if (!canSend.value || !props.conversation) return

  const messageId = crypto.randomUUID()
  const content = []
  if (newMessage.value && newMessage.value.trim()) content.push({ type: 'text', data: newMessage.value })

  sending.value = true
  uploadProgress.value = 0
  try {
    try {
      // 附件先分片并发上传到磁盘，消息里只放引用（fileId），不内联 base64（避免撑大消息/DB）
      // 机器人侧：服务端转发给 bb 时会把 fileId 读成 base64 的 localImage/localFile，bb 才能读到内容
      if (imageList.value.length > 0) {
        const raw = imageList.value[0].raw
        const d = await uploadChatFileChunked(props.conversation.id, raw, 'image', p => { uploadProgress.value = p })
        content.push({ type: 'chatImage', data: d.id, fileName: d.fileName, mimeType: d.mimeType, size: d.size })
      }
      if (fileList.value.length > 0) {
        const raw = fileList.value[0].raw
        const d = await uploadChatFileChunked(props.conversation.id, raw, 'file', p => { uploadProgress.value = p })
        content.push({ type: 'chatFile', data: d.id, fileName: d.fileName, mimeType: d.mimeType, size: d.size })
      }
    } catch (err) {
      logger.error('附件上传失败:', err)
      ElMessage.error('附件上传失败，请重试')
      return
    }

    const payload = {
      messageId,
      conversationId: props.conversation.id,
      atUserIds: [...pendingAtIds.value],
      messageContentList: content
    }

    // 本地立即回显
    messages.value.push({
      id: 'local-' + messageId,
      clientMessageId: messageId,
      content,
      senderType: 'USER',
      senderUserId: props.currentUser.userId,
      isSelf: true,
      time: nowLabel()
    })
    scrollToBottom()

    sentMessages.set(messageId, payload)
    enqueueAndFlush(payload)

    newMessage.value = ''
    pendingAtIds.value = []
    handleImageRemove()
    handleFileRemove()
  } finally {
    uploadProgress.value = 0
    sending.value = false   // 无论成功/失败/异常都复位，避免按钮卡死
  }
}

const markFailed = (messageId) => {
  const b = messages.value.find((m) => m.isSelf && m.clientMessageId === messageId)
  if (b) b.failed = true
}
const markDelivered = (receiveMessageId) => {
  if (!receiveMessageId) return
  retryCounts.delete(receiveMessageId)
  const b = messages.value.find((m) => m.isSelf && m.clientMessageId === receiveMessageId)
  if (b && b.failed) b.failed = false
}
const resend = (messageId) => {
  const msg = sentMessages.get(messageId)
  if (!msg) return
  const b = messages.value.find((m) => m.isSelf && m.clientMessageId === messageId)
  if (b) b.failed = false
  retryCounts.delete(messageId)
  enqueueAndFlush(msg)
}

/* ------------------ WebSocket ------------------ */
const MAX_RECONNECT_ATTEMPTS = 5
const RECONNECT_BASE_MS = 1000
let reconnectAttempts = 0
let reconnectTimer = null
let intentionalClose = false

const clearAuthFallback = () => { if (authFallbackTimer) { clearTimeout(authFallbackTimer); authFallbackTimer = null } }
const markAuthed = () => { clearAuthFallback(); authed = true; connStatus.value = 'open'; flushQueue() }

const enqueueAndFlush = (payload) => {
  pendingQueue.push(payload)
  if (socket && socket.readyState === WebSocket.OPEN && authed) flushQueue()
  else if (!socket && !reconnectTimer) initSocket()
}
const flushQueue = () => {
  if (!socket || socket.readyState !== WebSocket.OPEN || !authed) return
  while (pendingQueue.length > 0) socket.send(JSON.stringify(pendingQueue.shift()))
}

const initSocket = () => {
  intentionalClose = false
  if (connStatus.value !== 'open') connStatus.value = 'connecting'
  getServerWebSocketUrl().then((res) => {
    socket = new WebSocket(res.data)
    socket.onopen = () => {
      reconnectAttempts = 0
      authed = false
      getAccessToken().then((r) => {
        socket.send(JSON.stringify({ type: 'auth', token: r.data }))
        clearAuthFallback()
        authFallbackTimer = setTimeout(() => {
          if (socket && socket.readyState === WebSocket.OPEN && !authed) markAuthed()
        }, AUTH_FALLBACK_MS)
      })
    }
    socket.onmessage = handleIncomingMessage
    socket.onerror = (e) => logger.error('chat WS error:', e)
    socket.onclose = () => {
      socket = null; authed = false; clearAuthFallback()
      if (!intentionalClose) { connStatus.value = 'connecting'; scheduleReconnect() }
    }
  }).catch((err) => {
    logger.error('获取 WS url 失败:', err)
    if (!intentionalClose) scheduleReconnect()
  })
}

const scheduleReconnect = () => {
  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) { connStatus.value = 'offline'; return }
  const delay = RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts)
  reconnectAttempts += 1
  reconnectTimer = setTimeout(() => { reconnectTimer = null; initSocket() }, delay)
}
const manualReconnect = () => {
  reconnectAttempts = 0
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  initSocket()
}

const handleIncomingMessage = (event) => {
  const payload = JSON.parse(event.data)

  if (payload.type) {
    if (payload.type === 'auth_ok') markAuthed()
    else if (payload.type === 'auth_error') { clearAuthFallback(); authed = false }
    else if (payload.type === 'forbidden') ElMessage.error('无权在该会话发送消息')
    else if (payload.type === 'bot_offline') {
      const id = payload.receiveMessageId
      const tried = retryCounts.get(id) || 0
      if (id && sentMessages.has(id) && tried < MAX_BOT_RETRY) {
        retryCounts.set(id, tried + 1)
        setTimeout(() => enqueueAndFlush(sentMessages.get(id)), BOT_RETRY_DELAY_MS)
      } else if (id) markFailed(id)
    }
    return
  }

  if (!authed) markAuthed()
  markDelivered(payload.receiveMessageId)

  // 通知工作区有新消息到达（用于刷新会话列表未读 / 保持当前会话已读）
  emit('incoming', payload.conversationId)

  // 只渲染当前会话的消息
  if (props.conversation && String(payload.conversationId) !== String(props.conversation.id)) return

  const list = payload.messageList || []

  // 流式帧：同一 streamId 续写同一气泡
  if (payload.streamId) {
    const deltaText = list.filter((c) => c.type === 'text').map((c) => c.data).join('')
    let bubble = messages.value.find((m) => m.streamId === payload.streamId)
    if (!bubble) {
      bubble = { id: 'stream-' + payload.streamId, streamId: payload.streamId, streaming: true, content: [{ type: 'text', data: '' }], senderType: 'BOT', isSelf: false, time: nowLabel() }
      messages.value.push(bubble)
    }
    const t = bubble.content.find((c) => c.type === 'text')
    if (t) t.data += deltaText
    if (payload.streamState === 'end') bubble.streaming = false
    scrollToBottom()
    return
  }

  // 普通一次成型消息（BOT 回复，或群里其他成员的消息）
  messages.value.push({
    id: 'in-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
    content: list,
    senderType: payload.senderType || 'BOT',
    senderUserId: payload.senderUserId,
    sender: { userId: payload.senderUserId, nickName: payload.senderNickname, avatar: payload.senderAvatar },
    isSelf: false,
    time: nowLabel()
  })
  scrollToBottom()
}

const closeSocket = () => {
  intentionalClose = true; authed = false; clearAuthFallback()
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (socket) socket.close()
  socket = null
}

onMounted(() => { initSocket() })
onUnmounted(() => { closeSocket() })

/* ------------------ Attachments ------------------ */
const handleImageRemove = () => {
  imageList.value = []
  if (imageUpload.value && imageUpload.value.clearFiles) imageUpload.value.clearFiles()
}
const handleImageExceed = (files) => {
  if (imageUpload.value && imageUpload.value.clearFiles) imageUpload.value.clearFiles()
  const file = files[0]
  if (!/^image\/(jpeg|png)$/.test(file.type)) { ElMessage.error('图片格式不正确！仅支持 JPEG / PNG'); return }
  if (file.size > MAX_ATTACHMENT_BYTES) { ElMessage.error('图片大小不能超过 20MB'); return }
  imageUpload.value.handleStart(file)
}
const handleFileRemove = () => {
  fileList.value = []
  if (fileUpload.value && fileUpload.value.clearFiles) fileUpload.value.clearFiles()
}
const handleFileExceed = (files) => {
  if (fileUpload.value && fileUpload.value.clearFiles) fileUpload.value.clearFiles()
  const file = files[0]
  if (file.size > MAX_ATTACHMENT_BYTES) { ElMessage.error('文件大小不能超过 20MB'); return }
  fileUpload.value.handleStart(file)
}

/* ------------------ bb avatar ------------------ */
const avatarInput = ref(null)
const triggerAvatarPick = () => { if (avatarInput.value) avatarInput.value.click() }
const onAvatarPick = (e) => {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = async () => {
    try {
      await setBotAvatar(reader.result)
      ElMessage.success('已更新 bb 头像（全局生效）')
    } catch (err) {
      logger.error('更新 bb 头像失败:', err)
      ElMessage.error('更新失败：只有管理员可以修改')
    }
  }
  reader.readAsDataURL(file)
  e.target.value = ''
}

/* ------------------ mention ------------------ */
// 打字触发：输入末尾出现 @ 或 @查询 时弹出选择窗（仅群聊）
watch(newMessage, (val) => {
  if (!isGroup.value) { showMention.value = false; return }
  const m = (val || '').match(/@([^\s@]*)$/)
  if (m) {
    mentionQuery.value = m[1]
    showMention.value = true
  } else {
    showMention.value = false
  }
})

const insertMention = (m) => {
  const name = m.botFlag ? botName.value : (m.nickName || m.userName)
  // 用所选成员名替换末尾的「@查询」
  newMessage.value = (newMessage.value || '').replace(/@[^\s@]*$/, '@' + name + ' ')
  if (m.userId && !pendingAtIds.value.includes(String(m.userId))) pendingAtIds.value.push(String(m.userId))
  showMention.value = false
  mentionQuery.value = ''
}
</script>

<template>
  <div v-if="conversation" class="bot-chat">
    <header class="chat-header">
      <button v-if="isMobile" class="chat-back" type="button" aria-label="返回" @click="emit('back')"><ArrowLeft /></button>
      <div
          class="chat-header-avatar"
          :class="{ editable: !isGroup && canEditBotAvatar, 'cam-always': !isGroup && canEditBotAvatar && isMobile }"
          :title="(!isGroup && canEditBotAvatar) ? '点击更换 bb 头像' : ''"
          @click="(!isGroup && canEditBotAvatar) && triggerAvatarPick()">
        <ChatAvatar :name="conversation.title" :kind="isGroup ? 'group' : 'bot'" :avatar="isGroup ? '' : botProfile.avatar" :size="36" />
        <span v-if="!isGroup && canEditBotAvatar" class="chat-avatar-cam" aria-hidden="true"><Camera /></span>
      </div>
      <input ref="avatarInput" type="file" accept="image/png,image/jpeg" hidden @change="onAvatarPick" />
      <div class="chat-header-meta">
        <div class="chat-header-title">{{ conversation.title }}</div>
        <div class="chat-header-sub">{{ subtitle }}</div>
      </div>
      <button v-if="isGroup" class="chat-header-action" type="button" aria-label="群文件" @click="emit('open-files')"><Folder /></button>
      <button v-if="isGroup" class="chat-header-action" type="button" aria-label="群成员" @click="emit('open-members')"><More /></button>
    </header>

    <ChatUsageBar v-if="!isGroup" :conversation-id="conversation.id" />

    <div v-if="connStatus === 'offline'" class="bot-conn-banner">
      <span>与冥想bb的连接已断开</span>
      <button class="bot-conn-retry" type="button" @click="manualReconnect">重新连接</button>
    </div>

    <div ref="messagesContainer" class="bot-messages" @scroll="onMessagesScroll">
      <div class="bot-messages-inner">
        <template v-for="(message, index) in messages" :key="message.id || index">
          <div class="bot-row" :class="message.isSelf ? 'self' : 'other'">
            <ChatAvatar
                v-if="!message.isSelf"
                class="bot-row-avatar"
                :name="senderName(message)"
                :kind="message.senderType === 'BOT' ? 'bot' : 'user'"
                :avatar="message.senderType === 'BOT' ? botProfile.avatar : (message.sender && message.sender.avatar)"
                :size="34" />

            <div class="bot-bubble-wrap">
              <div v-if="!message.isSelf && (isGroup || message.senderType === 'BOT')" class="bot-sender-name">
                {{ senderName(message) }}
                <span v-if="message.senderType === 'BOT'" class="bot-badge">BOT</span>
              </div>

              <div class="bot-bubble" :class="{ self: message.isSelf, bot: message.senderType === 'BOT' }">
                <template v-for="(content, ci) in message.content" :key="ci">
                  <span v-if="content.type === 'text'" class="bot-text" :class="{ 'bot-text-streaming': message.streaming }" v-html="formatText(content.data)"></span>
                  <img v-else-if="content.type === 'chatImage' || content.type === 'netImage' || content.type === 'localImage'" class="bot-image"
                       :src="imageSrc(content)" alt="" />
                  <a v-else-if="content.type === 'chatFile' || content.type === 'netFile' || content.type === 'localFile'" class="bot-file" :href="fileHref(content)" :download="content.fileName || 'file'" target="_blank" rel="noopener">
                    <span class="bot-file-icon"><Document /></span>
                    <span class="bot-file-meta">
                      <span class="bot-file-name">{{ content.fileName || '文件' }}</span>
                      <span v-if="content.size" class="bot-file-size">{{ formatSize(content.size) }}</span>
                    </span>
                    <span class="bot-file-dl"><Download /></span>
                  </a>
                </template>
              </div>
              <div class="bot-time" :class="{ self: message.isSelf }">
                {{ message.time }}
                <button v-if="message.isSelf && message.failed" class="bot-retry-btn" type="button" @click="resend(message.clientMessageId)">
                  <RefreshRight /><span>重发</span>
                </button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="bot-input-wrap">
      <div v-if="imageList.length > 0 || fileList.length > 0" class="bot-attach-preview">
        <div v-if="imageList.length > 0" class="bot-image-thumb">
          <img :src="imageList[0].url" alt="" />
          <button class="bot-attach-remove" type="button" aria-label="移除图片" @click="handleImageRemove"><Close /></button>
        </div>
        <div v-if="fileList.length > 0" class="bot-file-chip">
          <span class="bot-file-chip-icon"><Document /></span>
          <span class="bot-file-chip-name">{{ fileList[0].name }}</span>
          <button class="bot-attach-remove static" type="button" aria-label="移除文件" @click="handleFileRemove"><Close /></button>
        </div>
        <div v-if="sending" class="bot-attach-progress">
          <el-progress :percentage="uploadProgress" :stroke-width="6" :text-inside="false" />
          <span class="bot-attach-progress-label">{{ uploadProgress < 100 ? '上传中…' : '发送中…' }}</span>
        </div>
      </div>

      <transition name="mention-pop">
        <div v-if="showMention && isGroup" class="bot-mention-pop">
          <div class="bot-mention-head">选择要 @ 的成员</div>
          <button v-for="m in mentionTargets" :key="m.userId" class="bot-mention-item" type="button" @click="insertMention(m)">
            <ChatAvatar :name="m.botFlag ? botName : (m.nickName || m.userName)" :kind="m.botFlag ? 'bot' : 'user'" :avatar="m.botFlag ? botProfile.avatar : m.avatar" :size="28" />
            <span class="bot-mention-name">{{ m.botFlag ? botName : (m.nickName || m.userName) }}</span>
            <span v-if="m.botFlag" class="bot-badge">BOT</span>
          </button>
        </div>
      </transition>

      <div class="bot-input-row">
        <el-upload ref="imageUpload" v-model:file-list="imageList" class="bot-upload" action="#" :auto-upload="false" :show-file-list="false" :on-exceed="handleImageExceed" :limit="1" accept="image/png,image/jpeg">
          <button class="bot-input-btn" type="button" aria-label="上传图片"><Picture /></button>
        </el-upload>
        <el-upload ref="fileUpload" v-model:file-list="fileList" class="bot-upload" action="#" :auto-upload="false" :show-file-list="false" :on-exceed="handleFileExceed" :limit="1">
          <button class="bot-input-btn" type="button" aria-label="上传文件"><Document /></button>
        </el-upload>
        <el-input v-model="newMessage" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }"
                  :placeholder="isGroup ? '发送到群聊 · 输入 @ 呼叫成员' : '和冥想bb说点什么'" class="bot-input-text" resize="none"
                  @keydown.enter.exact.prevent="sendMessage" />
        <button class="bot-input-send" type="button" :disabled="!canSend || sending" aria-label="发送" @click="sendMessage">
          <Loading v-if="sending" class="bot-send-spin" />
          <Promotion v-else />
        </button>
      </div>
    </div>
  </div>

  <div v-else class="bot-empty">
    <ChatAvatar name="bb" kind="bot" :avatar="botProfile.avatar" :size="64" />
    <p class="bot-empty-title">选择一个会话开始聊天</p>
    <p class="bot-empty-hint">点左侧会话，或与冥想bb开始私聊</p>
  </div>
</template>

<style scoped>
.bot-chat { display: flex; flex-direction: column; width: 100%; flex: 1 1 0; min-height: 0; background: var(--color-bg-base); }

.chat-header {
  flex-shrink: 0; display: flex; align-items: center; gap: var(--space-3); min-height: 60px;
  /* 顶部留刘海/状态栏安全区（移动端全屏聊天时 header 是最顶元素，避免贴边/被遮） */
  padding: env(safe-area-inset-top) var(--space-4) 0;
  background: var(--nav-bg); backdrop-filter: var(--nav-backdrop); -webkit-backdrop-filter: var(--nav-backdrop);
  border-bottom: 1px solid var(--nav-border);
}
.chat-back, .chat-header-action {
  flex-shrink: 0; width: 36px; height: 36px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-pill); color: var(--color-text-secondary); background: transparent;
  transition: background var(--duration-fast) var(--ease-standard);
}
.chat-back:hover, .chat-header-action:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.chat-back :deep(svg), .chat-header-action :deep(svg) { width: 20px; height: 20px; }

.chat-header-avatar { position: relative; flex-shrink: 0; display: inline-flex; line-height: 0; }
.chat-header-avatar.editable { cursor: pointer; }
.chat-avatar-cam {
  position: absolute; right: -2px; bottom: -2px; width: 16px; height: 16px;
  display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-pill);
  background: var(--accent); color: var(--color-text-on-accent); border: 1.5px solid var(--color-bg-surface);
  opacity: 0; transform: scale(0.8); transition: opacity var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard);
}
.chat-header-avatar.editable:hover .chat-avatar-cam, .chat-header-avatar.cam-always .chat-avatar-cam { opacity: 1; transform: scale(1); }
.chat-avatar-cam :deep(svg) { width: 9px; height: 9px; }

.chat-header-meta { flex: 1 1 auto; min-width: 0; }
.chat-header-title { font-size: var(--font-size-md); font-weight: var(--font-weight-semibold); color: var(--color-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-header-sub { font-size: var(--font-size-xs); color: var(--color-text-tertiary); margin-top: 1px; }

.bot-conn-banner {
  flex-shrink: 0; display: flex; align-items: center; justify-content: center; gap: var(--space-3);
  padding: var(--space-2) var(--space-4); font-size: var(--font-size-sm); color: var(--color-danger); background: var(--accent-soft);
  border-bottom: 1px solid var(--color-border-subtle);
}
.bot-conn-retry { padding: 2px var(--space-3); border: 1px solid var(--color-danger); border-radius: var(--radius-pill); background: transparent; color: var(--color-danger); font-size: var(--font-size-sm); cursor: pointer; }

.bot-messages { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
.bot-messages-inner { margin-top: auto; display: flex; flex-direction: column; gap: var(--space-4); padding: var(--space-5) var(--space-4) var(--space-4); }

.bot-row { display: flex; align-items: flex-start; gap: var(--space-2); max-width: 100%; }
.bot-row.self { flex-direction: row-reverse; }
.bot-row-avatar { margin-top: 18px; }
.bot-bubble-wrap { display: flex; flex-direction: column; max-width: 72%; min-width: 0; }
.bot-row.self .bot-bubble-wrap { align-items: flex-end; }

.bot-sender-name { display: flex; align-items: center; gap: var(--space-1); font-size: var(--font-size-xs); color: var(--color-text-tertiary); margin: 0 0 3px var(--space-1); }
.bot-badge { display: inline-flex; align-items: center; padding: 0 5px; height: 15px; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--accent); font-size: 10px; font-weight: var(--font-weight-semibold); letter-spacing: 0.04em; }

.bot-bubble { padding: var(--space-3) var(--space-4); border-radius: var(--radius-lg); background: var(--color-bg-surface); border: 1px solid var(--color-border-subtle); color: var(--color-text-primary); font-size: var(--font-size-base); line-height: var(--line-height-normal); word-break: break-word; white-space: pre-wrap; box-shadow: var(--shadow-sm); }
.bot-row.other .bot-bubble { border-top-left-radius: var(--radius-sm); }
.bot-bubble.bot { border-color: var(--accent-soft); }
.bot-bubble.self { background: var(--accent); color: var(--color-text-on-accent); border-color: transparent; border-top-right-radius: var(--radius-sm); }
.bot-text { white-space: pre-wrap; }
.bot-bubble.self :deep(.bot-mention) { color: var(--color-text-on-accent); font-weight: var(--font-weight-semibold); }
:deep(.bot-mention) { color: var(--accent); font-weight: var(--font-weight-medium); }
.bot-text-streaming::after { content: ''; display: inline-block; width: 2px; height: 1em; margin-left: 2px; vertical-align: text-bottom; background: var(--accent); animation: bot-caret-blink 1s steps(1) infinite; }
@keyframes bot-caret-blink { 50% { opacity: 0; } }

.bot-time { font-size: 11px; color: var(--color-text-tertiary); margin: 4px var(--space-1) 0; display: flex; align-items: center; gap: var(--space-2); }
.bot-time.self { flex-direction: row-reverse; }
.bot-retry-btn { display: inline-flex; align-items: center; gap: 3px; padding: 1px 7px; border: none; border-radius: var(--radius-pill); background: var(--accent-soft); color: var(--accent); font-size: 11px; cursor: pointer; }
.bot-retry-btn :deep(svg) { width: 11px; height: 11px; }

.bot-image { max-width: 240px; height: auto; border-radius: var(--radius-md); display: block; margin-top: var(--space-2); }
.bot-bubble > .bot-image:first-child { margin-top: 0; }

.bot-file { display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-2); padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); background: var(--color-bg-muted); border: 1px solid var(--color-border-default); color: var(--color-text-primary); text-decoration: none; max-width: 240px; }
.bot-file-icon { flex-shrink: 0; display: inline-flex; color: var(--accent); }
.bot-file-icon :deep(svg) { width: 22px; height: 22px; }
.bot-file-meta { display: flex; flex-direction: column; min-width: 0; flex: 1 1 auto; }
.bot-file-name { font-size: var(--font-size-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bot-file-size { font-size: var(--font-size-xs); color: var(--color-text-secondary); }
.bot-file-dl { flex-shrink: 0; display: inline-flex; color: var(--color-text-secondary); }
.bot-file-dl :deep(svg) { width: 16px; height: 16px; }

.bot-input-wrap { position: relative; flex-shrink: 0; background: var(--color-bg-surface); border-top: 1px solid var(--color-border-subtle); padding-bottom: env(safe-area-inset-bottom); }
.bot-attach-preview { display: flex; gap: var(--space-2); padding: var(--space-3) var(--space-3) 0; }
.bot-image-thumb { position: relative; width: 64px; height: 64px; border-radius: var(--radius-md); overflow: hidden; border: 1px solid var(--color-border-default); }
.bot-image-thumb img { width: 100%; height: 100%; object-fit: cover; }
.bot-attach-remove { position: absolute; top: 2px; right: 2px; width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center; background: rgba(31,27,22,0.6); color: #fff; border-radius: var(--radius-pill); flex-shrink: 0; }
.bot-attach-remove.static { position: static; }
.bot-attach-remove :deep(svg) { width: 12px; height: 12px; }
.bot-file-chip { display: flex; align-items: center; gap: var(--space-2); max-width: 220px; height: 36px; padding: 0 var(--space-2) 0 var(--space-3); border-radius: var(--radius-md); border: 1px solid var(--color-border-default); background: var(--color-bg-muted); }
.bot-file-chip-icon { flex-shrink: 0; display: inline-flex; color: var(--accent); }
.bot-file-chip-icon :deep(svg) { width: 16px; height: 16px; }
.bot-file-chip-name { flex: 1 1 auto; min-width: 0; font-size: var(--font-size-sm); color: var(--color-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.bot-input-row { display: flex; align-items: flex-end; gap: var(--space-2); padding: var(--space-2) var(--space-3); }
.bot-input-btn { flex-shrink: 0; width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-pill); color: var(--color-text-secondary); background: transparent; transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard); }
.bot-input-btn:hover, .bot-input-btn.active { background: var(--accent-soft); color: var(--accent); }
.bot-input-btn :deep(svg) { width: 20px; height: 20px; }
.bot-input-text { flex: 1 1 auto; min-width: 0; }
.bot-input-text :deep(.el-textarea__inner) { border-radius: var(--radius-lg); background: var(--color-bg-muted); border-color: transparent; font-size: var(--font-size-md); padding: 10px var(--space-3); box-shadow: none; resize: none; line-height: var(--line-height-normal); }
.bot-input-text :deep(.el-textarea__inner:focus) { background: var(--color-bg-surface); border-color: var(--color-border-strong); }
.bot-input-send { flex-shrink: 0; width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-pill); background: var(--accent); color: var(--color-text-on-accent); transition: background var(--duration-fast) var(--ease-standard), opacity var(--duration-fast) var(--ease-standard); }
.bot-input-send:disabled { background: var(--color-border-default); cursor: not-allowed; opacity: 0.7; }
.bot-input-send:not(:disabled):hover { background: var(--accent-strong); }
.bot-send-spin { animation: bot-send-spin 0.8s linear infinite; }
@keyframes bot-send-spin { to { transform: rotate(360deg); } }
.bot-attach-progress { display: flex; align-items: center; gap: var(--space-2); width: 100%; }
.bot-attach-progress :deep(.el-progress) { flex: 1; }
.bot-attach-progress-label { font-size: var(--font-size-xs); color: var(--color-text-secondary); white-space: nowrap; }
.bot-input-send :deep(svg) { width: 18px; height: 18px; }
:deep(.bot-upload .el-upload) { width: auto; height: auto; border: none; background: transparent; }

.bot-mention-pop { position: absolute; left: var(--space-3); bottom: calc(100% - var(--space-1)); width: 220px; max-height: 260px; overflow-y: auto; padding: var(--space-2); background: var(--color-bg-surface); border: 1px solid var(--color-border-default); border-radius: var(--radius-lg); box-shadow: var(--shadow-lg); z-index: var(--z-overlay); }
.bot-mention-head { padding: var(--space-1) var(--space-2) var(--space-2); font-size: var(--font-size-xs); color: var(--color-text-tertiary); }
.bot-mention-item { display: flex; align-items: center; gap: var(--space-2); width: 100%; padding: var(--space-2); border-radius: var(--radius-md); background: transparent; text-align: left; transition: background var(--duration-fast) var(--ease-standard); }
.bot-mention-item:hover { background: var(--color-bg-hover); }
.bot-mention-name { flex: 1 1 auto; font-size: var(--font-size-base); color: var(--color-text-primary); }
.mention-pop-enter-active, .mention-pop-leave-active { transition: opacity var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard); }
.mention-pop-enter-from, .mention-pop-leave-to { opacity: 0; transform: translateY(6px); }

.bot-empty { flex: 1 1 0; min-height: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); background: var(--color-bg-base); padding: var(--space-8); }
.bot-empty-title { font-size: var(--font-size-lg); font-weight: var(--font-weight-medium); color: var(--color-text-primary); margin-top: var(--space-2); }
.bot-empty-hint { font-size: var(--font-size-sm); color: var(--color-text-tertiary); text-align: center; max-width: 280px; }
</style>
