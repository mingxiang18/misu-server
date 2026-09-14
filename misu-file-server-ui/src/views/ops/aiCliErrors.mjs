export function getAiCliExitError(tool, receivedOutput) {
  if (receivedOutput) {
    return `${tool === 'CLAUDE' ? 'Claude Code' : 'Codex CLI'} 已退出，请重新连接`
  }
  if (tool === 'CLAUDE') {
    return 'Claude Code 已退出，请检查第三方 API URL 和 API Key 配置'
  }
  return 'Codex CLI 已退出，请检查 CLI 配置或认证状态'
}
