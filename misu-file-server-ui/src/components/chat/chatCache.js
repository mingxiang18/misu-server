// 浏览器缓存：会话列表 + 各会话最近消息，仅作「秒显」用，服务端仍是真相源。
// 进入页面先用缓存渲染，接口返回后替换并回写缓存。

const LIST_PREFIX = 'chat-conv-list:'
const MSG_PREFIX = 'chat-msgs:'
const MSG_LIMIT = 40

const safeGet = (k) => {
  try {
    const r = localStorage.getItem(k)
    return r ? JSON.parse(r) : null
  } catch (e) {
    return null
  }
}
const safeSet = (k, v) => {
  try {
    localStorage.setItem(k, JSON.stringify(v))
  } catch (e) {
    // 配额不足等：跳过缓存，不影响功能
  }
}

export const readConvList = (scope) => safeGet(LIST_PREFIX + (scope || 'me'))
export const writeConvList = (scope, list) => safeSet(LIST_PREFIX + (scope || 'me'), list || [])

export const readMsgs = (scope, convId) => safeGet(MSG_PREFIX + (scope || 'me') + ':' + convId)
export const writeMsgs = (scope, convId, msgs) =>
  safeSet(MSG_PREFIX + (scope || 'me') + ':' + convId, (msgs || []).slice(-MSG_LIMIT))

export const clearChatCache = () => {
  try {
    Object.keys(localStorage)
      .filter((k) => k.startsWith(LIST_PREFIX) || k.startsWith(MSG_PREFIX))
      .forEach((k) => localStorage.removeItem(k))
  } catch (e) { /* ignore */ }
}
