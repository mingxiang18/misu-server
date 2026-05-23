import { reactive } from 'vue'
import { getBotProfile, setBotAvatarApi } from '@/api/chat/chat'
import logger from '@/utils/logger'

// bb 是全局共享机器人，头像/昵称由服务端统一存储（ADMIN 设置，全局生效）。
export const botProfile = reactive({
  name: '冥想bb',
  avatar: ''
})

// 进聊天页加载一次，所有人看到同一个头像
export async function loadBotProfile() {
  try {
    const res = await getBotProfile()
    if (res && res.data) {
      botProfile.name = res.data.name || '冥想bb'
      botProfile.avatar = res.data.avatar || ''
    }
  } catch (e) {
    logger.error('加载 bb 资料失败:', e)
  }
}

// 仅 ADMIN 调用成功；失败（如 403）抛出，由调用方提示
export async function setBotAvatar(dataUrl) {
  await setBotAvatarApi(dataUrl)
  botProfile.avatar = dataUrl
}
