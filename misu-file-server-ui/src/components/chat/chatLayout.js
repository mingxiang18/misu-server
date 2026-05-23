import { reactive } from 'vue'

// 移动端进入会话时全屏聊天：隐藏底部 TabBar（由 ChatWorkspace 置位，Index/TabBar 读取）。
export const chatLayout = reactive({
  hideTabBar: false
})
