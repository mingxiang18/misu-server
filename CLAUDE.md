# misu-server — hard rules
## 拓扑 / 端口

| 服务 | 端口 | context-path |
|---|---|---|
| `misu-gateway` | 30260 | — |
| `misu-account` | 30261 | `/account` |
| `misu-file-server-biz` | 30262 | `/fileServer` |
| file-server actuator | 30362 | `/actuator` |
| `misu-file-server-ui` (vite) | 5173 | — |
| MySQL | 3316 | — |
| Nacos | 8848 | `nacos/nacos` |

模块：`misu-framework`（含 `misu-security` JWT 过滤链）、`misu-net`（Nacos/Cloud 公共配置）、`misu-chat`（聊天 / 群聊 / bb 机器人接入，旧名 misu-bot；包 `com.misu.chat`）、`misu-web`。配置分两层刻意区分：**`bot.*`**（bb 机器人连接 + 它的 WS 桥，prod Nacos 已有不改：`bot.enable`/`bot.serverPort`=30201/`bot.serverWebSocketUrl`/`bot.token.expireTtl`/`bot.bbConnection.*`）；**`chat.*`**（聊天数据层新增：`spring.datasource.chat` 指向库 `misu_chat`，群聊参数 `chat.groupRandomReplyRate`/`chat.botUserId`，群文件磁盘目录 `chat.file.path`——文件/图片存盘、消息只放引用 fileId，**prod 需挂持久卷**）。

## 本机工具链（per-machine，禁止改进脚本）

- `JAVA_HOME=$(/usr/libexec/java_home -v 17)`
- mvn：`/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn`
- mvn 本地仓库：`-Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository`

单模块编译模板：
```bash
/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn \
  -pl misu-file-server/misu-file-server-biz -am compile -DskipTests \
  -Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository
```

## Dev / Release

- 本地起停：`scripts/dev/dev.sh {up|down|status|restart <svc>|logs <svc>|build|seed-nacos|seed-sql|nuke}`
- 重启 `mw` 会重新 seed `scripts/dev/nacos/*.yml`
- 本地手动发布（兜底）：`scripts/deploy/release.sh`（本地驱动 → 推私有 registry → SSH 部署，失败自动回滚）。配置 `scripts/deploy/deploy.conf`（gitignored）

## ⚠️ 发布 = push master 自动触发（ARC self-hosted runner）

**merge / push 到 master 会自动构建并部署到生产**，没有手动步骤。改任何代码前先想清楚这条链路：

```
push master → GitHub Actions(.github/workflows/release.yml) → 集群内 runner
  → mvn package → Kaniko build & push 私有 registry → kubectl apply + rollout
```

- 按变更路径自动选服务：改 `misu-gateway/**` 只发 gateway；改 `misu-framework/** | misu-net/** | pom.xml`（共享）会发**全部 Java 服务**；改 `misu-file-server-ui/**` 发前端
- 镜像 tag = git short SHA；`workflow_dispatch` 可手动选 `services=all/gateway/account/file_server/frontend`
- ARC 配置在 `scripts/deploy/arc/`；机制 / 踩坑见那里的 README + 集群侧 `init/k8s/docs/ARC-CICD.md`

### 改代码必须保证 merge 后 auto-deploy 仍能跑通（每条都踩过）

1. **加新服务/模块** → 必须同时：① workflow 的 paths-filter + Build JARs 的 `-pl` 列表加它 ② `scripts/deploy/k8s/misu-server/` 加它的 Deployment+Service YAML ③ 否则 push 后它不会被构建/部署
2. **改 Deployment/Service YAML**（resources/probes/env/端口）→ 直接生效（workflow 用 `envsubst + kubectl apply`，不只是换镜像 tag）。但 YAML 写错会让 `kubectl apply` 失败 → 整个 release 失败
3. **改 `DockerfileLocal`** → 必须保持 `FROM .../amazoncorretto:17-alpine3.20-jdk`（runner 的 kaniko-wrapper 靠它换 `-fonts` 变体 + 删 apk RUN；换别的 base 会触发 Alpine+kaniko 字体 bug）
4. **引入新的自定义/私有 maven 依赖**（非 maven central 能拉的）→ 要么在同 reactor 内构建，要么先 push 到集群 maven cache PVC（否则 runner 上 mvn 拉不到，build 失败）
5. **改 `scripts/deploy/k8s/misu-server/*.yaml` 的镜像行** → 保留 `${REGISTRY_PULL}/misuaa/<svc>:${IMAGE_TAG}` 占位（workflow envsubst 渲染）
6. push 后去 GitHub Actions 看绿；失败时 workflow 会自动 `rollout undo`，但**别留着红的 release 不管**

