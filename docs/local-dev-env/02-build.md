# Phase 3 — BUILD：本地联调环境实现

## 1. 技术设计（½ 页）

### 1.1 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Mac 主机                                  │
│                                                              │
│  ┌── docker-compose.local.yml ──┐    ┌── host JVM ──┐       │
│  │                              │    │              │       │
│  │  Nacos 2.3.2 (8848/9848/49)  │◄───┤ misu-account │ 30261 │
│  │  MySQL 8       (3316)        │◄───┤ misu-fileSrv │ 30262 │
│  │                              │◄───┤ misu-gateway │ 30260 │
│  └──────────────────────────────┘    └──────────────┘       │
│                                              ▲              │
│                                              │ HTTP/WS      │
│                                       ┌──────┴──────┐       │
│                                       │  Vite (UI)  │ 5173  │
│                                       └─────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 文件清单

| 类别 | 路径 | 说明 |
|---|---|---|
| 中间件 | `docker-compose.local.yml` | Nacos + MySQL 两容器，含 healthcheck |
| 数据库 | `scripts/dev/sql/01-init-databases.sql` | 建 `misu_account` / `misu_file_server` 两库 |
| Nacos 配置 | `scripts/dev/nacos/misu-account-local.yml` | DB / JPA / token / fileClient |
| 同上 | `scripts/dev/nacos/misu-file-server-local.yml` | DB / JPA / file-server.path / qBitTorrent关 / video.transcode关 |
| 同上 | `scripts/dev/nacos/misu-gateway-local.yml` | logging（routes 在 application-local.yml） |
| Nacos 推送 | `scripts/dev/nacos/seed.sh` | 用 OpenAPI 推送 3 份 yml |
| Shell 工具 | `scripts/dev/lib/common.sh` | wait_for / port_in_use / nacos_publish 等 |
| 编排入口 | `dev.sh` | up / down / status / restart / build / logs / seed-nacos / seed-sql / nuke |
| 配置改动 | 三处 `application-local.yml` | Nacos 地址 `10.8.0.22` → `localhost`；gateway 关 Sentinel transport |
| 文档 | `dev-env-README.md` | 用户文档 |
| .gitignore | 新增 `/.dev/`、`/.misu-dev/` | 排除运行时产物 |

### 1.3 关键设计决策

1. **JVM 系统参数 `-DNACOS_SERVER_ADDR`**：`application-local.yml` 用占位 `${NACOS_SERVER_ADDR:localhost:8848}`，dev.sh 启动时注入。这样既保证默认值是本地，又能在团队共享 Nacos 时一行环境变量切回去。
2. **Nacos auth 启用 + 默认账号 nacos/nacos**：与 Nacos 2.3 默认行为一致，`spring.cloud.nacos.username/password` 在 application-local.yml 里写死，避免每次都要去 Nacos 控制台手动配。
3. **ddl-auto=update**：因为 docs/ 中只有增量迁移片段没有完整 DDL，最稳的办法是依赖 JPA `@Entity`。仅本地用，prod 不受影响。
4. **Sentinel 关闭，不引入 dashboard 容器**：用户选了 Q2=a。配置里保留 transport 块的注释，便于将来打开。
5. **Java 跑 host JVM 不进 Docker**：用户选了 Q3=a。改代码 → `mvn package` 几秒 → `dev.sh restart` 比重 build 镜像快得多。
6. **PID 隔离 + 端口监听等待**：每个服务 `nohup java -jar &` 后写 PID 到 `.dev/pids/<svc>.pid`，再 `wait_for` 直到端口监听成功；任一失败立即报错并 tail 日志路径，不让用户去翻日志。
7. **健康等待用 `--start-period`**：Nacos 首次启动慢，docker healthcheck 给了 30s 起步、最多 5 分钟（60×5s），实战充裕。

## 2. 实现日志

### M1 — 修补 application-local.yml（不分提交，改 3 个文件）
- account / file-server / gateway：`10.8.0.22:8848` → `${NACOS_SERVER_ADDR:localhost:8848}`
- 加 `username: nacos` / `password: nacos`（Nacos 2.3 默认开 auth）
- gateway：注释掉整个 Sentinel `transport`/`datasource` 块，加 `enabled: false` / `eager: false`
- 验证：`python3 -c "import yaml; …"` 全部 pass

