<script setup>
import { ref, computed, inject, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useBreakpoint } from '@/composables/useBreakpoint'
import ConversationList from './ConversationList.vue'
import ChatRoom from './ChatRoom.vue'
import CreateGroupDialog from './CreateGroupDialog.vue'
import GroupMemberPanel from './GroupMemberPanel.vue'
import {
  listConversations, getPrivateConversation,
  createGroup, listGroupMembers, addGroupMembers, removeGroupMember
} from '@/api/chat/chat'
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
const activeMembers = ref([])

const showCreate = ref(false)
const showMembers = ref(false)
const createDialog = ref(null)

const active = computed(() => conversations.value.find((c) => String(c.id) === String(activeId.value)) || null)

const refreshConversations = async () => {
  try {
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

// 切到群聊时加载成员（供 @ 选择 + 成员面板）
const loadMembers = async () => {
  if (!active.value || active.value.type !== 'GROUP') { activeMembers.value = []; return }
  try {
    const res = await listGroupMembers(active.value.id)
    activeMembers.value = res.data || []
  } catch (err) {
    logger.error('加载群成员失败:', err)
    activeMembers.value = []
  }
}
watch(() => active.value && active.value.id, loadMembers, { immediate: true })

const onSelect = (c) => {
  activeId.value = c.id
  if (isMobile.value) mobileShowChat.value = true
}
const onBack = () => { mobileShowChat.value = false }

const onCreated = async ({ title, memberUserIds }) => {
  try {
    const res = await createGroup({ title, memberUserIds })
    showCreate.value = false
    await refreshConversations()
    if (res.data && res.data.id) {
      activeId.value = res.data.id
      if (isMobile.value) mobileShowChat.value = true
    }
    ElMessage.success('群聊已创建')
  } catch (err) {
    logger.error('建群失败:', err)
    ElMessage.error('建群失败')
    if (createDialog.value) createDialog.value.resetSubmitting()
  }
}

const onAddMember = () => {
  // 简化：复用建群弹窗的选人 UI 暂不单独做加人弹窗，提示走建群入口（阶段2 可扩展独立加人弹窗）
  ElMessage.info('请在群里通过「建群」流程管理成员（加人独立弹窗后续补充）')
}
const onRemoveMember = async (m) => {
  if (!active.value) return
  try {
    await removeGroupMember(active.value.id, m.userId)
    await loadMembers()
    ElMessage.success(`已移出 ${m.nickName || m.userId}`)
  } catch (err) {
    ElMessage.error('移除失败')
  }
}
const onLeave = async () => {
  if (!active.value) return
  const meId = currentUser.value.userId
  try {
    // 自己退群：用成员里 self 的 userId（currentUser.userId 可能为空）
    const selfMember = activeMembers.value.find((m) => m.self)
    const uid = (selfMember && selfMember.userId) || meId
    await removeGroupMember(active.value.id, uid)
    showMembers.value = false
    activeId.value = null
    await refreshConversations()
    ElMessage.success('已退出群聊')
  } catch (err) {
    ElMessage.error('退群失败')
  }
}
</script>

<template>
  <div class="bot-ws" :class="isMobile ? 'mobile' : 'desktop'">
    <template v-if="isDesktop">
      <aside class="bot-ws-list">
        <ConversationList :conversations="conversations" :active-id="activeId" @select="onSelect" @create-group="showCreate = true" />
      </aside>
      <section class="bot-ws-chat">
        <ChatRoom :conversation="active" :current-user="currentUser" :members="activeMembers" :is-mobile="false" @open-members="showMembers = true" />
      </section>
    </template>

    <template v-else>
      <ConversationList v-show="!mobileShowChat" :conversations="conversations" :active-id="activeId" @select="onSelect" @create-group="showCreate = true" />
      <ChatRoom v-if="mobileShowChat" :conversation="active" :current-user="currentUser" :members="activeMembers" :is-mobile="true" @back="onBack" @open-members="showMembers = true" />
    </template>

    <CreateGroupDialog ref="createDialog" v-model="showCreate" :is-mobile="isMobile" @created="onCreated" />
    <GroupMemberPanel
        v-model="showMembers"
        :members="activeMembers"
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
