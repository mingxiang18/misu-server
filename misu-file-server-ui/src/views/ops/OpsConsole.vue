<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { FullScreen, Refresh, Monitor, Connection, WarningFilled } from '@element-plus/icons-vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import 'xterm/css/xterm.css'
import { createSshSession, getWebSocketUrl, issueConsoleTicket, revokeSshSession } from '@/api/ops/ops'
import DatabaseManagement from './DatabaseManagement.vue'
import AiCliTerminal from './AiCliTerminal.vue'
import { installQbittorrentParentBridge, releaseQbittorrentParentBridge } from './qbittorrentParentBridge.mjs'

const tabs = [
  { key: 'nacos', target: 'NACOS', label: 'Nacos 控制台', hint: '配置与服务管理' },
  { key: 'headlamp', target: 'HEADLAMP', label: 'Kubernetes', hint: 'Headlamp 集群控制台' },
  { key: 'ssh', label: 'SSH 终端', hint: '节点交互式终端' },
  { key: 'codex-cli', tool: 'CODEX', label: 'Codex CLI', hint: 'Codex 交互式终端' },
  { key: 'claude-code', tool: 'CLAUDE', label: 'Claude Code', hint: 'Claude Code 交互式终端' },
  { key: 'qbittorrent', target: 'QBITTORRENT', label: 'qBittorrent', hint: '下载与做种管理' },
  { key: 'database', label: '数据库', hint: '表数据与结构' }
]

const nodes = [
  { id: 'master', name: '主节点', host: '10.8.0.1' },
  { id: 'worker', name: '工作节点', host: '10.8.0.26' }
]

const activeTab = ref('nacos')
const activeNode = ref('master')
const consoleFullScreen = ref(false)
const embeddedTabs = tabs.filter((tab) => tab.target)
const openedTabs = ref(new Set(['nacos']))
const consoleFrames = {}
const consoleStates = reactive(Object.fromEntries(embeddedTabs.map((tab) => [tab.key, {
  frameName: `ops-console-${tab.key}-${Math.random().toString(36).slice(2)}`,
  url: '',
  loading: false,
  error: '',
  navigationStarted: false,
  initialized: false,
  generation: 0
}])))

const terminalHost = ref(null)
const terminal = shallowRef(null)
const fitAddon = shallowRef(null)
const socket = ref(null)
const sshSessionId = ref('')
const sshStatus = ref('idle')
const sshError = ref('')
const terminalFullScreen = ref(false)
const ctrlPending = ref(false)
let resizeObserver
let connectionGeneration = 0
let sshHeartbeatTimer

function setConsoleFrame(key, frame) {
  if (frame) consoleFrames[key] = frame
  else delete consoleFrames[key]
}

function isTabOpened(key) {
  return openedTabs.value.has(key)
}

function openTabOnce(key) {
  if (isTabOpened(key)) return
  openedTabs.value = new Set([...openedTabs.value, key])
}

function stopConsoleFrame(key) {
  const frame = consoleFrames[key]
  if (!frame) return
  // Stop a previous form navigation before removing the document. This also
  // prevents a stale console request from racing a newly issued ticket.
  try {
    frame.contentWindow?.stop?.()
  } catch {
    // A cross-origin frame may reject access to contentWindow.
  }
  frame.src = 'about:blank'
}

function cancelConsoleLoad(key) {
  const state = consoleStates[key]
  if (!state) return
  state.generation += 1
  stopConsoleFrame(key)
  state.url = ''
  state.loading = false
  state.navigationStarted = false
}

function handleConsoleFrameLoad(key, event) {
  const state = consoleStates[key]
  if (!state?.navigationStarted) return
  // The initial about:blank can dispatch after form.submit() on slower mobile
  // browsers. Keep the loading mask until the POST navigation leaves blank.
  try {
    if (event?.target?.contentWindow?.location?.href === 'about:blank') return
  } catch {
    // Cross-origin console documents cannot expose location; that is the real
    // navigation, so it is safe to clear the mask.
  }
  state.loading = false
}

function handleConsoleFrameError(key) {
  const state = consoleStates[key]
  if (!state?.navigationStarted) return
  if (embeddedTabs.find((tab) => tab.key === key)?.target === 'QBITTORRENT') releaseQbittorrentParentBridge()
  state.loading = false
  state.error = '控制台加载失败，请重试'
}

