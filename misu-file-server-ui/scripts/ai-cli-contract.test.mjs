import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const api = readFileSync(resolve(root, 'src/api/ops/ops.js'), 'utf8')
const terminal = readFileSync(resolve(root, 'src/views/ops/AiCliTerminal.vue'), 'utf8')
const opsConsole = readFileSync(resolve(root, 'src/views/ops/OpsConsole.vue'), 'utf8')

assert.match(api, /url: '\/ops\/api\/ai\/sessions'/)
assert.match(api, /data: \{ tool, cols, rows \}/)
assert.match(api, /url: `\/ops\/api\/ai\/sessions\/\$\{encodeURIComponent\(sessionId\)\}`/)
assert.match(terminal, /send\(\{ type: 'input', data \}\)/)
assert.match(terminal, /send\(\{ type: 'resize', cols: terminal\.value\.cols, rows: terminal\.value\.rows \}\)/)
assert.match(terminal, /sessionLifecycle\.close\(nextSocket, nextSessionId, currentGeneration\)/)
assert.match(terminal, /result\.current && isCurrentAiCliSocket\(nextSocket, socket\.value, currentGeneration, generation\)/)
assert.match(terminal, /sessionLifecycle\.revokeOnce\(nextSessionId\)/)
assert.match(terminal, /receivedOutput\.value = true/)
assert.match(terminal, /getAiCliExitError\(props\.tool, receivedOutput\.value\)/)
for (const forbidden of ['startupCommand', 'argv', 'cwd', 'apiKey']) {
  assert.doesNotMatch(terminal, new RegExp(forbidden), `浏览器端不得携带 ${forbidden}`)
}
assert.match(opsConsole, /key: 'codex-cli', tool: 'CODEX', label: 'Codex CLI'/)
assert.match(opsConsole, /key: 'claude-code', tool: 'CLAUDE', label: 'Claude Code'/)
assert.match(opsConsole, /const isAiCliTab = computed\(\(\) => Boolean\(activeTabInfo\.value\?\.tool\)\)/)
assert.match(opsConsole, /<AiCliTerminal :key="activeTab" :tool="activeTabInfo\.tool" :label="activeTabInfo\.label" \/>/)
assert.doesNotMatch(opsConsole, /AiCliWorkspace|ai-cli-grid|ai-cli-mobile-switch|ai-cli-pane-hidden-mobile/)

console.log('AI CLI frontend contract checks passed')
