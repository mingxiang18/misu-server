<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { FullScreen, Refresh } from '@element-plus/icons-vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import 'xterm/css/xterm.css'
import { createAiSession, getWebSocketUrl, revokeAiSession } from '@/api/ops/ops'
import { createAiCliSessionLifecycle, isCurrentAiCliSocket } from './aiCliSessionLifecycle.mjs'
import { getAiCliExitError } from './aiCliErrors.mjs'

const props = defineProps({
  tool: { type: String, required: true },
  label: { type: String, required: true }
})

const terminalHost = ref(null)
const terminal = shallowRef(null)
const fitAddon = shallowRef(null)
const socket = ref(null)
const sessionId = ref('')
const status = ref('idle')
const error = ref('')
const receivedOutput = ref(false)
const fullScreen = ref(false)
const ctrlPending = ref(false)
let resizeObserver
let generation = 0
const sessionLifecycle = createAiCliSessionLifecycle(revokeAiSession)

const connected = computed(() => status.value === 'connected')
const actionLabel = computed(() => status.value === 'idle' ? '连接' : '重连')
const statusText = computed(() => ({
  idle: '未连接',
  connecting: '连接中',
  connected: '已连接',
  closed: '已断开',
  error: '连接失败'
}[status.value] || '未连接'))

function write(data) {
  if (terminal.value && data) terminal.value.write(data)
}

function send(message) {
  if (socket.value?.readyState !== WebSocket.OPEN) return false
  socket.value.send(JSON.stringify(message))
  return true
}

function sendInput(data) {
  if (!connected.value) return
  if (ctrlPending.value) {
    ctrlPending.value = false
    if (/^[a-z]$/i.test(data)) {
      data = String.fromCharCode(data.toUpperCase().charCodeAt(0) - 64)
    }
  }
  send({ type: 'input', data })
}

function resize() {
  if (!fitAddon.value || !terminal.value) return
  try {
    fitAddon.value.fit()
    send({ type: 'resize', cols: terminal.value.cols, rows: terminal.value.rows })
  } catch {
    // Hidden panes can be measured before their layout is committed.
  }
}

async function disconnect(invalidate = true) {
  if (invalidate) generation += 1
  const currentSocket = socket.value
  const currentSession = sessionId.value
  socket.value = null
  sessionId.value = ''
  currentSocket?.close()
  if (status.value !== 'idle') status.value = 'closed'
  if (currentSession) await sessionLifecycle.revokeOnce(currentSession)
}

async function connect() {
  const currentGeneration = ++generation
  await disconnect(false)
  if (currentGeneration !== generation) return
  status.value = 'connecting'
  error.value = ''
  receivedOutput.value = false
  terminal.value?.clear()
  terminal.value?.reset()
  try {
    await nextTick()
    resize()
    const data = await createAiSession(props.tool, terminal.value?.cols || 80, terminal.value?.rows || 24)
    const nextSessionId = data.sessionId
    if (!nextSessionId || !data.websocketUrl) throw new Error('后端未返回 AI 终端会话')
    if (currentGeneration !== generation || !terminal.value) {
      await sessionLifecycle.revokeOnce(nextSessionId)
      return
    }
    sessionId.value = nextSessionId
    const nextSocket = new WebSocket(getWebSocketUrl(data.websocketUrl))
    socket.value = nextSocket
    sessionLifecycle.bind(nextSocket, nextSessionId, currentGeneration)
    nextSocket.onopen = () => {
      if (socket.value !== nextSocket || currentGeneration !== generation) return
      status.value = 'connected'
      write(`\r\n${props.label} 会话已连接\r\n`)
      resize()
    }
    nextSocket.onmessage = (event) => {
      if (socket.value !== nextSocket || currentGeneration !== generation) return
      if (typeof event.data === 'string') {
        if (event.data.length > 0) receivedOutput.value = true
        write(event.data)
      }
    }
    nextSocket.onerror = () => {
      if (socket.value !== nextSocket || currentGeneration !== generation) {
        sessionLifecycle.revokeOnce(nextSessionId)
        return
      }
      status.value = 'error'
      error.value = '终端连接失败，请检查运维服务和 CLI 配置'
      sessionLifecycle.revokeOnce(nextSessionId)
      nextSocket.close()
    }
    nextSocket.onclose = () => {
      const result = sessionLifecycle.close(nextSocket, nextSessionId, currentGeneration)
      if (result.current && isCurrentAiCliSocket(nextSocket, socket.value, currentGeneration, generation)) {
        const hadExplicitError = Boolean(error.value)
        socket.value = null
        if (sessionId.value === nextSessionId) sessionId.value = ''
        if (status.value === 'connected' || status.value === 'connecting') status.value = 'closed'
        if (!hadExplicitError) error.value = getAiCliExitError(props.tool, receivedOutput.value)
        receivedOutput.value = false
      }
      void result.promise
    }
  } catch (cause) {
    if (currentGeneration !== generation) return
    const orphanSession = sessionId.value
    sessionId.value = ''
    status.value = 'error'
    error.value = cause?.message || 'AI 终端连接失败'
    await sessionLifecycle.revokeOnce(orphanSession)
  }
}