const activeTabInfo = computed(() => tabs.find((tab) => tab.key === activeTab.value))
const activeNodeInfo = computed(() => nodes.find((node) => node.id === activeNode.value))
const isAiCliTab = computed(() => Boolean(activeTabInfo.value?.tool))
const isSshConnected = computed(() => sshStatus.value === 'connected')

const statusText = computed(() => ({
  idle: '未连接',
  connecting: '连接中',
  connected: '已连接',
  closed: '已断开',
  error: '连接失败'
}[sshStatus.value] || '未连接'))

async function loadConsole(target, force = false) {
  const state = consoleStates[target]
  if (!state || (!force && (state.initialized || state.loading))) return
  const generation = ++state.generation
  state.initialized = true
  state.error = ''
  state.loading = true
  state.navigationStarted = false
  if (force) {
    if (embeddedTabs.find((tab) => tab.key === target)?.target === 'QBITTORRENT') {
      releaseQbittorrentParentBridge()
    }
    stopConsoleFrame(target)
    state.url = ''
    state.frameName = `ops-console-${target}-${Math.random().toString(36).slice(2)}`
  }
  await nextTick()
  if (generation !== state.generation) return
  try {
    const consoleTarget = tabs.find((tab) => tab.key === target)?.target || target
    const data = await issueConsoleTicket(consoleTarget)
    if (generation !== state.generation) return
    const entryUrl = data.entryUrl
    if (!entryUrl) {
      throw new Error('运维控制台尚未配置访问地址')
    }

    // 在 iframe 中 POST 一次性票据，避免票据进入地址栏或浏览器历史。
    const ticket = data.ticket
    const exchangeUrl = data.exchangeUrl
    if (!ticket || !exchangeUrl) throw new Error('后端未返回有效的控制台会话')
    state.url = entryUrl
    await nextTick()
    if (generation !== state.generation) return
    if (ticket && consoleFrames[target]) {
      if (consoleTarget === 'QBITTORRENT') installQbittorrentParentBridge()
      const form = document.createElement('form')
      form.method = 'post'
      form.action = exchangeUrl
      form.target = state.frameName
      form.hidden = true
      const input = document.createElement('input')
      input.type = 'hidden'
      input.name = 'ticket'
      input.value = ticket
      form.appendChild(input)
      document.body.appendChild(form)
      state.navigationStarted = true
      form.submit()
      form.remove()
    }
  } catch (error) {
    if (generation === state.generation) {
      state.error = error?.message || '控制台加载失败'
    }
  } finally {
    if (generation === state.generation && state.error) state.loading = false
  }
}

function reloadConsole() {
  if (consoleStates[activeTab.value]) loadConsole(activeTab.value, true)
}

async function toggleFullScreen(element, state) {
  try {
    if (!document.fullscreenElement) {
      await element?.requestFullscreen?.()
    } else {
      await document.exitFullscreen()
    }
    state.value = Boolean(document.fullscreenElement)
  } catch (error) {
    ElMessage.warning('当前浏览器不支持全屏操作')
  }
}

function setConsoleFullScreen() {
  toggleFullScreen(consoleFrames[activeTab.value]?.parentElement, consoleFullScreen)
}

function syncFullScreenState() {
  consoleFullScreen.value = document.fullscreenElement === consoleFrames[activeTab.value]?.parentElement
  terminalFullScreen.value = document.fullscreenElement === terminalHost.value
}

function writeTerminal(data) {
  if (terminal.value && data) terminal.value.write(data)
}

function sendSocketMessage(message) {
  if (socket.value?.readyState === WebSocket.OPEN) {
    socket.value.send(JSON.stringify(message))
    return true
  }
  return false
}

function stopSshHeartbeat() {
  if (sshHeartbeatTimer) clearInterval(sshHeartbeatTimer)
  sshHeartbeatTimer = undefined
}

function startSshHeartbeat(nextSocket, generation) {
  stopSshHeartbeat()
  sshHeartbeatTimer = setInterval(() => {
    if (socket.value !== nextSocket || generation !== connectionGeneration || nextSocket.readyState !== WebSocket.OPEN) {
      stopSshHeartbeat()
      return
    }
    nextSocket.send(JSON.stringify({ type: 'ping' }))
  }, 45_000)
}

function sendInput(data) {
  if (!isSshConnected.value) return
  if (ctrlPending.value) {
    ctrlPending.value = false
    if (/^[a-z]$/i.test(data)) data = String.fromCharCode(data.toUpperCase().charCodeAt(0) - 64)
  }
  sendSocketMessage({ type: 'input', data })
}

