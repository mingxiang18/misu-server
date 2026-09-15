#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
INSTALLER="${ROOT_DIR}/install-worker.sh"
tmp_root="$(mktemp -d /tmp/misu-ai-cli-contract.XXXXXX)"
tmp_wrapper="${tmp_root}/raw-wrapper"
trap 'rm -rf -- "$tmp_root"' EXIT

fail() { printf 'ai-cli contract: %s\n' "$*" >&2; exit 1; }

bash -n "$INSTALLER" || fail "installer has invalid shell syntax"
grep -Fq 'readonly NODE_VERSION="24.21.0"' "$INSTALLER" || fail "Node pin changed"
grep -Fq 'readonly CODEX_VERSION_PIN="0.154.0"' "$INSTALLER" || fail "Codex pin changed"
grep -Fq 'readonly CLAUDE_VERSION_PIN="2.1.270"' "$INSTALLER" || fail "Claude Code pin changed"
grep -Fq 'npm" install --prefix "$CLI_ROOT" --package-lock-only' "$INSTALLER" || fail "first install must create a lockfile"
grep -Fq 'npm" ci --prefix "$CLI_ROOT"' "$INSTALLER" || fail "install must use npm ci"
grep -Fq 'npm" ci --prefix "$CLI_ROOT" --omit=dev --include=optional --ignore-scripts' "$INSTALLER" || fail "npm ci must disable lifecycle scripts"
grep -Fq -- '--include=optional' "$INSTALLER" || fail "native optional dependency must be included"
grep -Fq 'env PATH="$CONTROLLED_PATH" "$npm"' "$INSTALLER" || fail "npm does not use controlled PATH"
grep -Fq 'export PATH="$CONTROLLED_PATH"' "$INSTALLER" || fail "wrapper does not use controlled PATH"
grep -Fq 'local installer="${claude_package}/install.cjs"' "$INSTALLER" || fail "fixed Claude installer is not selected"
grep -Fq 'install_claude_native' "$INSTALLER" || fail "Claude native installer is not invoked"
grep -Fq 'readonly WORKSPACE="${APP_ROOT}/workspace"' "$INSTALLER" || fail "workspace path changed"
grep -Fq 'readonly WRAPPER="/usr/local/bin/misu-ai-cli"' "$INSTALLER" || fail "wrapper path changed"
grep -Fq 'ANTHROPIC_BASE_URL' "$INSTALLER" || fail "Anthropic base URL wiring missing"
grep -Fq 'ANTHROPIC_AUTH_TOKEN' "$INSTALLER" || fail "Anthropic auth token wiring missing"
for model_env in ANTHROPIC_DEFAULT_MODEL ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL ANTHROPIC_DEFAULT_HAIKU_MODEL CLAUDE_CODE_SUBAGENT_MODEL; do
  grep -Fq "${model_env}=\"deepseek-v4-pro\"" "$INSTALLER" || fail "Claude ${model_env} wiring missing"
done
grep -Fq 'readonly CODEX_HTTP_PROXY="http://127.0.0.1:7890"' "$INSTALLER" || fail "Codex Clash HTTP proxy pin changed"
grep -Fq 'readonly CODEX_NO_PROXY="localhost,127.0.0.1,::1,10.8.0.1,10.8.0.26,192.168.50.227,.svc,.cluster.local"' "$INSTALLER" || fail "Codex internal NO_PROXY pin changed"

awk 'index($0, "cat > \"$wrapper_tmp\"") { capture=1; next }
     capture && /^WRAPPER$/ { exit }
     capture { print }' "$INSTALLER" > "$tmp_wrapper"
[[ -s "$tmp_wrapper" ]] || fail "wrapper body was not found"
bash -n "$tmp_wrapper" || fail "generated wrapper has invalid shell syntax"
grep -Fq 'tool must be exactly codex or claude' "$tmp_wrapper" || fail "tool allowlist changed"
grep -Fq 'only no arguments or the exact --version argument is allowed' "$tmp_wrapper" || fail "wrapper argument contract changed"
grep -Fq 'IFS= read -r ANTHROPIC_BASE_URL' "$tmp_wrapper" || fail "base URL is not read literally"
grep -Fq 'IFS= read -r ANTHROPIC_AUTH_TOKEN' "$tmp_wrapper" || fail "auth token is not read literally"

