<script setup>
import { ref } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AiCliTerminal from './AiCliTerminal.vue'

const { isMobile, isDesktop } = useBreakpoint()
const activeTool = ref('CODEX')
const panes = [
  { tool: 'CODEX', label: 'Codex CLI' },
  { tool: 'CLAUDE', label: 'Claude Code' }
]
</script>

<template>
  <div class="ai-cli-workspace">
    <div v-if="isMobile" class="ai-cli-mobile-switch" role="tablist" aria-label="AI CLI 工具">
      <button
          v-for="pane in panes"
          :key="pane.tool"
          type="button"
          role="tab"
          :id="`ai-cli-tab-${pane.tool.toLowerCase()}`"
          :aria-selected="activeTool === pane.tool"
          :aria-controls="`ai-cli-panel-${pane.tool.toLowerCase()}`"
          :class="{ active: activeTool === pane.tool }"
          @click="activeTool = pane.tool">
        {{ pane.label }}
      </button>
    </div>

    <div class="ai-cli-grid">
      <div
          v-for="pane in panes"
          :key="pane.tool"
          :id="`ai-cli-panel-${pane.tool.toLowerCase()}`"
          class="ai-cli-pane"
          :role="isMobile ? 'tabpanel' : undefined"
          :aria-labelledby="isMobile ? `ai-cli-tab-${pane.tool.toLowerCase()}` : undefined"
          :tabindex="isMobile && activeTool !== pane.tool ? -1 : undefined"
          :class="{ 'ai-cli-pane-hidden-mobile': isMobile && activeTool !== pane.tool }">
        <AiCliTerminal :tool="pane.tool" :label="pane.label" :visible="isDesktop || activeTool === pane.tool" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-cli-workspace {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--color-bg-muted);
}
.ai-cli-grid {
  display: grid;
  flex: 1 1 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-height: 0;
  gap: var(--space-3);
}
.ai-cli-pane { display: flex; min-width: 0; min-height: 0; }
.ai-cli-pane :deep(.ai-cli-terminal) { flex: 1 1 0; }
.ai-cli-mobile-switch { display: none; }

@media (max-width: 640px) {
  .ai-cli-workspace { padding: var(--space-2); gap: var(--space-2); }
  .ai-cli-mobile-switch {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 2px;
    padding: 3px;
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
  }
  .ai-cli-mobile-switch button {
    min-height: 44px;
    color: var(--color-text-secondary);
    background: transparent;
    border-radius: var(--radius-sm);
    font-size: var(--font-size-sm);
  }
  .ai-cli-mobile-switch button.active { color: var(--accent); background: var(--accent-soft); font-weight: var(--font-weight-medium); }
  .ai-cli-grid { display: flex; flex: 1 1 0; min-height: 0; }
  .ai-cli-pane { flex: 1 1 0; }
  .ai-cli-pane-hidden-mobile { display: none; }
}
</style>
