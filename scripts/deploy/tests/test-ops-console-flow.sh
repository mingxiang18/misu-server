#!/usr/bin/env bash
# Source-level flow harness for the browser-only iframe exchange sequence.
# It models the observable ordering without contacting an API or opening a
# browser: an old frame must be stopped and removed before a new ticket is
# requested, and stale generations must never submit a form.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SOURCE="${ROOT_DIR}/misu-file-server-ui/src/views/ops/OpsConsole.vue"
DB_SOURCE="${ROOT_DIR}/misu-file-server-ui/src/views/ops/DatabaseManagement.vue"

python3 - "${SOURCE}" "${DB_SOURCE}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
db_source = Path(sys.argv[2]).read_text()
load = source[source.index('async function loadConsole'):source.index('\nfunction reloadConsole')]
def assert_order(text, *needles):
    cursor = -1
    for needle in needles:
        cursor = text.index(needle, cursor + 1)

assert_order(load,
             'releaseQbittorrentParentBridge()',
             'stopConsoleFrame()',
             "consoleUrl.value = ''",
             'await nextTick()',
             'issueConsoleTicket(consoleTarget)')
assert_order(load,
             'await nextTick()',
             'if (generation !== consoleGeneration || activeTab.value !== target) return',
             'issueConsoleTicket(consoleTarget)')
assert 'consoleFrameName.value = `ops-console-' in source
assert_order(load,
             'form.target = consoleFrameName.value',
             'consoleFrameNavigationStarted.value = true',
             'form.submit()')
assert 'function handleConsoleFrameLoad(event)' in source
assert 'function handleConsoleFrameError()' in source
assert '@load="handleConsoleFrameLoad"' in source
assert '@error="handleConsoleFrameError"' in source
assert 'releaseQbittorrentParentBridge()' in source
assert 'class="ops-node-switcher" aria-label="SSH 节点"' in source
assert ':aria-pressed="activeNode === node.id"' in source
assert 'document.addEventListener(\'fullscreenchange\', syncFullScreenState)' in source
assert ':aria-controls="activeTab === tab.key ? `ops-panel-${tab.key}` : undefined"' in source
assert 'min-height: 0' in source[source.index('.ops-console-frame'):source.index('.ops-loading')]
assert "if (tab === 'ssh' || tab === 'database') cancelConsoleLoad()" in source
assert 'onBeforeUnmount(() => {\n  cancelConsoleLoad()' in source

# Simulate the two relevant generations.  A cancellation between the first
# render tick and the API response must suppress the old form submission.
events = []
generation = 1
events += ['stop-old-frame', 'remove-old-frame', 'issue-ticket:g1']
generation = 2
events += ['stop-old-frame', 'remove-old-frame', 'issue-ticket:g2']
assert events.index('stop-old-frame') < events.index('issue-ticket:g1')
assert events.index('stop-old-frame', events.index('issue-ticket:g1') + 1) < events.index('issue-ticket:g2')
assert generation != 1, 'stale generation must be invalidated before submit'
print('console iframe stop/remove, generation guard, and form target ordering: PASS')

assert ':aria-controls="view === \'data\' ? \'database-data-view\' : undefined"' in db_source
assert ':aria-controls="view === \'schema\' ? \'database-schema-view\' : undefined"' in db_source
assert 'class="database-dialog"' in db_source
assert 'aria-labelledby="database-view-data-tab"' in db_source
assert 'aria-labelledby="database-view-schema-tab"' in db_source
assert 'width:calc(100vw - 24px)' in db_source
assert 'min-height: 44px' in db_source
assert 'class="database-title-name"' in db_source
print('database mobile dialog, touch target, truncation, and ARIA contracts: PASS')
PY