checksum_function="${tmp_root}/checksum-function.sh"
awk 'capture && /^}/ { print; exit }
     /^verify_sha256\(\) \{/ { capture=1 }
     capture { print }' "$INSTALLER" > "$checksum_function"
[[ -s "$checksum_function" ]] || fail "checksum function was not found"
printf '%s\n' 'verify_sha256 "$1" "$2"' >> "$checksum_function"
checksum_dir="${tmp_root}/checksum"
mkdir -p "$checksum_dir"
printf '%s\n' 'verified payload' > "${checksum_dir}/sample.tar.xz"
digest="$(sha256sum "${checksum_dir}/sample.tar.xz" | awk '{print $1}')"
printf '%s  %s\n' "$digest" 'sample.tar.xz' > "${checksum_dir}/SHASUMS256.txt"
bash "$checksum_function" "$checksum_dir" 'sample.tar.xz' >/dev/null || fail "valid checksum was rejected"
printf '%s\n' 'tampered payload' > "${checksum_dir}/sample.tar.xz"
if bash "$checksum_function" "$checksum_dir" 'sample.tar.xz' >/dev/null 2>&1; then
  fail "invalid checksum was accepted"
fi

native_function="${tmp_root}/native-function.sh"
awk 'capture && /^}/ { print; exit }
     /^install_claude_native\(\) \{/ { capture=1 }
     capture { print }' "$INSTALLER" > "$native_function"
[[ -s "$native_function" ]] || fail "native installer function was not found"
native_root="${tmp_root}/native"
native_package="${native_root}/cli/node_modules/@anthropic-ai/claude-code"
mkdir -p "${native_package}/bin" "${native_root}/cli/node-current/bin"
printf '%s\n' '{"version":"2.1.270"}' > "${native_package}/package.json"
: > "${native_package}/install.cjs"
native_node="${native_root}/cli/node-current/bin/node"
printf '%s\n' '#!/usr/bin/env bash' \
  'if [[ "$1" == "-" ]]; then exit 0; fi' \
  '[[ "$1" == */install.cjs ]] || exit 1' \
  'printf "%s\\n" "native-stub" > "${native_root}/cli/node_modules/@anthropic-ai/claude-code/bin/claude.exe"' \
  'chmod 0755 "${native_root}/cli/node_modules/@anthropic-ai/claude-code/bin/claude.exe"' > "$native_node"
chmod 0755 "$native_node"
export native_root
cat >> "$native_function" <<EOF
readonly CLI_ROOT="${native_root}/cli"
readonly NODE_CURRENT="${native_root}/cli/node-current"
readonly CONTROLLED_PATH="\${NODE_CURRENT}/bin:/usr/bin:/bin"
claude_version="2.1.270"
die() { exit 1; }
install_claude_native
EOF
bash "$native_function" || fail "fixed Claude install.cjs was not invoked successfully"
[[ -x "${native_package}/bin/claude.exe" ]] || fail "native binary was not placed"

runtime_root="${tmp_root}/runtime"
mkdir -p "${runtime_root}/cli/node-current/bin" "${runtime_root}/cli/node_modules/.bin" "${runtime_root}/workspace" "${runtime_root}/config"
node_stub="${runtime_root}/cli/node-current/bin/node"
printf '%s\n' '#!/usr/bin/env bash' 'script="$1"; shift' 'printf "stub:%s\\n" "$*" >> "$MISU_STUB_LOG"' > "$node_stub"
chmod 0755 "$node_stub"
stat_stub="${runtime_root}/cli/node-current/bin/stat"
printf '%s\n' '#!/usr/bin/env bash' '[[ "$1" == "-c" && "$2" == "%a" ]] || exit 1' 'printf "%s\\n" 600' > "$stat_stub"
chmod 0755 "$stat_stub"
stub="${runtime_root}/cli/node_modules/.bin/claude"
printf '%s\n' '#!/usr/bin/env bash' \
  'printf "claude-env:%s|%s|%s|%s|%s|%s|%s|%s\\n" "${ANTHROPIC_BASE_URL-}" "${ANTHROPIC_MODEL-}" "${ANTHROPIC_DEFAULT_MODEL-}" "${ANTHROPIC_DEFAULT_OPUS_MODEL-}" "${ANTHROPIC_DEFAULT_SONNET_MODEL-}" "${ANTHROPIC_DEFAULT_HAIKU_MODEL-}" "${CLAUDE_CODE_SUBAGENT_MODEL-}" "$*" >> "$MISU_STUB_LOG"' > "$stub"
chmod 0755 "$stub"
codex_stub="${runtime_root}/cli/node_modules/.bin/codex"
printf '%s\n' '#!/usr/bin/env bash' \
  'printf "codex-env:%s|%s|%s|%s|%s|%s|%s|%s\\n" "${HTTP_PROXY-}" "${HTTPS_PROXY-}" "${ALL_PROXY-}" "${NO_PROXY-}" "${http_proxy-}" "${https_proxy-}" "${all_proxy-}" "${no_proxy-}" >> "$MISU_STUB_LOG"' > "$codex_stub"
chmod 0755 "$codex_stub"
: > "${runtime_root}/config/anthropic-base-url"
: > "${runtime_root}/config/anthropic-auth-token"
chmod 0600 "${runtime_root}/config/anthropic-base-url" "${runtime_root}/config/anthropic-auth-token"
sed \
  -e "s#/srv/misu-ai-cli/cli#${runtime_root}/cli#" \
  -e "s#/srv/misu-ai-cli/workspace#${runtime_root}/workspace#" \
  -e "s#/opt/misu-ai-cli/node-current#${runtime_root}/cli/node-current#" \
  -e "s#/etc/misu-ai-cli/anthropic-base-url#${runtime_root}/config/anthropic-base-url#" \
  -e "s#/etc/misu-ai-cli/anthropic-auth-token#${runtime_root}/config/anthropic-auth-token#" \
  "$tmp_wrapper" | grep -v 'must run as root' > "${runtime_root}/wrapper"
chmod 0755 "${runtime_root}/wrapper"
bash -n "${runtime_root}/wrapper" || fail "runtime wrapper has invalid shell syntax"
stub_log="${runtime_root}/stub.log"
export MISU_STUB_LOG="$stub_log"

if ! "${runtime_root}/wrapper" claude --version >/dev/null 2>&1; then
  fail "claude --version should bypass empty credentials"
fi
grep -Fq 'claude-env:|||||||--version' "$stub_log" || fail "claude --version did not execute"
: > "$stub_log"
if ! env HTTP_PROXY=http://inherited.invalid HTTPS_PROXY=http://inherited.invalid ALL_PROXY=http://inherited.invalid NO_PROXY=inherited.invalid \
  http_proxy=http://inherited.invalid https_proxy=http://inherited.invalid all_proxy=http://inherited.invalid no_proxy=inherited.invalid \
  "${runtime_root}/wrapper" codex >/dev/null 2>&1; then
  fail "codex interactive mode failed"
fi
grep -Fq 'codex-env:http://127.0.0.1:7890|http://127.0.0.1:7890|http://127.0.0.1:7890|localhost,127.0.0.1,::1,10.8.0.1,10.8.0.26,192.168.50.227,.svc,.cluster.local|http://127.0.0.1:7890|http://127.0.0.1:7890|http://127.0.0.1:7890|localhost,127.0.0.1,::1,10.8.0.1,10.8.0.26,192.168.50.227,.svc,.cluster.local' "$stub_log" \
  || fail "codex did not receive the worker-local Clash proxy"
: > "$stub_log"
if "${runtime_root}/wrapper" claude >/dev/null 2>&1; then
  fail "claude interactive mode accepted empty credentials"
fi
[[ ! -s "$stub_log" ]] || fail "claude interactive mode ran with empty credentials"
printf '%s\n' 'https://api.deepseek.com/anthropic' > "${runtime_root}/config/anthropic-base-url"
printf '%s\n' 'fixture-token' > "${runtime_root}/config/anthropic-auth-token"
if ! env \
  ANTHROPIC_MODEL=inherited-model \
  ANTHROPIC_DEFAULT_OPUS_MODEL=inherited-model \
  ANTHROPIC_DEFAULT_SONNET_MODEL=inherited-model \
  ANTHROPIC_DEFAULT_HAIKU_MODEL=inherited-model \
  CLAUDE_CODE_SUBAGENT_MODEL=inherited-model \
  "${runtime_root}/wrapper" claude >/dev/null 2>&1; then
  fail "claude interactive mode failed with configured credentials"
fi
grep -Fq 'claude-env:https://api.deepseek.com/anthropic||deepseek-v4-pro|deepseek-v4-pro|deepseek-v4-pro|deepseek-v4-pro|deepseek-v4-pro|' "$stub_log" \
  || fail "claude did not receive the default DeepSeek model environment"
: > "$stub_log"
if "${runtime_root}/wrapper" claude --help >/dev/null 2>&1; then
  fail "wrapper accepted an unsupported CLI argument"
fi
if "${runtime_root}/wrapper" sh >/dev/null 2>&1; then
  fail "wrapper accepted an unsupported tool"
fi

if grep -En '(^|[;&|][[:space:]]*)source[[:space:]]|(^|[;&|][[:space:]]*)eval[[:space:]]' "$INSTALLER" "$tmp_wrapper"; then
  fail "source/eval is forbidden"
fi
if grep -Ein '/var/lib/kubelet|/var/lib/containerd|kubectl|qbit|mysql|(^|[[:space:]])mount([[:space:]]|$)' "$INSTALLER"; then
  fail "installer references protected runtime or data paths"
fi

printf '%s\n' 'ai-cli contract: ok'
