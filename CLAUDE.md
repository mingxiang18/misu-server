# misu-server — project knowledge for Claude sessions

This file is auto-loaded into every Claude session that runs in this repo.
It captures the project-specific facts that are not derivable from `git log` or a 30-second tour, so each new session can act competently from minute one. Generic workflow lessons live in the `ai-product-workflow` skill, not here.

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

Other ports: MySQL **3316**, Nacos console **8848** (admin/admin: `nacos`/`nacos`), file-server actuator **30362** (see §5).

## 2. Local environment — the only commands you need

The repo has a `dev.sh` orchestrator and a `dev-env-README.md` long-form guide. Default path:

```bash
./dev.sh up          # docker mw + 3 Java services + vite
./dev.sh status
./dev.sh logs gateway
./dev.sh restart file-server
./dev.sh down
```

Java + Maven prerequisites:

- **JAVA_HOME must point at JDK 17** (`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`).
- **`mvn` is NOT on PATH on this machine.** Use one of:
  - `/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn`
  - or `export MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"` (recognized by `dev.sh`)
- **Maven local repo is custom**: `/Users/renyuming/Documents/develop/maven/repository`. Always pass `-Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository` when invoking `mvn` directly. `dev.sh build` will translate `MAVEN_REPO_LOCAL=…` env into that flag.

Example single-module compile:

```bash
/Users/renyuming/Documents/develop/maven/apache-maven-3.6.3/bin/mvn \
  -pl misu-file-server/misu-file-server-biz -am compile -DskipTests \
  -Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository
```

## 3. Frontend — npm proxy workaround

The user's `~/.npmrc` is configured with `proxy=http://127.0.0.1:7890` which is frequently **offline**. `npm install` will hang or fail.

Always run npm with proxy disabled and the public registry pinned:

```bash
npm install --proxy=null --https-proxy=null \
  --registry=https://registry.npmjs.org/ --ignore-scripts
```

`git` and `curl` use a different proxy (`http://127.0.0.1:7897`) which **is** typically up. So git clone works without intervention.

When `npm install` is blocked by registry-mirror lag for a specific library, **vendor it**: `git clone --depth=1` into `misu-file-server-ui/src/lib/<lib>/` and import via relative path. Already done for `foliate-js` (used by `EpubViewer.vue`).

## 4. Nacos-driven config — where to find what

Each Spring service has a thin `application.yml` baked into the jar and a **richer override pulled from Nacos at boot**. For local dev:

- Baked-in: `misu-file-server/misu-file-server-biz/src/main/resources/application.yml`
- Nacos override (loaded over the top): `scripts/dev/nacos/misu-file-server-local.yml`

The Nacos config has `spring.jpa.properties.hibernate.hbm2ddl.auto=update` enabled for local dev so new entities and `@Index` declarations auto-apply on boot. For production, prefer migration DDL — see `docs/file-server-ux-mvp-ddl.md` for an example.

`./dev.sh restart mw` re-seeds the local Nacos with the contents of `scripts/dev/nacos/*.yml`.

## 5. file-server module — gotchas baked in from real incidents

Each item below caused a real bug; future Claude should treat them as defaults, not options.

**5.1 `@ColumnDefault` precision must match Hibernate's generated type.**
For `LocalDateTime` columns, Hibernate generates `datetime(6)`. Pair it with `@ColumnDefault("CURRENT_TIMESTAMP(6)")` — NOT `"CURRENT_TIMESTAMP"`. MySQL 8 in STRICT mode rejects the precision mismatch with `Invalid default value for 'create_time'`. See `FileShare`, `FileVersion`, `FileAuditLog` entities for the canonical pattern.

**5.2 Actuator runs on a separate port (30362).**
Setting `management.server.port: 30362` in `application.yml` avoids a `RequestMappingHandlerMapping` bean clash with `misu-security`'s `PermitAllUrlProperties.afterPropertiesSet` (which calls `applicationContext.getBean(RequestMappingHandlerMapping.class)` — the actuator-added `controllerEndpointHandlerMapping` makes that bean ambiguous on the same port). Plus, `misu-security`'s `SecurityConfiguration` must `permitAll("/actuator/**")`, otherwise endpoints return 403 even on the management port.

**5.3 `@RequestParam` needs explicit names.**
The project compiles **without** `-parameters`, so reflection can't infer parameter names. Spring 6 will throw `Name for argument of type [Integer] not specified`. Always write `@RequestParam("openType") Integer openType`, not `@RequestParam Integer openType`.

**5.4 Composite-index conventions on `file_mapping`.**
Standard predicate is `(open_type, user_id, parent_path, deleted)` plus search-by-name and trash-GC variants. New entities related to file_mapping should match this column order. Avoid putting `target_virtual_path varchar(1200)` into a composite index — 4× utf8mb4 exceeds the 3072-byte InnoDB key limit. (Caught while building `file_audit_log` indexes.)