## Dev 测试账号（AI 自验证流程直接复用）

- `verifybot` / `Test1234!`，phone `13900000000`，非管理员
- `POST /account/auth/login { userName, password, captchaCode:"dummy" }`
- captcha 后端只校验非空（任意字符串都行），phone 必填且要过 `^(13|14|15|16|17|18|19)\d{9}$`
- 注册需 `register.enable=true`（local nacos 已开，prod 没有）
- 该号撞管理员接口会被合法拒绝 → HTTP 403，正好可验「真 403 路径」

## 后端硬规则（每条都踩过坑）

- `@RequestParam` **必须显式写名字**：`@RequestParam("openType") Integer openType`（编译不带 `-parameters`）
- `LocalDateTime` 字段配 `@ColumnDefault("CURRENT_TIMESTAMP(6)")`，**不是** `"CURRENT_TIMESTAMP"`（Hibernate 生成 `datetime(6)`，MySQL 8 STRICT 会拒绝）
- 复合索引按 `(open_type, user_id, parent_path, deleted)` 列序；`target_virtual_path varchar(1200)` **禁止**进复合索引（4× utf8mb4 超 3072 byte 限制）
- 新实体加 `@Index` 时列顺序必须与现有约定对齐 —— `hbm2ddl=update` 只 ADD 不修
- 新指向 `file_mapping` 的实体必须同时挂进 `purgeFromTrash`（手动彻底删除）和 `cleanDeletedFileMappings`（定时 GC），参考 `FileVersionService.purgeAllVersionsForMapping`
- 权限拒绝抛 `HttpStatus.FORBIDDEN`（403），与 401（session 过期）严格分开 —— 前端 axios 上 401 刷 token、403 toast，**不能并支**
- Actuator 必须在 30362 单独端口：`management.server.port: 30362` + `SecurityConfiguration.permitAll("/actuator/**")`（否则与 `misu-security` 的 `PermitAllUrlProperties` 冲突）
- **新成体系功能（如 WebDAV）另起独立 service**，不要往 `FileServiceImpl` 加方法（已经很大）；复用就调它已 public 的方法（如 `accessUserFileAsUser`）

## 前端硬规则

- **所有请求走 `misu-file-server-ui/src/api/request.js` 的 axios 实例**，包括二进制下载（`responseType:'arraybuffer'`）。禁止裸 `fetch()`（跨域 5173→30260 会掉 `Authorization`）
- `useBreakpoint()`（`src/composables/useBreakpoint.js`）分桌面 / 移动 chrome
- **任何 UI/UX 改动必须同时覆盖桌面 + 移动**（≤640px）。桌面 = SideNav 左 + PageHeader 顶，移动 = TabBar 底 + sheets。改完两个 viewport 都要在 Chrome MCP 截图验证（1280×800 + 414×800）。复用同一 ref/composable 让两端状态自动同步
- `vite.config.js` 的 `build.target: 'esnext'` 禁改（foliate-js 顶层 await 必需）

## ffmpeg-worker 镜像跨架构

- worker 镜像必须 `linux/amd64`（节点 misu-maco 是 amd64）
- 在 arm64 Mac 上用 `docker buildx --platform linux/amd64` **完整**构建 worker 会 QEMU 卡死（apk 装 140+ 多媒体包死锁）
- **只改 shell 脚本 / worker-api.py** → 补丁镜像：`FROM <现有 amd64 镜像> + COPY --chmod=755 <新脚本>`，零 RUN 零模拟，秒级完成；之后 `release.sh ffmpeg-worker --skip-build`
- 要重装 / 升级 apk 包 → 必须有原生 amd64 构建机（buildkit on 集群节点），arm64 Mac 上 `release.sh ffmpeg-worker`（不带 `--skip-build`）会卡死

## Nacos 配置

- 每个服务有 jar 内薄 `application.yml` + Nacos 启动时拉的**覆盖**（local 在 `scripts/dev/nacos/*.yml`）
- local 用 `hibernate.hbm2ddl.auto=update`，新实体 / `@Index` 自动应用
- 生产用迁移 DDL（参考 `docs/file-server-ux-mvp-ddl.md`）