function toggleFullScreen() {
  if (!document.fullscreenElement) {
    terminalHost.value?.requestFullscreen?.().catch(() => {})
  } else {
    document.exitFullscreen().catch(() => {})
  }
}

function syncFullScreen() {
  fullScreen.value = document.fullscreenElement === terminalHost.value
}

async function paste() {
  try {
    sendInput(await navigator.clipboard.readText())
  } catch {
    error.value = '浏览器未允许读取剪贴板'
  }
}

function assistKey(key) {
  if (key === 'ctrl') {
    ctrlPending.value = !ctrlPending.value
    return
  }
  const values = { esc: '\u001b', tab: '\t', up: '\u001b[A', down: '\u001b[B', left: '\u001b[D', right: '\u001b[C' }
  if (key === 'ctrl-c') return sendInput('\u0003')
  if (key === 'ctrl-d') return sendInput('\u0004')
  sendInput(values[key] || '')
}

function createTerminal() {
  terminal.value = new Terminal({
    cursorBlink: true,
    convertEol: true,
    fontFamily: 'var(--font-family-mono)',
    fontSize: 13,
    theme: { background: '#111827', foreground: '#e5e7eb', cursor: '#f59e0b' },
    scrollback: 3000
  })
  fitAddon.value = new FitAddon()
  terminal.value.loadAddon(fitAddon.value)
  terminal.value.open(terminalHost.value)
  terminal.value.onData(sendInput)
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(terminalHost.value)
  nextTick(resize)
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullScreen)
  createTerminal()
})

onBeforeUnmount(() => {
  generation += 1
  document.removeEventListener('fullscreenchange', syncFullScreen)
  resizeObserver?.disconnect()
  disconnect(false)
  terminal.value?.dispose()
  terminal.value = null
})
</script>

