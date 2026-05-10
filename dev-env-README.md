# misu-server 本地联调环境

## TL;DR

```bash
./dev.sh up        # 启动一切（中间件 + 后端三件套 + 前端）
./dev.sh status    # 查状态
./dev.sh logs gateway   # 看某个服务日志
./dev.sh down      # 全部停掉
```

第一次跑大约 3–5 分钟（拉镜像 + maven 下依赖 + npm install）；之后 30 秒内就绪。

---

## 1. 前置依赖

| 软件 | 版本 | 验证 |
|---|---|---|
| Docker Desktop | 任意 | `docker compose version` |
| JDK | **17** | `java -version` |
| Maven | 3.6+ | `mvn -v`，或设 `MVN=/path/to/mvn` |
| Node | 18+ | `node -v` |
| Bash | macOS 自带 3.2 即可 | `bash --version` |
| `curl`、`lsof` | 系统自带 | — |

> JAVA_HOME 必须指向 17。如果默认 Java 不是 17，临时切换：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

### 1.1 没装 Maven？用 IntelliJ 自带的

IntelliJ IDEA 自带一个 Maven，路径固定：

```bash
export MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
```

`dev.sh` 接受 `MVN=` 环境变量覆盖默认 `mvn`，所以无需 `brew install maven`。

### 1.2 自定义 Maven 本地仓库

如果你的 `~/.m2/repository` 已经被其他项目占用，或想用一个独立目录避免重复下依赖：

```bash
export MAVEN_REPO_LOCAL="/path/to/your/maven/repository"
```

`dev.sh build` 会把它翻译成 `-Dmaven.repo.local=…` 自动注入。

### 1.3 Docker Hub 拉不动？

国内直连 Docker Hub 经常失败（`Error: registry-1.docker.io ... EOF`）。一次性拉镜像源 + retag：

```bash
docker pull docker.m.daocloud.io/library/mysql:8.0
docker tag  docker.m.daocloud.io/library/mysql:8.0 mysql:8.0

docker pull --platform linux/amd64 docker.m.daocloud.io/nacos/nacos-server:v2.3.2
docker tag  docker.m.daocloud.io/nacos/nacos-server:v2.3.2 nacos/nacos-server:v2.3.2
```

之后 `./dev.sh up` 会找到本地镜像，不再触发 Docker Hub 拉取。

> 已在 `docker-compose.local.yml` 给 Nacos 加了 `platform: linux/amd64`：Nacos 2.3.x 没有 arm64 官方镜像，Apple Silicon 走 Rosetta，冷启慢约 30s 但完全可用。

## 2. 启动 / 关闭

```bash
./dev.sh up                         # 默认：build + 起中间件 + 后端 + 前端
./dev.sh up --no-build              # 跳过 mvn package（jar 已存在）
./dev.sh up --no-frontend           # 不起前端 vite
./dev.sh down                       # 反向停掉一切
./dev.sh restart account            # 改完代码后重启单个 Java 服务
./dev.sh restart mw                 # 重启中间件 + 重新 seed Nacos
```

## 3. 端口表

| 端口 | 服务 |
|---|---|
| 5173 | 前端（Vite） |
| 30260 | misu-gateway |
| 30261 | misu-account（context-path `/account`） |
| 30262 | misu-file-server（context-path `/fileServer`） |
| 8848 | Nacos 控制台 + OpenAPI |
| 9848 / 9849 | Nacos gRPC |
| 3316 | MySQL |

Nacos 控制台账号：`nacos` / `nacos`。MySQL：`root` / `root`。

## 4. 目录约定

```
/.dev/                       # dev.sh 运行时产物（不入 git）
   pids/                     # 各服务 PID
   logs/account.out          # Java 服务日志（Spring 自身的 logs/ 仍在 misu-*/logs）
   logs/gateway.out
   logs/file-server.out
   logs/frontend.out
~/.misu-dev/files/           # file-server 的本地文件存储
   file-server/              # 业务文件主目录
docker-compose.local.yml     # 中间件
scripts/dev/
   sql/01-init-databases.sql # MySQL 初始化（建库）
   nacos/*.yml               # 推到 Nacos 的配置（dataId=文件名）
   nacos/seed.sh             # 推送脚本
   lib/common.sh             # shell 工具函数
.ai-workflow/                # 本次工作流文档
```

## 5. 数据库 schema 怎么来？

JPA 配置 `hibernate.ddl-auto=update`：第一次启动 Java 服务时，Hibernate 自动建表/加字段。

如果想手动跑 `docs/` 里的迁移 SQL：

```bash
./dev.sh seed-sql
```

> 仅本地用。生产仍由 DBA / Flyway 管。

## 6. 修改 Nacos 配置

`scripts/dev/nacos/*.yml` 是**真理来源**（git 版本化）。改完执行：

```bash
./dev.sh seed-nacos                    # 重推到本机 Nacos
./dev.sh restart account               # 让服务重新拉配置
```

或直接在 Nacos 控制台 (http://localhost:8848/nacos) 改后再重启服务（控制台改的不会写回 git）。

## 7. 常见问题

**Q: `mvn package` 卡在下依赖**
A: 用国内镜像。在 `~/.m2/settings.xml` 配 aliyun mirror。

**Q: Nacos 启动失败**
A: 先 `docker logs misu-nacos-local`。常见是 8848 / 9848 被占用。

**Q: `dev.sh up` 报"端口 30260 被占用"**
A: 之前没 `down` 干净。`./dev.sh down` 后重试，仍占用就 `lsof -i :30260` 找进程。

**Q: 前端登录 401 / 403**
A: 默认 JPA 自动建表，但 `sys_user` 表是空的——通过前端注册一个，或在 Nacos 把 `register.enable: true`（默认已开）。

**Q: 想完全清空（包括 MySQL 数据）**
A: `./dev.sh nuke`（会删 docker volume）。

## 8. AI 后续使用

后续 Claude 接到任务时，先运行：

```bash
./dev.sh status                # 看哪个组件挂了
./dev.sh logs <service>        # 出问题就看日志（最后 50 行）
./dev.sh restart <service>     # 重启
```

Java 代码改动后流程：

```bash
./dev.sh build                 # 重新打 jar
./dev.sh restart <service>     # 重启对应服务
```
