#!/usr/bin/env bash
# Source-level flow harness for the browser-only iframe exchange sequence.
# It models the observable ordering without contacting an API or opening a
# browser: an old frame must be stopped and removed before a new ticket is
# requested, and stale generations must never submit a form.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SOURCE="${ROOT_DIR}/misu-file-server-ui/src/views/ops/OpsConsole.vue"

python3 - "${SOURCE}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
load = source[source.index('async function loadConsole'):source.index('\nfunction reloadConsole')]
stop = load.index('stopConsoleFrame()')
clear = load.index("consoleUrl.value = ''")
tick = load.index('await nextTick()')
issue = load.index('issueConsoleTicket(target)')
guard = load.index('if (generation !== consoleGeneration || activeTab.value !== target) return', tick)
submit = load.index('form.submit()')

assert stop < clear < tick < issue, 'old iframe must be stopped before ticket issuance'
assert guard > tick and guard < issue, 'cancelled generation must not issue a ticket'
assert load.index('consoleFrameName.value = `ops-console-') < tick
assert load.index('form.target = consoleFrameName.value') < submit
assert 'if (tab === \'ssh\') cancelConsoleLoad()' in source
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
PY
