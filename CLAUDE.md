# misu-server — project knowledge for Claude sessions

Auto-loaded into every session. Captures project-specific facts not derivable from `git log` or a 30-second tour. Generic workflow lessons live in the `ai-product-workflow` skill, not here.

## 1. What this repo is

A Spring Cloud (Spring Boot 3.2.5) microservice cluster plus a Vue 3 / Element Plus / Vite frontend. Modules:

| Module | Role |
|---|---|
| `misu-gateway` | Spring Cloud Gateway, entry point on **30260** |
| `misu-account` | auth + user, on **30261**, context-path `/account` |
| `misu-file-server/misu-file-server-biz` | private cloud-drive backend, on **30262**, context-path `/fileServer` |
| `misu-framework` | shared starters (security, redis, etc.) — `misu-security` is the JWT filter chain |
| `misu-net` | Nacos / Cloud common config |
| `misu-bot`, `misu-web` | auxiliary services |
| `misu-file-server-ui` | Vite 5 + Vue 3 SPA, dev server on **5173** |

Other ports: MySQL **3316**, Nacos console **8848** (`nacos`/`nacos`), file-server actuator **30362** (see §5).

## 2. scripts/ — local dev, build, release

All tooling lives under `scripts/`:

| 路径 | 作用 |
|---|---|
| `scripts/dev/dev.sh` | 本地环境编排：`up` / `down` / `status` / `restart <svc>` / `logs <svc>` / `build` / `seed-nacos` / `seed-sql` / `nuke` |
| `scripts/dev/` | `docker-compose.local.yml`、`nacos/*.yml`、`lib/`、`dev-env-README.md`（详细指南） |
| `scripts/build/` | `build-push-*.sh`（公有 Docker Hub）、`build-local-push-*.sh`（私有 registry）、`build-push-ffmpeg-worker.sh` |
| `scripts/deploy/` | `release.sh` 一键发布 + `k8s/misu-server/*.yaml` 清单 + `README.md` |

```bash
scripts/dev/dev.sh up          # docker 中间件 + 3 个 Java 服务 + vite
scripts/dev/dev.sh status
scripts/dev/dev.sh restart file-server
```

**Java + Maven 前置：**
- **JAVA_HOME 指向 JDK 17**：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
- **`mvn` 不在 PATH 上**：用 `/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn`（或 `export MVN=…`，`dev.sh` 认）。
- **Maven 本地仓库是自定义路径** `/Users/renyuming/Documents/develop/maven/repository` —— 直接调 `mvn` 时务必带 `-Dmaven.repo.local=…`。

单模块编译示例：
```bash
/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn \
  -pl misu-file-server/misu-file-server-biz -am compile -DskipTests \
  -Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository
```

**发布**：合并到 master 后，在仓库根执行 `scripts/deploy/release.sh` —— 本地驱动，构建镜像→推私有 registry→SSH 部署到 k8s 集群 + 覆盖前端静态文件，失败自动回滚。支持按需发布：`release.sh misu-gateway`、`release.sh frontend`、`release.sh --dry-run`。配置集中在 `scripts/deploy/deploy.conf`（含 SSH key / IP，已 gitignore）。细节见 `scripts/deploy/README.md`。

## 3. Frontend — npm proxy workaround

The user's `~/.npmrc` has `proxy=http://127.0.0.1:7890` which is frequently **offline** — `npm install` will hang. Always run npm with proxy disabled and registry pinned:

```bash
npm install --proxy=null --https-proxy=null \
  --registry=https://registry.npmjs.org/ --ignore-scripts
```

`git` / `curl` use a different proxy (`http://127.0.0.1:7897`) which **is** typically up, so git clone works.

When `npm install` is blocked by registry-mirror lag for a library, **vendor it**: `git clone --depth=1` into `misu-file-server-ui/src/lib/<lib>/` and import via relative path. Already done for `foliate-js` (used by `EpubViewer.vue`).

## 4. Nacos-driven config — where to find what

Each Spring service has a thin `application.yml` baked into the jar and a **richer override pulled from Nacos at boot**. For local dev:

- Baked-in: `misu-file-server/misu-file-server-biz/src/main/resources/application.yml`
- Nacos override (loaded over the top): `scripts/dev/nacos/misu-file-server-local.yml`

The Nacos config has `spring.jpa.properties.hibernate.hbm2ddl.auto=update` for local dev, so new entities and `@Index` declarations auto-apply on boot. For production, prefer migration DDL — see `docs/file-server-ux-mvp-ddl.md`.

`scripts/dev/dev.sh restart mw` re-seeds the local Nacos with `scripts/dev/nacos/*.yml`.

## 5. file-server module — gotchas baked in from real incidents

Each item below caused a real bug; treat them as defaults, not options.

**5.1 `@ColumnDefault` precision must match Hibernate's generated type.**
For `LocalDateTime` columns Hibernate generates `datetime(6)` — pair it with `@ColumnDefault("CURRENT_TIMESTAMP(6)")`, NOT `"CURRENT_TIMESTAMP"`. MySQL 8 STRICT mode rejects the mismatch with `Invalid default value for 'create_time'`. Canonical pattern: `FileShare`, `FileVersion`, `FileAuditLog`.