### M2 — docker-compose.local.yml + sql 初始化
- Nacos 2.3.2 standalone，env 显式 `NACOS_AUTH_ENABLE=true` + 占位 token
- MySQL 8.0，`utf8mb4_unicode_ci` + `lower_case_table_names=1`
- 端口绑定 3316（与 misu-web/application.yml 已有约定一致，避免和系统 3306 冲突）
- mount `./scripts/dev/sql:/docker-entrypoint-initdb.d:ro` 让 mysql:8 自动建库
- volumes：`misu-mysql-data` / `misu-nacos-data` / `misu-nacos-logs`，`./dev.sh nuke` 时一并删

### M3 — Nacos seed 三份 yml
- `misu-account-local.yml`：HikariCP / JPA / token.secret / register.enable / fileClient.type=local
- `misu-file-server-local.yml`：同上 DB；file-server.path 默认 `${user.home}/.misu-dev/files/file-server/`；qBitTorrent.remoteEnable=false；video.transcode.enabled=false（默认关，避免要求用户装 ffmpeg）
- `misu-gateway-local.yml`：仅 logging，最小化（路由仍走 application-local.yml）

### M4 — `scripts/dev/lib/common.sh`
- 颜色日志（log/ok/warn/err/section），非 TTY 时降级
- `wait_for` / `port_in_use` / `pid_alive` / `read_pid` / `write_pid`
- `nacos_login` 拿 accessToken（不依赖 jq，sed 解析）
- `nacos_namespace_ensure` / `nacos_publish`
- `detect_compose` 兼容 `docker compose` / `docker-compose`

### M5 — `scripts/dev/nacos/seed.sh`
- 等 Nacos readiness → 确保 namespace=local → 推送 3 份 yml

### M6 — `dev.sh`（核心入口）
- 9 个子命令：up / down / status / restart / build / logs / seed-nacos / seed-sql / nuke
- `up` 流程：`check_prereqs → mw_up → java_build → java_start_one × 3（按顺序）→ frontend_start → status`
- `down` 反向
- `restart <name>`：`mw / frontend / account / file-server / gateway / all` 都支持
- 每个 Java 服务启动后用 `wait_for` 监听端口，最多 120s
- 前端用 `npx vite --port 5173`，避开 electron（Mac 上 dev.sh 主要做浏览器联调）
- `--no-build` / `--no-frontend` 旗标加速二次启动

### M7 — `.gitignore` + chmod
- 加 `/.dev/`、`/.misu-dev/`
- `chmod +x dev.sh scripts/dev/nacos/seed.sh`
- `bash -n` 全部通过

### M8 — 用户文档
- `dev-env-README.md`：TL;DR / 前置依赖 / 端口表 / 目录约定 / FAQ / AI 后续使用

## 3. Sandbox 静态校验

| 检查 | 结果 |
|---|---|
| `bash -n dev.sh` | ✓ |
| `bash -n scripts/dev/nacos/seed.sh` | ✓ |
| `bash -n scripts/dev/lib/common.sh` | ✓ |
| `python3 yaml.safe_load` × 7 个 yml | ✓ 全部合法 |
| `./dev.sh help` 输出正常 | ✓ |
| `./dev.sh status`（无 docker 环境） | ✓ 显示中间件未运行 + 三个 Java DOWN |

## 4. Sandbox 不能验证的部分（交给 SHIP）

由于本沙箱没有 Docker / Maven / 本地 MySQL：

- 实际 `docker compose up` 是否健康
- `mvn package` 三个模块是否能编译过（依赖 m2 cache）
- Java 服务能否真的从 Nacos 拉到配置并连上 MySQL
- 前端登录链路 `localhost:5173 → localhost:30260/account/...` 是否打通

这些放到 Phase 4 SHIP 由用户在 Mac 上跑 `./dev.sh up` 验证（验收 A1–A5）。

## 5. 风险与已采取缓解

| 风险 | 缓解 |
|---|---|
| Nacos 2.3 鉴权失败 | docker 显式开 auth + 应用配置 `username/password=nacos` |
| MySQL 端口与本机已有 MySQL 冲突 | 用 3316，与 misu-web 已有约定一致 |
| `mvn package` 慢 | `--no-build` 跳过；用户可设 `MVN=` 指向自己的 mvn 副本 |
| 服务启动顺序错（gateway 先于 file-server） | `JAVA_SERVICES=("account" "file-server" "gateway")` 严格按数组顺序 |
| 残留进程 | `down` 反向 + 端口兜底（前端走 lsof 杀） |
| 重复 `up` 报"已存在" | 每个 `*_start_one` 有 `pid_alive` + `port_in_use` 早返检查 |
