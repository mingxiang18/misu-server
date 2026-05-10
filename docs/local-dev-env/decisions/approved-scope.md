# Phase 1 — DISCOVER：本地联调环境

## 1. 目标（Goal）

为 misu-server 搭一套**纯本地**的开发/联调环境：

- 后端三件套：`misu-account` / `misu-gateway` / `misu-file-server` 在本机 JVM 中跑起来
- 前端：`misu-file-server-ui` Vite 开发服务器，可调通后端
- 中间件：全部用本机 Docker 容器
- 一键 `up` / `down` / `status`，且后续 Claude 也能用这套脚本自动启动/校验

## 2. 用户与使用场景

- **唯一用户**：开发者本人 + 后续 Claude
- **典型场景**：
  - 早上：`./dev.sh up` → 写代码 → 手动重启某个 Java 服务 → 调试
  - 晚上：`./dev.sh down`
  - Claude 后续接到任务时，先 `./dev.sh status`，看哪个挂了就 `./dev.sh restart <service>`

## 3. 当前架构侦察结论

### 3.1 模块清单（本次范围）

| 模块 | 端口 | context-path | 启动类 | 说明 |
|---|---|---|---|---|
| misu-gateway | 30260 | / | `GatewayApplication` | Spring Cloud Gateway，路由 `/account/**` 和 `/fileServer/**` |
| misu-account | 30261 | /account | `AccountApplication` | 账号、JWT、用户 |
| misu-file-server | 30262 | /fileServer | `FileServerApplication` | 文件存储、视频转码、视频房间 WS、RSS、Torrent |
| misu-file-server-ui | 5173 (Vite) | — | Vite + Electron | Vue3 前端，开发模式 `npm run dev` |

### 3.2 中间件清单

| 中间件 | 用途 | 必须？ |
|---|---|---|
| **Nacos 2.x** | 服务注册 + 配置中心（所有 Java 模块强依赖） | **必须** |
| **MySQL 8** | 两个库：`misu_account`、`misu_file_server` | **必须** |
| Sentinel Dashboard | 仅 gateway 引用，限流监控 | **可选**（默认禁用以减少容器） |
| Redis | 代码中未使用 `RedisTemplate / @Cacheable` | **不需要** |
| MinIO / OSS | `fileClient.type` 默认 `local`（本地文件系统）| **不需要** |
| qBittorrent | `file-server.qBitTorrent.remoteEnable` 可关 | **不需要**（dev 关掉） |

### 3.3 配置链路（关键发现）

- 现有 `application-local.yml` 仅含 Nacos 指针（`10.8.0.22:8848`，namespace `local`），**真实 DB / JPA / 业务配置全部存在 Nacos 配置中心**：`misu-account-local.yml`、`misu-file-server-local.yml`、`misu-gateway-local.yml`。
- 我没有访问那台共享 Nacos 的权限，但根据 `misu-web/application.yml`（仅参考，本次不编译该模块）和源码 `@Value` 反推出了完整配置 schema。
- DDL：`docs/` 下只有几个增量迁移片段，**没有完整建表 SQL**。但所有表都用 JPA `@Entity` 标注，最简方案是 `spring.jpa.hibernate.ddl-auto=update` 让 Hibernate 启动时建表（仅本地用）。

### 3.4 数据库 schema（从代码反推）

- `misu_account`：`sys_user`、`sys_user_role`
- `misu_file_server`：`file_mapping`、`torrent_info`、`torrent_user_relation`、`rss_info`、`rss_item`、`rss_rule`、`video_room`、`video_room_event`

### 3.5 端口分配（最终方案）

| 端口 | 服务 |
|---|---|
| 3316 | MySQL（与 `misu-web/application.yml` 已有约定一致，避免与系统 3306 冲突）|
| 8848 | Nacos HTTP API |
| 9848 / 9849 | Nacos gRPC（2.x 必须） |
| 8858 | Sentinel Dashboard（如启用） |
| 30260 / 30261 / 30262 | Java 三件套 |
| 5173 | 前端 Vite |

## 4. 范围（Scope）

### 4.1 In-scope（本次必须交付）

1. **`docker-compose.local.yml`** — 起 Nacos（standalone）+ MySQL，含 healthcheck
2. **数据库初始化脚本** `scripts/sql/init.sql` — 建库（不建表，让 JPA 自己 build）
3. **Nacos 配置 seed** `scripts/nacos/*.yml` + 用 Nacos OpenAPI 自动推送的脚本
4. **`application-local.yml` 修补** — Nacos 地址从 `10.8.0.22:8848` 改成 `localhost:8848`，禁用 Sentinel transport
5. **统一入口 `dev.sh`**（放在 repo 根），子命令：
   - `dev.sh up` — 启中间件 + seed Nacos + （可选）build java + 起三个 Java 进程 + 起前端
   - `dev.sh down` — 反向全部停掉
   - `dev.sh status` — 各组件健康状态 + PID + 端口
   - `dev.sh logs <service>` — tail 日志
   - `dev.sh restart <service>` — 重启单个 Java/前端
   - `dev.sh build` — 仅 `mvn package -pl ... -am -DskipTests`
6. **README** `dev-env-README.md`，说明前置依赖、首次使用、常见故障

### 4.2 Non-goals（明确不做）

- 不打 Docker 镜像跑 Java（开发期 host JVM 直接跑，热重启快）
- 不部署 misu-bot / misu-net / misu-web
- 不动 prod / Dockerfile / 现有 `scripts/build-*.sh`
- 不写完整 DDL（依赖 JPA `ddl-auto=update`）
- 不接真实 qBittorrent / SFTP / 腾讯云

## 5. 功能性需求（FR）

