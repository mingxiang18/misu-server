#!/usr/bin/env bash
# Root-only, idempotent worker installer with exact release pins.
#
#   bash install-worker.sh \
#     --anthropic-base-url-file /root/secrets/anthropic-base-url \
#     --anthropic-auth-token-file /root/secrets/anthropic-auth-token
#
# Real credentials and unreviewed versions stay outside this repository.

set -Eeuo pipefail
IFS=$'\n\t'

readonly NODE_VERSION="24.21.0"
readonly CODEX_VERSION_PIN="0.154.0"
readonly CLAUDE_VERSION_PIN="2.1.270"
readonly PREFIX="/opt/misu-ai-cli"
readonly NODE_DIR="${PREFIX}/node-v${NODE_VERSION}"
readonly NODE_CURRENT="${PREFIX}/node-current"
readonly CONTROLLED_PATH="${NODE_CURRENT}/bin:/usr/bin:/bin"
readonly APP_ROOT="/srv/misu-ai-cli"
readonly CLI_ROOT="${APP_ROOT}/cli"
readonly WORKSPACE="${APP_ROOT}/workspace"
readonly CONFIG_ROOT="/etc/misu-ai-cli"
readonly BASE_URL_FILE="${CONFIG_ROOT}/anthropic-base-url"
readonly AUTH_TOKEN_FILE="${CONFIG_ROOT}/anthropic-auth-token"
readonly WRAPPER="/usr/local/bin/misu-ai-cli"

codex_version="$CODEX_VERSION_PIN"
claude_version="$CLAUDE_VERSION_PIN"
base_url_source=""
auth_token_source=""

die() { printf 'misu-ai-cli install: %s\n' "$*" >&2; exit 1; }

