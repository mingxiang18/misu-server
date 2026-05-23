import { reactive } from 'vue'

// bb 是全局共享的机器人，头像 / 昵称是全局配置（mock 先存内存）。
// 阶段1 改为服务端配置：拉一次 GET /bot/profile，管理员上传后所有人一致。
export const botProfile = reactive({
  name: '冥想bb',
  avatar: ''
})

export const setBotAvatar = (dataUrl) => { botProfile.avatar = dataUrl }