- **FR-1** `dev.sh up` 在 Mac 上一键启动，全程不需要交互；总耗时（首次 ≤ 5 分钟，二次 ≤ 60 秒）
- **FR-2** Nacos 启动后自动 seed 三份配置，已存在则覆盖（保持幂等）
- **FR-3** 三个 Java 服务带 PID 文件、按服务隔离日志（`logs/<service>.out`）、按依赖顺序启动（gateway 最后）
- **FR-4** `dev.sh down` 必须能停掉所有由该脚本启动的进程 + 容器，不残留
- **FR-5** 前端 `npm run dev` 默认连 `localhost:30260`（已是 `.env.development` 的设定，无需改）
- **FR-6** `dev.sh status` 输出每个组件的：监听端口 / PID / health endpoint 状态（绿/红）

## 6. 非功能需求（NFR）

- **可移植**：脚本只用 `bash` + 标准 `curl/jq/lsof/docker compose`，不依赖 GNU coreutils 特性
- **可观测**：每个组件失败时，错误信息直接打到终端，不要让用户去翻日志
- **可幂等**：重复 `up` 不报错；`down` 后 `up` 仍能干净启动
- **不污染 Git**：临时产物（PID / 日志 / `.dev/` 状态目录）写进 `.gitignore`

## 7. 验收标准（Acceptance Criteria）

| ID | 标准 |
|---|---|
| **A1** | 在 Mac 干净状态下运行 `./dev.sh up`，5 分钟内三个 Java 服务全部健康（actuator/health 或 root 200/404 即可），前端可访问 |
| **A2** | 浏览器访问 `http://localhost:5173`，登录页能加载；点击登录会发请求到 `http://localhost:30260/account/...` 并得到非网络错误（即便业务上账号不存在）|
| **A3** | `./dev.sh status` 全绿 |
| **A4** | `./dev.sh down` 后 `docker ps`、`lsof -i :30260` `:30261` `:30262` `:8848` `:3316` 均无残留 |
| **A5** | 二次 `up` 不报"已存在/端口占用"等错误 |
| **A6** | 后续 Claude 仅凭 `./dev.sh status` + `dev.sh logs <name>` 就能定位环境是否正常 |

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| Nacos 2.x 必须 gRPC（9848/9849），如果用户网络下载镜像慢，首次 `up` 可能超时 | 中 | docker-compose 设置较长 healthcheck `start_period` |
| `mvn package` 在用户机上可能因依赖下载慢而超时 | 中 | 复用用户已有的 `~/.m2/repository`（脚本里读 `MAVEN_REPO` 环境变量；缺省走默认） |
| JPA `ddl-auto=update` 可能漏建索引/约束 | 低 | 仅本地 dev 用；docs/*.sql 里的迁移可补充执行（在脚本中提供 `dev.sh seed-sql`）|
| sandbox 中没装 docker / mvn，AI 无法在本环境跑通 | 高 | **真正运行验证必须在用户 Mac 上执行**；AI 在 BUILD 阶段会做语法检查 + 静态校验，SHIP 阶段把执行交给用户 |
| 用户原来的 `application-local.yml` 同时被团队共享 Nacos 用 | 中 | **见 GATE 1 决策点 1**：是改原文件还是新增 profile |

## 9. 决策点（请用户回答）

> 只问 3 个，其余我用推荐值默认走。

### Q1. `application-local.yml` 处理方式

- **(a) 直接改 `application-local.yml`** 把 Nacos 地址从 `10.8.0.22:8848` 改成 `localhost:8848`、Sentinel transport 改为 `localhost:8858`（你说"可以读取和配置"——这是最干净的）**(recommended)**
- (b) 保留 `application-local.yml` 不动，新增 `application-localdev.yml`，启动用 `--spring.profiles.active=localdev`
- (c) 不改任何配置文件，只在 `dev.sh` 里通过 JVM 系统参数 `-Dspring.cloud.nacos.config.server-addr=localhost:8848` 等覆盖

### Q2. Sentinel Dashboard 是否启动？

- **(a) 不启动，dev 模式禁用 Sentinel transport**（启动更快，gateway 仍可跑通限流配置只是不上报）**(recommended)**
- (b) 启动 Sentinel Dashboard 容器（多一个端口 8858）

### Q3. Java 服务启动方式

- **(a) Host JVM 直接 `java -jar`** — 修改代码后 `dev.sh restart account`，最快 **(recommended)**
- (b) 三个 Java 服务也跑在 docker compose 里（与 prod 更像，但每次改代码要重 build 镜像）
- (c) 给两套：dev.sh 默认 host，加 `dev.sh up --containerized` 走 docker

## 10. 开放问题（无须现在回答）

- 后续是否要把 `dev.sh` 注册成 IDE 的 Run Configuration？（先做命令行，IDE 集成后置）
- 是否要把 Nacos 配置版本化进 git（`scripts/nacos/`）？（默认是，方便回滚）

---

**[GATE 1: scope]**

Produced: 一份纯本地联调方案。中间件 Nacos + MySQL 跑 docker，三个 Java 服务跑 host JVM，前端 Vite，统一脚本 `dev.sh`。AI 在 sandbox 内做静态校验，最终 `dev.sh up` 的真实运行验证需要在你的 Mac 上跑（A1-A5）。

Decisions you need to make:
1. `application-local.yml` 处理方式 — (a) recommended / (b) / (c)
2. Sentinel Dashboard 是否启动 — (a) recommended / (b)
3. Java 服务启动方式 — (a) recommended / (b) / (c)

Reply with:
- "approve" → 我按三个 (a) 走
- "approve, Q2=b" → 你只想改 Q2，其余按推荐
- "request changes: …" → 我在本 Phase 内迭代
- "reject" → 重做
Locked: scope approved (Q1=a, Q2=a, Q3=a)