**5.2 Actuator runs on a separate port (30362).**
`management.server.port: 30362` avoids a `RequestMappingHandlerMapping` bean clash with `misu-security`'s `PermitAllUrlProperties` (the actuator-added `controllerEndpointHandlerMapping` makes that bean ambiguous on the same port). Also, `misu-security`'s `SecurityConfiguration` must `permitAll("/actuator/**")`, else endpoints 403 even on the management port.

**5.3 `@RequestParam` needs explicit names.**
The project compiles **without** `-parameters`, so Spring 6 throws `Name for argument of type [Integer] not specified`. Always write `@RequestParam("openType") Integer openType`.

**5.4 Composite-index conventions on `file_mapping`.**
Standard predicate is `(open_type, user_id, parent_path, deleted)` plus search-by-name and trash-GC variants; new related entities should match this column order. Don't put `target_virtual_path varchar(1200)` into a composite index — 4× utf8mb4 exceeds the 3072-byte InnoDB key limit.

**5.5 GC + version-history must cascade.**
When a new entity points at `file_mapping`, wire it into both `purgeFromTrash` (manual permanent delete) AND `cleanDeletedFileMappings` (scheduled GC). Existing pattern: `FileVersionService.purgeAllVersionsForMapping(mappingId)`.

**5.6 Permission denial is 403, session expiry is 401.**
Backend permission checks (`checkPublicWriteAuthority` etc.) throw `HttpStatus.FORBIDDEN`. The axios interceptor in `misu-file-server-ui/src/api/request.js` refreshes the token on 401, toasts on 403 — they must never collapse to one branch, or a non-admin touching a public dir wrongly gets booted to `/login`.

## 6. Frontend conventions — what reuses what

- `misu-file-server-ui/src/api/request.js` is the axios instance. **All API calls go through it**, including binary downloads (`responseType: 'arraybuffer'`). It auto-attaches `Authorization: Bearer <token>`. Naked `fetch()` drops auth cross-origin (5173 → 30260) → 401s (the trap the original `EpubViewer.vue` hit).
- Route map: `misu-file-server-ui/src/router/index.js`.
- `useBreakpoint()` (`src/composables/useBreakpoint.js`) gates mobile vs desktop UI.
- Hash uploads: `FileUpload.vue` computes md5 via `spark-md5`, calls `POST /fileServer/file/checkUploadByHash`, falls back to chunked upload on miss.
- EPUB rendering: vendored `src/lib/foliate-js/` (not `epubjs`). Three project-local patches that must survive any upstream re-sync:
  - `view.js` — `loadText` CJK-character-count heuristic across UTF-8 / GB18030 / Big5 so legacy GBK EPUBs render without mojibake; shadowRoot mode `open` for HMR.
  - `paginator.js` — shadowRoot mode `open` for HMR.
  - `pdf.js:1` — `new URL(\`./vendor/pdfjs/\${path}\`, import.meta.url)` (leading `./` added; Vite 5 rejects the unanchored glob without it).
- `vite.config.js` MUST keep `build.target: 'esnext'` — foliate-js uses top-level await, forbidden by Vite's default ES2020 preset.

## 7. Audit log + metrics infra

- `@Audited(action = AuditAction.X, target = "#filePath", openType = "#openType")`, wired by `FileAuditAspect` (Spring AOP): persists rows to `file_audit_log` AND records a Micrometer `Timer` `misu.file.audit.<action>` with `outcome` + `openType` tags. Exposed via `/actuator/prometheus` on **30362**.
- Whole subsystem: `misu-file-server-biz/src/main/java/com/misu/fileServer/audit/`.

## 8. Where to look first

| Question | File |
|---|---|
| login / token refresh | `misu-framework/misu-security/.../JwtAuthenticationFilter.java`, `misu-account/.../AccountController.java` |
| upload merging | `misu-file-server/misu-file-server-biz/.../FileServiceImpl.java` — `mergeChunks`, `saveOrUpdateFileMapping` |
| video room | `misu-file-server/misu-file-server-biz/.../room` — `accessUserFileAsUser(房主.userId, ...)` "act-as-user" pattern |
| path-traversal guard | `misu-file-server/misu-file-server-biz/.../util/FilePathGuard.java` |
| upload rate-limiting | `UploadConcurrencyGuard` + `TranscodeQueueGuard` (config `file.upload.maxConcurrentPerUser`, `video.transcode.maxQueuePerUser`) |
| auto-DDL config | `scripts/dev/nacos/misu-file-server-local.yml` → `hibernate.hbm2ddl.auto=update` |

## 9. Things NOT to do

- Don't `npm install` without the proxy/registry flags from §3 — it hangs on 127.0.0.1:7890.
- Don't push to `master` directly. PR-only.
- Don't change `MAVEN_REPO_LOCAL` / `JAVA_HOME` in scripts — per-machine env, not committed.
- Don't put `target_virtual_path` into a composite index (§5.4).
- Don't add Spring entities without `@Index` declarations matching existing patterns — `hbm2ddl=update` only ADDS indexes, never repairs a bad column order.
- Don't bypass the axios `request` instance for fetching files — auth drops (§6).