**5.5 GC + version-history must cascade.**
When you add a new entity that points at `file_mapping`, wire it into both `purgeFromTrash` (manual permanent delete) AND `cleanDeletedFileMappings` (scheduled GC) so orphans don't pile up. `FileVersionService.purgeAllVersionsForMapping(mappingId)` is the existing pattern.

**5.6 Permission denial is 403, session expiry is 401.**
Backend permission checks (`checkPublicWriteAuthority` and similar) throw `HttpStatus.FORBIDDEN`. The frontend axios interceptor in `misu-file-server-ui/src/api/request.js` refreshes the token on 401 and toasts on 403 — they must never collapse to the same branch, or "non-admin user touches public dir" wrongly boots the user to `/login`.

## 6. Frontend conventions — what reuses what

- `misu-file-server-ui/src/api/request.js` is the axios instance. **All API calls go through it**, including for binary downloads (`responseType: 'arraybuffer'`). It auto-attaches `Authorization: Bearer <token>`. Naked `fetch()` will drop auth on cross-origin (5173 → 30260) and you'll get 401s — the original `EpubViewer.vue` hit exactly this trap.
- Route map: `misu-file-server-ui/src/router/index.js` — file pages, viewers (`PdfViewer`, `TextViewer`, `EpubViewer`), shared download page, audit log page.
- `useBreakpoint()` (`src/composables/useBreakpoint.js`) gates mobile vs desktop UI.
- Hash uploads: `FileUpload.vue` computes md5 via `spark-md5`, calls `POST /fileServer/file/checkUploadByHash`, falls back to chunked upload when miss.
- EPUB rendering: vendored `src/lib/foliate-js/` (not `epubjs`). Three project-local patches that must survive any future upstream re-sync:
  - `view.js` — `loadText` runs a CJK-character-count heuristic across UTF-8 / GB18030 / Big5 so legacy GBK EPUBs render without mojibake; shadowRoot mode flipped to `open` for HMR debugging.
  - `paginator.js` — shadowRoot mode flipped to `open` for HMR debugging.
  - `pdf.js:1` — `new URL(\`./vendor/pdfjs/\${path}\`, import.meta.url)` (leading `./` added; upstream omits it, which Vite 5 rejects as an unanchored glob).
- `vite.config.js` MUST keep `build.target: 'esnext'` — foliate-js uses top-level await, which the default Vite `modules` preset (ES2020) forbids.

## 7. Audit log + metrics infra

- `@Audited(action = AuditAction.X, target = "#filePath", openType = "#openType")` annotation, wired by `FileAuditAspect` (Spring AOP), persists rows to `file_audit_log` AND records a Micrometer `Timer` named `misu.file.audit.<action>` with `outcome` + `openType` tags. Exposed via `/actuator/prometheus` on **30362**.
- `misu-file-server-biz/src/main/java/com/misu/fileServer/audit/` is the whole subsystem.

## 8. Where to look first

| Question | File |
|---|---|
| "How does login / token refresh work?" | `misu-framework/misu-security/.../JwtAuthenticationFilter.java`, `misu-account/.../AccountController.java` |
| "How does upload merging work?" | `misu-file-server/misu-file-server-biz/.../FileServiceImpl.java`, `mergeChunks` and `saveOrUpdateFileMapping` |
| "How does the video room work?" | `misu-file-server/misu-file-server-biz/.../room` — uses `accessUserFileAsUser(房主.userId, ...)` "act-as-user" pattern, parallel to but independent of the external-share path |
| "Where's the path-traversal guard?" | `misu-file-server/misu-file-server-biz/.../util/FilePathGuard.java` |
| "How are uploads rate-limited?" | `UploadConcurrencyGuard` + `TranscodeQueueGuard` (Semaphore-based, config keys `file.upload.maxConcurrentPerUser` and `video.transcode.maxQueuePerUser`) |
| "What's the auto-DDL config?" | `scripts/dev/nacos/misu-file-server-local.yml` → `spring.jpa.properties.hibernate.hbm2ddl.auto=update` |

## 9. Things NOT to do

- Don't `npm install` without the proxy/registry flags from §3 — it will hang on 127.0.0.1:7890.
- Don't push to `master` directly. PR-only.
- Don't change `MAVEN_REPO_LOCAL` or `JAVA_HOME` in scripts — they're per-machine env, not committed.
- Don't put `target_virtual_path` into a composite index (§5.4).
- Don't add Spring entities without `@Index` declarations matching the existing patterns — `hbm2ddl=update` will only ADD indexes, never repair a bad column order chosen later.
- Don't bypass the axios `request` instance for fetching files — auth will drop (§6).