function handleSocketMessage(event) {
  if (typeof event.data !== 'string') return
  writeTerminal(event.data)
}

function resizeTerminal() {
  if (activeTab.value !== 'ssh' || !fitAddon.value || !terminal.value) return
  try {
    fitAddon.value.fit()
    sendSocketMessage({ type: 'resize', cols: terminal.value.cols, rows: terminal.value.rows })
  } catch {
    // 切换标签时终端可能暂时隐藏。
  }
}

async function disconnectSsh(invalidate = true) {
  if (invalidate) connectionGeneration += 1
  stopSshHeartbeat()
  const currentSocket = socket.value
  const currentSession = sshSessionId.value
  socket.value = null
  sshSessionId.value = ''
  currentSocket?.close()
  if (sshStatus.value !== 'idle') sshStatus.value = 'closed'
  if (currentSession) {
    await revokeSshSession(currentSession).catch(() => {})
  }
}

async function connectSsh() {
  const generation = ++connectionGeneration
  await disconnectSsh(false)
  if (generation !== connectionGeneration || activeTab.value !== 'ssh') return
  sshStatus.value = 'connecting'
  sshError.value = ''
  terminal.value?.clear()
  terminal.value?.reset()
  try {
    await nextTick()
    if (generation !== connectionGeneration || activeTab.value !== 'ssh') return
    resizeTerminal()
    const cols = terminal.value?.cols || 80
    const rows = terminal.value?.rows || 24
    const data = await createSshSession(activeNode.value, cols, rows)
    const sessionId = data.sessionId
    if (!sessionId) throw new Error('后端未返回终端会话')
    if (generation !== connectionGeneration || !terminal.value) {
      await revokeSshSession(sessionId).catch(() => {})
      return
    }
    sshSessionId.value = sessionId
    const endpoint = getWebSocketUrl(data.websocketUrl)
    const nextSocket = new WebSocket(endpoint)
    socket.value = nextSocket
    nextSocket.onopen = () => {
      if (socket.value !== nextSocket || generation !== connectionGeneration) return
      sshStatus.value = 'connected'
      startSshHeartbeat(nextSocket, generation)
      writeTerminal(`\r\n已连接 ${activeNodeInfo.value.name} (${activeNodeInfo.value.host})\r\n`)
      resizeTerminal()
    }
    nextSocket.onmessage = (event) => {
      if (socket.value === nextSocket && generation === connectionGeneration) handleSocketMessage(event)
    }
    nextSocket.onerror = () => {
      if (socket.value !== nextSocket || generation !== connectionGeneration) return
      sshStatus.value = 'error'
      sshError.value = '终端连接失败，请检查运维服务和目标节点'
    }
    nextSocket.onclose = () => {
      if (socket.value !== nextSocket || generation !== connectionGeneration) return
      stopSshHeartbeat()
      socket.value = null
      sshSessionId.value = ''
      if (sshStatus.value === 'connected' || sshStatus.value === 'connecting') {
        sshStatus.value = 'closed'
      }
    }
  } catch (error) {
    if (generation !== connectionGeneration) return
    sshStatus.value = 'error'
    sshError.value = error?.message || '终端连接失败'
  }
}

function setActiveNode(nodeId) {
  if (activeNode.value === nodeId) return
  activeNode.value = nodeId
  if (isSshConnected.value || sshStatus.value === 'connecting') connectSsh()
}

async function pasteFromClipboard() {
  try {
    sendInput(await navigator.clipboard.readText())
  } catch {
    ElMessage.info('浏览器未允许读取剪贴板')
  }
}

function toggleTerminalFullScreen() {
  toggleFullScreen(terminalHost.value, terminalFullScreen)
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

function openActiveTab() {
  if (activeTab.value === 'ssh') {
    openTabOnce('ssh')
    nextTick(() => {
      if (activeTab.value !== 'ssh' || !terminalHost.value) return
      if (!terminal.value) createTerminal()
      resizeTerminal()
    })
  } else if (isAiCliTab.value || activeTab.value === 'database') {
    openTabOnce(activeTab.value)
  } else {
    openTabOnce(activeTab.value)
    loadConsole(activeTab.value)
  }
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
  resizeObserver = new ResizeObserver(resizeTerminal)
  resizeObserver.observe(terminalHost.value)
  nextTick(resizeTerminal)
}

watch(activeTab, (tab, previous) => {
  openActiveTab()
  if (tab === 'ssh' && previous !== 'ssh') nextTick(resizeTerminal)
})

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullScreenState)
  openActiveTab()
})

