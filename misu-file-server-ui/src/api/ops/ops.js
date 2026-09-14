import request from '@/api/request'

const unwrap = (response) => response?.data || response || {}

// 统一管理运维接口，页面只处理短期会话和目标地址。
export function issueConsoleTicket(target) {
  return request({
    url: '/ops/api/console/tickets',
    method: 'post',
    data: { target }
  }).then(unwrap)
}

export function createSshSession(nodeId, cols, rows) {
  return request({
    url: '/ops/api/ssh/sessions',
    method: 'post',
    data: { nodeId, cols, rows }
  }).then(unwrap)
}

export function revokeSshSession(sessionId) {
  if (!sessionId) return Promise.resolve()
  return request({
    url: `/ops/api/ssh/sessions/${encodeURIComponent(sessionId)}`,
    method: 'delete',
    timeout: 2000,
    silent: true,
    headers: { skipAuthRefresh: true }
  }).then(unwrap)
}

export function createAiSession(tool, cols, rows) {
  return request({
    url: '/ops/api/ai/sessions',
    method: 'post',
    data: { tool, cols, rows }
  }).then(unwrap)
}

export function revokeAiSession(sessionId) {
  if (!sessionId) return Promise.resolve()
  return request({
    url: `/ops/api/ai/sessions/${encodeURIComponent(sessionId)}`,
    method: 'delete',
    timeout: 2000,
    silent: true,
    headers: { skipAuthRefresh: true }
  }).then(unwrap)
}

export function getWebSocketUrl(url) {
  if (!url) throw new Error('后端未返回终端 WebSocket 地址')
  return url.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:')
}
