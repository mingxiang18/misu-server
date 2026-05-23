<script setup>
import { ref, computed, inject, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useBreakpoint } from '@/composables/useBreakpoint'
import ConversationList from './ConversationList.vue'
import ChatRoom from './ChatRoom.vue'
import CreateGroupDialog from './CreateGroupDialog.vue'
import GroupMemberPanel from './GroupMemberPanel.vue'
import { listConversations, getPrivateConversation } from '@/api/chat/chat'
import logger from '@/utils/logger'

const { isMobile, isDesktop } = useBreakpoint()

const userInfo = inject('userInfo', { value: {} })
const currentUser = computed(() => ({
  userId: userInfo.value && userInfo.value.userId != null ? String(userInfo.value.userId) : null,
  userName: userInfo.value ? userInfo.value.userName : '',
  nickName: userInfo.value ? (userInfo.value.nickName || userInfo.value.userName) : '',
  avatar: userInfo.value ? userInfo.value.avatar : ''
}))

const conversations = ref([])
const activeId = ref(null)
const mobileShowChat = ref(false)

const showCreate = ref(false)
const showMembers = ref(false)

const active = computed(() => conversations.value.find((c) => String(c.id) === String(activeId.value)) || null)

const refreshConversations = async () => {
  try {
    // 确保与 bb 的私聊会话存在
    await getPrivateConversation()
    const res = await listConversations()
    conversations.value = res.data || []
    if (isDesktop.value && !activeId.value && conversations.value.length > 0) {
      activeId.value = conversations.value[0].id
    }
  } catch (err) {
    logger.error('加载会话列表失败:', err)
  }
}

onMounted(refreshConversations)

const onSelect = (c) => {
  activeId.value = c.id
  if (isMobile.value) mobileShowChat.value = true
}
const onBack = () => { mobileShowChat.value = false }

// 群聊创建在阶段2 接入服务端；此处先提示
const onCreated = () => {
  showCreate.value = false
  ElMessage.info('群聊功能即将上线（阶段2）')
}
const onRemoveMember = () => { ElMessage.info('群聊功能即将上线（阶段2）') }
const onLeave = () => { showMembers.value = false }
const onAddMember = () => { showMembers.value = false }
</script>

<template>
  <div class="bot-ws" :class="isMobile ? 'mobile' : 'desktop'">
    <template v-if="isDesktop">
      <aside class="bot-ws-list">
        <ConversationList :conversations="conversations" :active-id="activeId" @select="onSelect" @create-group="showCreate = true" />
      </aside>
      <section class="bot-ws-chat">
        <ChatRoom :conversation="active" :current-user="currentUser" :is-mobile="false" @open-members="showMembers = true" />
      </section>
    </template>

    <template v-else>
      <ConversationList v-show="!mobileShowChat" :conversations="conversations" :active-id="activeId" @select="onSelect" @create-group="showCreate = true" />
      <ChatRoom v-if="mobileShowChat" :conversation="active" :current-user="currentUser" :is-mobile="true" @back="onBack" @open-members="showMembers = true" />
    </template>

    <CreateGroupDialog v-model="showCreate" :is-mobile="isMobile" @created="onCreated" />
    <GroupMemberPanel
        v-model="showMembers"
        :conversation="active"
        :current-user="currentUser"
        :is-mobile="isMobile"
        @add-member="onAddMember"
        @remove-member="onRemoveMember"
        @leave="onLeave" />
  </div>
</template>

<style scoped>
.bot-ws { flex: 1 1 0; min-height: 0; display: flex; width: 100%; }
.bot-ws.desktop .bot-ws-list { width: 320px; flex-shrink: 0; border-right: 1px solid var(--color-border-subtle); min-height: 0; }
.bot-ws.desktop .bot-ws-chat { flex: 1 1 auto; min-width: 0; display: flex; min-height: 0; }
.bot-ws.mobile { flex-direction: column; }
</style>
