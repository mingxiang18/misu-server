#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-${VERSION:-}}"
if [[ -z "${VERSION}" ]]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 0.0.1"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MVN="/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn"
MAVEN_REPO="/Users/renyuming/Documents/develop/maven/repository"
MODULE="misu-file-server/misu-file-server-biz"
IMAGE="misuaa/misu-file-server:${VERSION}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

cd "${ROOT_DIR}"
"${MVN}" clean -pl "${MODULE}" -am -f pom.xml -Dmaven.repo.local="${MAVEN_REPO}" -DskipTests=true -P prod
"${MVN}" package -pl "${MODULE}" -am -f pom.xml -Dmaven.repo.local="${MAVEN_REPO}" -DskipTests=true -P prod

docker buildx build --platform "${PLATFORMS}" -t "${IMAGE}" --push "${MODULE}" -f "${MODULE}/Dockerfile"
