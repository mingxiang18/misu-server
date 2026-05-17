# 自动部署流程（scripts/deploy/）

开发机一键发布到生产 k8s 集群。开发流程不变：在 `claude/*` 分支干活、开 PR、
用户审核后合并到 master，然后在开发机跑一条命令完成上线。

## 工作原理

```
合并到 master  →  在开发机执行 scripts/deploy/release.sh
   │
   ├─ 1. Maven 构建 3 个 Java 服务 → docker buildx 构建镜像
   │     → 推送到私有 registry，tag = master 的 git short SHA
   ├─ 2. vite build 构建前端
   ├─ 3. SSH 主节点(10.8.0.1)：
   │       备份旧清单 → /root/backups/<UTC时间戳>/k8s/
   │       渲染新清单(填入 registry+SHA) 覆盖 /root/k8s/misu-server/
   │       kubectl apply + rollout status
   └─ 4. SSH 工作节点(10.8.0.26)：
           备份旧 html → /root/backups/<UTC时间戳>/html/
           覆盖前端静态文件 /mnt/misu/misu-server/html/
   任一步失败 → 自动回滚。
```

所有 IP / 路径 / SSH key 集中在 **一个本地配置文件** `scripts/deploy/deploy.conf`，
`release.sh` 执行时自动读取。

## 覆盖范围

- 自动部署：`misu-gateway`、`misu-account`、`misu-file-server` 三个 Java 服务
  + 前端 `misu-file-server-ui`。
- 前端由集群里现有的 `misu-server-nginx` Deployment（挂载 hostPath
  `/mnt/misu/misu-server/html`）提供，发布只覆盖静态文件、无需改清单。
- **不含 `misu-web`**：它被根 `pom.xml` 的 `<modules>` 注释掉、且无 Dockerfile /
  prod 配置，不在生产部署内。

## 一次性设置

开发机需具备：JDK 17、本仓库自定义 Maven（见 `CLAUDE.md` §2）、`docker buildx`、
`envsubst`(gettext)、`rsync`、`git`；已 `docker login` 私有 registry 且把它配进
docker daemon 的 `insecure-registries`；能用 SSH key 登录两台节点。

```bash
cp scripts/deploy/deploy.conf.example scripts/deploy/deploy.conf
vi scripts/deploy/deploy.conf      # 填 SSH key 路径、两台节点、registry、Maven 路径
```

`deploy.conf` 已在 `.gitignore` 中，不会提交。

## 日常使用

合并 PR 到 master 后，在仓库根目录执行。**不带目标 = 全部发布；带目标 = 按需发布**：

```bash
scripts/deploy/release.sh                        # 全部：3 个 Java 服务 + 前端
scripts/deploy/release.sh misu-gateway           # 只发布单个服务
scripts/deploy/release.sh misu-account frontend  # 发布多个指定目标
scripts/deploy/release.sh frontend               # 只发布前端
scripts/deploy/release.sh --dry-run              # 只构建，不推送、不碰服务器（验证用）
scripts/deploy/release.sh misu-gateway --skip-build  # 镜像已推过，只重新部署
```

目标名支持别名：`gateway` / `account` / `file-server` / `front` / `ui`。

## 回滚

```bash
scripts/deploy/release.sh --list-backups          # 列出两台节点的备份时间戳
scripts/deploy/release.sh --rollback 20260517T083000Z
```

回滚会用指定备份恢复 `/root/k8s/misu-server/` 的清单与前端 html 并重新 apply。
部署中途 rollout 失败时，`release.sh` 会**自动回滚**到本次部署前的状态。

## k8s 清单的真源

`scripts/deploy/k8s/misu-server/{misu-gateway,misu-account,misu-file-server}.yaml` 是清单的
唯一真源，内容取自集群现有清单，仅把镜像行参数化为
`${REGISTRY_PULL}/misuaa/<服务>:${IMAGE_TAG}`，发布时由 `envsubst` 渲染。

要改部署配置（副本数、资源、探针等），改这里的文件并合并到 master，下次发布即生效。
`misu-server-nginx.yaml`、`nacos.yaml` 等其它清单未纳入本流程，仍在服务器上手工维护。

## 日志与备份

- 发布日志：开发机 `scripts/deploy/deploy.log`。
- 备份：两台节点的 `/root/backups/<UTC时间戳>/`，自动保留最近 `KEEP_BACKUPS` 份。
