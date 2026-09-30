import assert from 'node:assert/strict'
import { createAiCliSessionLifecycle, isCurrentAiCliSocket } from '../src/views/ops/aiCliSessionLifecycle.mjs'
import { getAiCliExitError } from '../src/views/ops/aiCliErrors.mjs'

const revoked = []
const lifecycle = createAiCliSessionLifecycle(async (sessionId) => {
  revoked.push(sessionId)
})
const oldSocket = {}
const newSocket = {}

lifecycle.bind(oldSocket, 'old-session', 1)
const abnormalClose = lifecycle.close(oldSocket, 'old-session', 1)
await abnormalClose.promise
await lifecycle.close(oldSocket, 'old-session', 1).promise
assert.deepEqual(revoked, ['old-session'], '异常关闭只应 DELETE 一次')

const secondLifecycle = createAiCliSessionLifecycle(async (sessionId) => {
  revoked.push(sessionId)
})
secondLifecycle.bind(oldSocket, 'active-session', 2)
const userRevoke = secondLifecycle.revokeOnce('active-session')
const userClose = secondLifecycle.close(oldSocket, 'active-session', 2)
await Promise.all([userRevoke, userClose.promise])
assert.equal(revoked.filter((id) => id === 'active-session').length, 1, '主动断开不得重复 DELETE')

secondLifecycle.bind(newSocket, 'new-session', 3)
const staleClose = secondLifecycle.close(oldSocket, 'active-session', 2)
await staleClose.promise
assert.equal(staleClose.current, false, '旧 socket 关闭不能成为当前连接')
assert.equal(secondLifecycle.isCurrent(newSocket, 'new-session', 3), true, '旧 socket 关闭不得影响新 session')
assert.equal(revoked.filter((id) => id === 'new-session').length, 0, '旧 socket 不得撤销新 session')

assert.equal(isCurrentAiCliSocket(oldSocket, null, 2, 2), false, '主动断开后 socket 已清空，不得显示退出错误')
assert.equal(isCurrentAiCliSocket(oldSocket, newSocket, 2, 3), false, '旧 socket 不得更新新会话状态')
assert.equal(isCurrentAiCliSocket(newSocket, newSocket, 3, 3), true, '当前 backend close 应更新会话状态并显示退出错误')

assert.match(getAiCliExitError('CLAUDE', false), /第三方 API URL 和 API Key/)
assert.match(getAiCliExitError('CODEX', false), /Codex CLI 已退出/)
assert.match(getAiCliExitError('CLAUDE', true), /Claude Code 已退出，请重新连接/)

console.log('AI CLI session lifecycle checks passed')
