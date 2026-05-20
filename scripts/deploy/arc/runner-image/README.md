# misu-ci-runner 镜像

自建 GitHub Actions runner 镜像，在官方 `actions-runner` 基础上预装：
- JDK 17 + Maven 3.9
- Node 20
- kubectl v1.34.1
- Kaniko v1.23.2（无需 docker daemon 即可 build & push 镜像）
- git / rsync / jq

## 构建并推送

```bash
# 1. 用本机 docker buildx（已配好可推 192.168.50.227:30500）
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT/scripts/deploy/arc/runner-image"

# buildx 多平台不必要，runner 只跑 amd64
docker buildx build \
  --platform linux/amd64 \
  -t 192.168.50.227:30500/misuaa/misu-ci-runner:latest \
  -t 192.168.50.227:30500/misuaa/misu-ci-runner:$(date +%Y%m%d) \
  --push .
```

## 何时需要重建

- 上游 `actions-runner` 大版本升级（一年一两次）
- 想换 JDK / Maven / Node / kubectl / Kaniko 版本
- 加新工具

平时不动它。runner pod 用 `imagePullPolicy: Always` 拉 `:latest`，重建推送完，下次 workflow 自动用新版本。

## 排错

- **runner pod 起来后日志 "permission denied"**：misu-maco 上的 hostPath 没 chown 给 uid 1001
  → `chown -R 1001:1001 /mnt/misu/ci /mnt/misu/misu-server/html`
- **Kaniko 推 registry 失败 `http: server gave HTTP response to HTTPS client`**：
  workflow 的 `--insecure --skip-tls-verify` 都在，但 Kaniko 也会读 `DOCKER_CONFIG`，确保 secret 没有错的 auth