<template>
  <section class="ai-cli-terminal">
    <div class="ai-cli-terminal-bar">
      <div class="ai-cli-terminal-title">
        <span class="ai-cli-terminal-dot" :class="status"></span>
        <strong>{{ label }}</strong>
        <span class="ai-cli-terminal-status" :class="status">{{ statusText }}</span>
      </div>
      <div class="ai-cli-terminal-actions">
        <el-button v-if="!connected" type="primary" size="small" :disabled="status === 'connecting'" @click="connect">
          <Refresh v-if="status !== 'idle'" />{{ actionLabel }}
        </el-button>
        <el-button v-else size="small" @click="disconnect">断开</el-button>
        <el-button
            text
            :icon="FullScreen"
            size="small"
            :aria-pressed="fullScreen"
            :aria-label="fullScreen ? `退出${label}全屏` : `${label}全屏`"
            @click="toggleFullScreen">{{ fullScreen ? '退出全屏' : '全屏' }}</el-button>
      </div>
    </div>

    <div ref="terminalHost" class="ai-cli-terminal-host" @click="terminal?.focus()"></div>
    <div class="ai-cli-terminal-footer">
      <div class="ai-cli-assist-keys">
        <button type="button" :class="{ active: ctrlPending }" @click="assistKey('ctrl')">Ctrl</button>
        <button type="button" @click="assistKey('esc')">Esc</button>
        <button type="button" @click="assistKey('tab')">Tab</button>
        <button type="button" @click="assistKey('ctrl-c')">Ctrl+C</button>
        <button type="button" @click="assistKey('ctrl-d')">Ctrl+D</button>
        <button type="button" @click="assistKey('up')">↑</button>
        <button type="button" @click="assistKey('down')">↓</button>
        <button type="button" @click="assistKey('left')">←</button>
        <button type="button" @click="assistKey('right')">→</button>
        <button type="button" @click="paste">粘贴</button>
      </div>
    </div>
    <p v-if="error" class="ai-cli-terminal-error">{{ error }}</p>
  </section>
</template>

<style scoped>
.ai-cli-terminal {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #111827;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}
.ai-cli-terminal-bar,
.ai-cli-terminal-title,
.ai-cli-terminal-actions {
  display: flex;
  align-items: center;
}
.ai-cli-terminal-bar {
  justify-content: space-between;
  gap: var(--space-2);
  min-height: 48px;
  padding: 0 var(--space-3);
  color: var(--color-text-primary);
  background: var(--color-bg-surface);
  border-bottom: 1px solid var(--color-border-subtle);
}
.ai-cli-terminal-title { gap: var(--space-2); min-width: 0; }
.ai-cli-terminal-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-cli-terminal-dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: 50%; background: var(--color-text-disabled); }
.ai-cli-terminal-dot.connected { background: var(--color-success); }
.ai-cli-terminal-dot.connecting { background: var(--color-warning); }
.ai-cli-terminal-dot.error { background: var(--color-danger); }
.ai-cli-terminal-status { color: var(--color-text-tertiary); font-size: var(--font-size-xs); }
.ai-cli-terminal-status.connected { color: var(--color-success); }
.ai-cli-terminal-status.connecting { color: var(--color-warning); }
.ai-cli-terminal-status.error { color: var(--color-danger); }
.ai-cli-terminal-actions { gap: var(--space-1); white-space: nowrap; }
.ai-cli-terminal-host { flex: 1 1 0; min-height: 0; padding: var(--space-3); overflow: hidden; }
.ai-cli-terminal-host :deep(.xterm) { height: 100%; }
.ai-cli-terminal-host :deep(.xterm-viewport) { overflow-y: auto; }
.ai-cli-terminal-footer { padding: var(--space-2) var(--space-3); background: #111827; }
.ai-cli-assist-keys { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.ai-cli-assist-keys button {
  min-height: 28px;
  padding: 4px 8px;
  color: #cbd5e1;
  background: #1f2937;
  border: 1px solid #374151;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
}
.ai-cli-assist-keys button.active { color: var(--accent); border-color: var(--accent); }
.ai-cli-terminal-error { margin: 0; padding: var(--space-2) var(--space-3); color: var(--color-danger); background: var(--color-bg-surface); font-size: var(--font-size-xs); }

@media (max-width: 640px) {
  .ai-cli-terminal-bar { min-height: 52px; padding: var(--space-2) var(--space-3); }
  .ai-cli-terminal-actions :deep(.el-button) { min-height: 40px; padding-left: 8px; padding-right: 8px; }
  .ai-cli-terminal-host { min-height: 0; padding: var(--space-2); }
  .ai-cli-terminal-footer { padding: var(--space-2); }
  .ai-cli-assist-keys button { min-width: 40px; min-height: 40px; padding: 8px; }
}
</style>