onBeforeUnmount(() => {
  embeddedTabs.forEach((tab) => cancelConsoleLoad(tab.key))
  releaseQbittorrentParentBridge()
  document.removeEventListener('fullscreenchange', syncFullScreenState)
  resizeObserver?.disconnect()
  stopSshHeartbeat()
  disconnectSsh()
  terminal.value?.dispose()
  terminal.value = null
})
</script>

<template>
  <section class="ops-page">
    <div class="ops-toolbar">
      <div class="ops-heading">
        <div class="ops-heading-icon"><Monitor /></div>
        <div>
          <h2>运维中心</h2>
          <p>仅 ADMIN 可访问 · 连接目标与操作记录由运维服务管理</p>
        </div>
      </div>
      <el-tag type="warning" effect="plain">ADMIN</el-tag>
    </div>

    <div class="ops-tabs" role="tablist" aria-label="运维功能">
      <button
          v-for="tab in tabs"
          :key="tab.key"
          class="ops-tab"
          :id="`ops-tab-${tab.key}`"
          :class="{ active: activeTab === tab.key }"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.key"
          :aria-controls="activeTab === tab.key ? `ops-panel-${tab.key}` : undefined"
          @click="activeTab = tab.key">
        <span>{{ tab.label }}</span>
        <small>{{ tab.hint }}</small>
      </button>
    </div>

    <div v-if="isTabOpened('database')" v-show="activeTab === 'database'" id="ops-panel-database" class="ops-database-panel" role="tabpanel" aria-labelledby="ops-tab-database">
      <div class="ops-panel-bar">
        <div class="ops-panel-status"><Connection /> 数据库</div>
      </div>
      <DatabaseManagement />
    </div>

    <template v-for="tab in tabs.filter((item) => item.tool)" :key="tab.key">
      <div v-if="isTabOpened(tab.key)" v-show="activeTab === tab.key" :id="`ops-panel-${tab.key}`" class="ops-ai-cli-panel" role="tabpanel" :aria-labelledby="`ops-tab-${tab.key}`">
        <div class="ops-panel-bar">
          <div class="ops-panel-status"><Connection /> {{ tab.label }}</div>
        </div>
        <AiCliTerminal :tool="tab.tool" :label="tab.label" :visible="activeTab === tab.key" />
      </div>
    </template>

    <template v-for="tab in embeddedTabs" :key="tab.key">
      <div v-if="isTabOpened(tab.key)" v-show="activeTab === tab.key" :id="`ops-panel-${tab.key}`" class="ops-console-panel" role="tabpanel" :aria-labelledby="`ops-tab-${tab.key}`">
      <div class="ops-panel-bar">
        <div class="ops-panel-status"><Connection /> {{ tab.label }}</div>
        <div class="ops-panel-actions">
          <el-button text :icon="Refresh" :disabled="consoleStates[tab.key].loading" @click="reloadConsole">刷新</el-button>
          <el-button text :icon="FullScreen" @click="setConsoleFullScreen">全屏</el-button>
        </div>
      </div>
      <div class="ops-frame-wrap">
        <iframe
            v-if="consoleStates[tab.key].url && !consoleStates[tab.key].error"
            :ref="(element) => setConsoleFrame(tab.key, element)"
            :name="consoleStates[tab.key].frameName"
            class="ops-console-frame"
            src="about:blank"
            :title="tab.label"
            allow="clipboard-read; clipboard-write"
            @load="handleConsoleFrameLoad(tab.key, $event)"
            @error="handleConsoleFrameError(tab.key)" />
        <div v-if="consoleStates[tab.key].loading" class="ops-loading">正在建立运维控制台会话…</div>
        <div v-else-if="consoleStates[tab.key].error" class="ops-empty">
          <WarningFilled />
          <p>{{ consoleStates[tab.key].error }}</p>
          <el-button type="primary" @click="loadConsole(tab.key, true)">重试</el-button>
        </div>
      </div>
    </div>
    </template>

    <div v-show="activeTab === 'ssh'" id="ops-panel-ssh" class="ops-terminal-panel" role="tabpanel" aria-labelledby="ops-tab-ssh">
      <div class="ops-panel-bar ops-terminal-bar">
        <div class="ops-node-switcher" aria-label="SSH 节点">
          <button
              v-for="node in nodes"
              :key="node.id"
              type="button"
              class="ops-node-button"
              :class="{ active: activeNode === node.id }"
              :aria-pressed="activeNode === node.id"
              @click="setActiveNode(node.id)">
            {{ node.name }} <small>{{ node.host }}</small>
          </button>
        </div>
        <div class="ops-terminal-actions">
          <span class="ops-connection-state" :class="sshStatus">{{ statusText }}</span>
          <el-button v-if="!isSshConnected" type="primary" size="small" @click="connectSsh">连接</el-button>
          <el-button v-else size="small" @click="disconnectSsh">断开</el-button>
          <el-button text :icon="FullScreen" @click="toggleTerminalFullScreen">全屏</el-button>
        </div>
      </div>

      <div ref="terminalHost" class="ops-terminal-host" @click="terminal?.focus()"></div>
      <div class="ops-terminal-footer">
        <div class="ops-assist-keys">
          <button type="button" :class="{ active: ctrlPending }" @click="assistKey('ctrl')">Ctrl</button>
          <button type="button" @click="assistKey('esc')">Esc</button>
          <button type="button" @click="assistKey('tab')">Tab</button>
          <button type="button" @click="assistKey('ctrl-c')">Ctrl+C</button>
          <button type="button" @click="assistKey('ctrl-d')">Ctrl+D</button>
          <button type="button" @click="assistKey('up')">↑</button>
          <button type="button" @click="assistKey('down')">↓</button>
          <button type="button" @click="assistKey('left')">←</button>
          <button type="button" @click="assistKey('right')">→</button>
          <button type="button" @click="pasteFromClipboard">粘贴</button>
        </div>
        <span class="ops-terminal-help">支持 vim、top、复制粘贴与窗口缩放</span>
      </div>
      <p v-if="sshError" class="ops-error">{{ sshError }}</p>
    </div>
  </section>