while (($# > 0)); do
  case "$1" in
    --codex-version) (($# >= 2)) || die "--codex-version requires a value"; [[ "$2" == "$CODEX_VERSION_PIN" ]] || die "Codex version is pinned to ${CODEX_VERSION_PIN}"; shift 2 ;;
    --claude-version) (($# >= 2)) || die "--claude-version requires a value"; [[ "$2" == "$CLAUDE_VERSION_PIN" ]] || die "Claude Code version is pinned to ${CLAUDE_VERSION_PIN}"; shift 2 ;;
    --anthropic-base-url-file) (($# >= 2)) || die "--anthropic-base-url-file requires a path"; base_url_source="$2"; shift 2 ;;
    --anthropic-auth-token-file) (($# >= 2)) || die "--anthropic-auth-token-file requires a path"; auth_token_source="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,7p' "$0"
      printf '%s\n' 'Usage: install-worker.sh [--codex-version 0.154.0] [--claude-version 2.1.270] [--anthropic-base-url-file PATH] [--anthropic-auth-token-file PATH]'
      exit 0
      ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ "$(id -u)" == 0 ]] || die "must run as root"
[[ "$codex_version" == "$CODEX_VERSION_PIN" ]] || die "Codex version pin changed unexpectedly"
[[ "$claude_version" == "$CLAUDE_VERSION_PIN" ]] || die "Claude Code version pin changed unexpectedly"

for required in curl sha256sum tar install mv cmp mktemp; do
  command -v "$required" >/dev/null 2>&1 || die "missing required command: $required"
done

tmp_root="$(mktemp -d /tmp/misu-ai-cli.XXXXXX)"
trap 'rm -rf -- "$tmp_root"' EXIT

verify_sha256() {
  local download_dir="$1" archive="$2"
  (
    cd -- "$download_dir"
    awk -v name="$archive" '$2 == name { print }' SHASUMS256.txt | sha256sum -c -
  )
}

install_node() {
  local node_bin="${NODE_CURRENT}/bin/node"
  if [[ -x "$node_bin" ]]; then
    [[ "$("$node_bin" --version)" == "v${NODE_VERSION}" ]] || die "unexpected Node version"
  elif [[ -e "$NODE_CURRENT" || -e "$NODE_DIR" ]]; then
    die "partial or unexpected pinned Node installation exists"
  else
    local archive="node-v${NODE_VERSION}-linux-x64.tar.xz"
    local download_dir="${tmp_root}/node"
    install -d -m 0755 -o root -g root "$PREFIX"
    install -d -m 0755 -o root -g root "$download_dir"
    curl --fail --silent --show-error --location "https://nodejs.org/dist/v${NODE_VERSION}/${archive}" -o "${download_dir}/${archive}"
    curl --fail --silent --show-error --location "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" -o "${download_dir}/SHASUMS256.txt"
    verify_sha256 "$download_dir" "$archive"
    tar -xJf "${download_dir}/${archive}" -C "$download_dir"
    [[ -x "${download_dir}/node-v${NODE_VERSION}-linux-x64/bin/node" ]] || die "unexpected Node archive layout"
    mv -- "${download_dir}/node-v${NODE_VERSION}-linux-x64" "$NODE_DIR"
    chown -R root:root "$NODE_DIR"
    chmod -R u=rwX,go=rX "$NODE_DIR"
    ln -s -- "$NODE_DIR" "$NODE_CURRENT"
  fi
  [[ -x "${NODE_CURRENT}/bin/npm" ]] || die "npm is missing from Node"
}

install_manifest() {
  local manifest="${CLI_ROOT}/package.json"
  if [[ -e "$manifest" ]]; then
    [[ -f "$manifest" && ! -L "$manifest" ]] || die "package.json is not a regular file"
    if ! env PATH="$CONTROLLED_PATH" "${NODE_CURRENT}/bin/node" - "$manifest" "$codex_version" "$claude_version" <<'NODE'
const fs = require('fs');
const [file, codex, claude] = process.argv.slice(2);
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
if (data.private !== true || !data.dependencies ||
    data.dependencies['@openai/codex'] !== codex ||
    data.dependencies['@anthropic-ai/claude-code'] !== claude) process.exit(1);
NODE
    then
      die "package.json has different pinned CLI versions"
    fi
    return
  fi
  local manifest_tmp="${tmp_root}/package.json"
  cat > "$manifest_tmp" <<EOF
{
  "name": "misu-ai-cli",
  "private": true,
  "dependencies": {
    "@openai/codex": "${codex_version}",
    "@anthropic-ai/claude-code": "${claude_version}"
  }
}
EOF
  install -m 0644 -o root -g root "$manifest_tmp" "$manifest"
}

install_cli() {
  install -d -m 0755 -o root -g root "$APP_ROOT" "$CLI_ROOT" "$WORKSPACE"
  install_manifest
  local npm="${NODE_CURRENT}/bin/npm"
  if [[ ! -f "${CLI_ROOT}/package-lock.json" ]]; then
    env PATH="$CONTROLLED_PATH" "$npm" install --prefix "$CLI_ROOT" --package-lock-only --ignore-scripts --no-audit --no-fund
  fi
  env PATH="$CONTROLLED_PATH" "$npm" ci --prefix "$CLI_ROOT" --omit=dev --include=optional --ignore-scripts --no-audit --no-fund
  [[ -x "${CLI_ROOT}/node_modules/.bin/codex" ]] || die "Codex binary was not installed"
  [[ -x "${CLI_ROOT}/node_modules/.bin/claude" ]] || die "Claude Code binary was not installed"
  install_claude_native
}

install_claude_native() {
  local claude_package="${CLI_ROOT}/node_modules/@anthropic-ai/claude-code"
  local installer="${claude_package}/install.cjs"
  local native_binary="${claude_package}/bin/claude.exe"
  [[ -f "$installer" && ! -L "$installer" ]] || die "Claude native installer is missing"
  [[ -f "${claude_package}/package.json" && ! -L "${claude_package}/package.json" ]] || die "Claude package metadata is missing"
  if ! env PATH="$CONTROLLED_PATH" "${NODE_CURRENT}/bin/node" - "${claude_package}/package.json" "$claude_version" <<'NODE'
const fs = require('fs');
const [file, expected] = process.argv.slice(2);
if (JSON.parse(fs.readFileSync(file, 'utf8')).version !== expected) process.exit(1);
NODE
  then
    die "Claude package version does not match the pin"
  fi
  env PATH="$CONTROLLED_PATH" "${NODE_CURRENT}/bin/node" "$installer"
  [[ -x "$native_binary" ]] || die "Claude native binary was not installed"
}

install_secret_file() {
  local source="$1" target="$2"
  [[ ! -L "$target" ]] || die "secret target must not be a symlink"
  if [[ -n "$source" ]]; then
    [[ -f "$source" && ! -L "$source" ]] || die "secret source is not a regular file"
    local incoming="${tmp_root}/$(basename "$target").incoming"
    install -m 0600 -o root -g root "$source" "$incoming"
    mv -f -- "$incoming" "$target"
  elif [[ ! -e "$target" ]]; then
    install -m 0600 -o root -g root /dev/null "$target"
  fi
  [[ -f "$target" && ! -L "$target" ]] || die "secret target is not a regular file"
  chown root:root "$target"
  chmod 0600 "$target"
}

write_wrapper() {
  local wrapper_tmp="${tmp_root}/misu-ai-cli"
  [[ ! -L "$WRAPPER" ]] || die "wrapper target must not be a symlink"
  cat > "$wrapper_tmp" <<'WRAPPER'
#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
readonly CLI_ROOT="/srv/misu-ai-cli/cli"
readonly WORKSPACE="/srv/misu-ai-cli/workspace"
readonly NODE_CURRENT="/opt/misu-ai-cli/node-current"
readonly CONTROLLED_PATH="${NODE_CURRENT}/bin:/usr/bin:/bin"
readonly BASE_URL_FILE="/etc/misu-ai-cli/anthropic-base-url"
readonly AUTH_TOKEN_FILE="/etc/misu-ai-cli/anthropic-auth-token"
readonly CODEX_HTTP_PROXY="http://127.0.0.1:7890"
readonly CODEX_NO_PROXY="localhost,127.0.0.1,::1,10.8.0.1,10.8.0.26,192.168.50.227,.svc,.cluster.local"
die() { printf 'misu-ai-cli: %s\n' "$*" >&2; exit 64; }
[[ "$(id -u)" == 0 ]] || die "must run as root"
(($# >= 1)) || die "choose exactly codex or claude"
tool="$1"; shift
case "$tool" in
  codex)
    executable="${CLI_ROOT}/node_modules/.bin/codex"
    # The worker's Clash HTTP port is local to this host. Set proxy variables
    # only for Codex so Claude and other SSH-launched workloads keep their
    # inherited environment unchanged, and never proxy loopback traffic.
    export HTTP_PROXY="$CODEX_HTTP_PROXY" HTTPS_PROXY="$CODEX_HTTP_PROXY" ALL_PROXY="$CODEX_HTTP_PROXY"
    export NO_PROXY="$CODEX_NO_PROXY"
    export http_proxy="$HTTP_PROXY" https_proxy="$HTTPS_PROXY" all_proxy="$ALL_PROXY" no_proxy="$NO_PROXY"
    ;;
  claude) executable="${CLI_ROOT}/node_modules/.bin/claude" ;;
  *) die "tool must be exactly codex or claude" ;;
esac
if (($# > 0)) && [[ "$#" != 1 || "$1" != "--version" ]]; then
  die "only no arguments or the exact --version argument is allowed"
fi
[[ -x "$executable" ]] || die "selected CLI is not installed"
[[ -d "$WORKSPACE" ]] || die "fixed workspace is missing"
cd -- "$WORKSPACE"
export PATH="$CONTROLLED_PATH"
if (($# == 1)); then
  exec "$executable" --version
fi
if [[ "$tool" == claude ]]; then
  [[ -f "$BASE_URL_FILE" && ! -L "$BASE_URL_FILE" ]] || die "Anthropic base URL file is missing"
  [[ -f "$AUTH_TOKEN_FILE" && ! -L "$AUTH_TOKEN_FILE" ]] || die "Anthropic auth token file is missing"
  [[ "$(stat -c '%a' "$BASE_URL_FILE")" == 600 ]] || die "Anthropic base URL file must be mode 0600"
  [[ "$(stat -c '%a' "$AUTH_TOKEN_FILE")" == 600 ]] || die "Anthropic auth token file must be mode 0600"
  ANTHROPIC_BASE_URL=""; ANTHROPIC_AUTH_TOKEN=""
  IFS= read -r ANTHROPIC_BASE_URL < "$BASE_URL_FILE" || [[ -n "$ANTHROPIC_BASE_URL" ]]
  IFS= read -r ANTHROPIC_AUTH_TOKEN < "$AUTH_TOKEN_FILE" || [[ -n "$ANTHROPIC_AUTH_TOKEN" ]]
  [[ "$ANTHROPIC_BASE_URL" =~ ^https?://[^[:space:]]+$ ]] || die "Anthropic base URL is empty or invalid"
  [[ -n "$ANTHROPIC_AUTH_TOKEN" ]] || die "Anthropic auth token is empty"
  ANTHROPIC_MODEL="deepseek-v4-pro"
  ANTHROPIC_DEFAULT_OPUS_MODEL="deepseek-v4-pro"
  ANTHROPIC_DEFAULT_SONNET_MODEL="deepseek-v4-pro"
  ANTHROPIC_DEFAULT_HAIKU_MODEL="deepseek-v4-pro"
  CLAUDE_CODE_SUBAGENT_MODEL="deepseek-v4-pro"
  export ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL \
    ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL \
    ANTHROPIC_DEFAULT_HAIKU_MODEL CLAUDE_CODE_SUBAGENT_MODEL
fi
exec "$executable" "$@"
WRAPPER
  chmod 0755 "$wrapper_tmp"
  if [[ ! -e "$WRAPPER" ]] || ! cmp -s "$wrapper_tmp" "$WRAPPER"; then
    install -m 0755 -o root -g root "$wrapper_tmp" "$WRAPPER"
  fi
}

install_node
install -d -m 0700 -o root -g root "$CONFIG_ROOT"
install_secret_file "$base_url_source" "$BASE_URL_FILE"
install_secret_file "$auth_token_source" "$AUTH_TOKEN_FILE"
install_cli
write_wrapper

printf '%s\n' "Installed Node v${NODE_VERSION}, Codex ${codex_version}, Claude Code ${claude_version}."
printf '%s\n' "Wrapper: ${WRAPPER}; workspace: ${WORKSPACE}"
printf '%s\n' "Interactive checks (as root): ${WRAPPER} codex --version; ${WRAPPER} claude --version"
