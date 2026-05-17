#!/usr/bin/env bash
# Build & push the in-cluster ffmpeg-worker image.
#
#   ./scripts/build-push-ffmpeg-worker.sh 0.0.1
# 或：
#   VERSION=0.0.1 PLATFORMS=linux/amd64 ./scripts/build-push-ffmpeg-worker.sh
#
# 默认推到 misuaa/misu-ffmpeg-worker；本地集群可以用：
#   REGISTRY=10.8.0.26:30500/ ./scripts/build-push-ffmpeg-worker.sh 0.0.1
set -euo pipefail

VERSION="${1:-${VERSION:-}}"
if [[ -z "${VERSION}" ]]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 0.0.1"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT="${ROOT_DIR}/tools/local-ffmpeg-worker"
REGISTRY="${REGISTRY:-}"
IMAGE="${REGISTRY}misuaa/misu-ffmpeg-worker:${VERSION}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

cd "${CONTEXT}"
docker buildx build \
  --platform "${PLATFORMS}" \
  -t "${IMAGE}" \
  --push \
  .
echo "Pushed ${IMAGE}"