</template>

<style scoped>
.ops-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-height: 0;
  height: 100%;
  padding: var(--space-5) var(--space-6) var(--space-6);
  color: var(--color-text-primary);
}
.ops-toolbar,
.ops-panel-bar,
.ops-terminal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
.ops-heading { display: flex; align-items: center; gap: var(--space-3); min-width: 0; }
.ops-heading-icon {
  display: flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: var(--radius-md);
  background: var(--accent-soft); color: var(--accent);
}
.ops-heading-icon :deep(svg) { width: 22px; height: 22px; }
.ops-heading h2 { margin: 0; font-size: var(--font-size-xl); }
.ops-heading p { margin: 3px 0 0; color: var(--color-text-tertiary); font-size: var(--font-size-sm); }
.ops-tabs { display: flex; gap: var(--space-2); overflow-x: auto; }
.ops-tab {
  display: flex; flex-direction: column; align-items: flex-start; gap: 3px;
  min-width: 150px; padding: var(--space-3) var(--space-4);
  background: var(--color-bg-surface); border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md); color: var(--color-text-secondary); text-align: left;
}
.ops-tab small { color: var(--color-text-tertiary); font-size: var(--font-size-xs); }
.ops-tab.active { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
.ops-tab.active small { color: var(--accent-strong); }
.ops-console-panel,
.ops-database-panel,
.ops-ai-cli-panel,
.ops-terminal-panel {
  display: flex; flex-direction: column; min-height: 420px; flex: 1 1 0;
  overflow: hidden; background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.ops-ai-cli-panel { min-height: 560px; }
.ops-database-panel { min-height: 560px; }
.ops-database-panel :deep(.database-workspace) { border: 0; border-radius: 0; box-shadow: none; }
.ops-panel-bar { min-height: 52px; padding: 0 var(--space-4); border-bottom: 1px solid var(--color-border-subtle); flex-shrink: 0; }
.ops-panel-status { display: inline-flex; align-items: center; gap: var(--space-2); color: var(--color-text-secondary); font-size: var(--font-size-sm); }
.ops-panel-status :deep(svg) { width: 16px; color: var(--accent); }
.ops-panel-actions { display: inline-flex; align-items: center; gap: var(--space-1); }
.ops-frame-wrap { position: relative; flex: 1 1 0; min-height: 0; background: var(--color-bg-muted); overflow: auto; }
.ops-console-frame { display: block; width: 100%; height: 100%; min-height: 0; border: 0; background: #fff; }
.ops-loading,
.ops-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); min-height: 320px; color: var(--color-text-tertiary); }
.ops-loading { position: absolute; inset: 0; z-index: 1; background: color-mix(in srgb, var(--color-bg-muted) 88%, transparent); }
.ops-empty :deep(svg) { width: 28px; height: 28px; color: var(--color-warning); }
.ops-empty p { margin: 0; }
.ops-node-switcher { display: flex; gap: var(--space-2); overflow-x: auto; }
.ops-node-button { padding: var(--space-2) var(--space-3); color: var(--color-text-secondary); background: transparent; border-radius: var(--radius-md); text-align: left; white-space: nowrap; }
.ops-node-button small { color: var(--color-text-tertiary); margin-left: 4px; }
.ops-node-button.active { color: var(--accent); background: var(--accent-soft); }
.ops-terminal-actions { display: inline-flex; align-items: center; gap: var(--space-2); white-space: nowrap; }
.ops-connection-state { color: var(--color-text-tertiary); font-size: var(--font-size-xs); }
.ops-connection-state.connected { color: var(--color-success); }
.ops-connection-state.connecting { color: var(--color-warning); }
.ops-connection-state.error { color: var(--color-danger); }
.ops-terminal-host { flex: 1 1 0; min-height: 0; padding: var(--space-3); background: #111827; overflow: hidden; }
.ops-terminal-host :deep(.xterm) { height: 100%; }
.ops-terminal-host :deep(.xterm-viewport) { overflow-y: auto; }
.ops-terminal-footer { align-items: flex-start; padding: var(--space-3) var(--space-4); border-top: 1px solid var(--color-border-subtle); }
.ops-assist-keys { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.ops-assist-keys button { padding: 5px 9px; color: var(--color-text-secondary); background: var(--color-bg-muted); border: 1px solid var(--color-border-subtle); border-radius: var(--radius-sm); font-size: var(--font-size-xs); }
.ops-assist-keys button.active { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
.ops-terminal-help { color: var(--color-text-tertiary); font-size: var(--font-size-xs); text-align: right; }
.ops-error { margin: 0; padding: var(--space-2) var(--space-4); color: var(--color-danger); font-size: var(--font-size-sm); border-top: 1px solid var(--color-danger-soft); }

@media (max-width: 640px) {
  .ops-page { gap: var(--space-3); padding: var(--space-3) var(--space-3) var(--space-4); }
  .ops-heading h2 { font-size: var(--font-size-lg); }
  .ops-heading p { max-width: 230px; font-size: var(--font-size-xs); }
  .ops-heading-icon { width: 36px; height: 36px; }
  .ops-tabs { margin-right: calc(-1 * var(--space-3)); padding-right: var(--space-3); }
  .ops-tab { min-width: 132px; padding: var(--space-2) var(--space-3); }
  .ops-console-panel, .ops-database-panel, .ops-terminal-panel {
    flex: 0 0 auto;
    height: calc(100dvh - var(--layout-tab-bar-height) - 210px);
    min-height: 420px;
    border-radius: var(--radius-md);
  }
  .ops-ai-cli-panel {
    flex: 0 0 auto;
    height: calc(100dvh - var(--layout-tab-bar-height) - 210px);
    min-height: 420px;
    border-radius: var(--radius-md);
  }
  .ops-panel-bar { min-height: 48px; padding: 0 var(--space-3); }
  .ops-panel-actions :deep(.el-button), .ops-terminal-actions :deep(.el-button) {
    min-height: 44px;
    padding-left: 8px;
    padding-right: 8px;
  }
  .ops-console-frame { min-width: 900px; min-height: 0; }
  .ops-database-panel { min-height: 650px; }
  .ops-frame-wrap { overflow: auto; }
  .ops-terminal-bar { align-items: flex-start; flex-direction: column; padding: var(--space-2) var(--space-3); }
  .ops-terminal-actions { width: 100%; justify-content: space-between; }
  .ops-node-button { min-height: 44px; }
  .ops-terminal-host { min-height: 300px; padding: var(--space-2); }
  .ops-terminal-footer { flex-direction: column; padding: var(--space-2) var(--space-3); }
  .ops-terminal-help { text-align: left; }
  .ops-assist-keys button { min-width: 44px; min-height: 44px; padding: 8px 10px; }
}
</style>
