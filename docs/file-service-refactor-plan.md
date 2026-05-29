# 文件服务重构方案（FileServiceImpl 拆分 + 聊天文件复用）

> 状态：**待审**。本文只描述方案，未改任何代码。
> 决策前提（已与需求方确认）：① 先出书面方案再实现；② 走「重划接口边界」（controller 跟着改）；③ 聊天复用走「抽底层机制到 misu-framework 共享」。

## 0. 背景与目标

- `FileServiceImpl` 已 **2221 行 / 一个类 8 类职责**，`FileService` 接口 **30+ 方法**，难读难测难维护。
- `misu-chat` 的 `ChatFileServiceImpl`（430 行）**自带一套分片上传 + 合并**逻辑，与 file-server 重复；聊天下载用 `ResponseEntity<byte[]>`（整文件进内存、**无 Range / 无 ETag**），拖累图片/视频查看器体验。
- 目标：**接口按职责切清**、**单类瘦身可读**、**跨模块去重**，且不破坏 `push master → 自动上生产` 这条链路。

### 不在本次范围（明确排除，避免 scope 膨胀）
- 不改 file-server 的存储模型（虚拟路径树 + `file_mapping` 表 + openType 公私域 + 配额 + 秒传 + 回收站）。
- 不改聊天的存储模型（按 conversationId 平铺 + `chat_file` 一行元数据 + 软删）。
- 不合并两套库 / 两个部署。聊天**不**走 file-server 的 `file_mapping` 体系（已确认采用「抽底层机制」而非「聊天文件走 file-server 存储」）。

---

## 1. 现状诊断（依据代码实测）

### 1.1 FileServiceImpl 职责分布

| 职责块 | 代表方法 | 约略行数 | 谁调 |
|---|---|---|---|
| 列表 / DTO 组装 | `getFileList` `toFileResponseDto` `packagePreviewLink` `packageVideoTranscodeInfo` | ~100 | FileController |
| 下载链接 + token | `getFileDownloadLink` `createUserFileAccessLink` `resolveTokenFile` | ~80 | FileController |
| **HTTP 写响应（Range/ETag/断点/304）** | `writeFileToResponse` `generateETag` `isNotModified` | ~140 | 内部 |
| 上传（分片/合并/秒传/续传） | `uploadFile` `mergeChunks` `allChunksUploaded` `checkUploadByHash` `getUploadStatus` | ~250 | FileController |
| file_mapping CRUD + 虚拟路径树 | `saveOrUpdateFileMapping` `moveFileMappingTree` `markDeletedByPrefix` `walkAndMap*` `hasMapping*Conflict` | ~300 | 内部 |
| 回收站 / 批量 | `listTrash` `restoreFromTrash` `purgeFromTrash` `batchDelete` `batchMove` | ~250 | FileController |
| 存储用量 / 配额 / 管理员视角 | `getStorageUsage(AsAdmin)` `listFilesAsAdmin` | ~120 | FileController + FileAdminController |
| staging 物理共享 + 定时 GC + backfill | `listStaging` `shareStaging*` `cleanExpired*`（3 个 `@Scheduled`）`doBackfillFileMapping` | ~400 | FileAdminController + 定时器 |
| 跨用户取文件 + 文本编辑 | `accessUserFileAsUser` `transcodedVideoFileAsUser` `existsUserFile` `getTextContent` `saveTextContent` | ~150 | WebDav/FileShare/VideoRoom + FileController |

### 1.2 调用方接缝（重划接口的依据）

| 调用方 | 当前用到的 FileService 方法 |
|---|---|
| `FileController` | 24 个（用户面全集：list/link/download/upload/move/delete/trash/batch/search/text/usage/preview/transcoded） |
| `FileAdminController` | 8 个：`listFilesAsAdmin` `getStorageUsageAsAdmin` `getStagingRoot` `listStaging` `shareStagingToPublic` `shareStagingToUser` `startFileMappingBackfill` `getFileMappingBackfillStatus` |
| `WebDavServiceImpl` | `accessUserFileAsUser` |
| `FileShareServiceImpl` | `accessUserFileAsUser` |
| `VideoRoomServiceImpl` | `accessUserFileAsUser` `transcodedVideoFileAsUser` `existsUserFile` |
| `TorrentServiceImpl` | `addFileInk` |

> 结论：跨模块/内部调用方只依赖「跨用户取文件原语 + addFileInk」这一小撮，**天然可独立成接口**；剩下的庞大用户面集中在 `FileController` 一处，重划风险可控。

### 1.3 chat 与 file-server 的重复

- 分片上传：chat `saveUploadedChunk`（part 文件 + per-uploadId 合并锁 + 全片到齐顺序合并）≈ file-server `mergeChunks`/`allChunksUploaded`/`uploadMergeLocks`。**机制重复，寻址/秒传策略不同**。
- HTTP 下载：chat 无 Range/ETag（`byte[]` 全量）；file-server 有完整实现。**chat 缺失，可直接复用补齐**。

---

## 2. 目标架构

### 2.1 共享层（misu-framework / misu-web-starter，`com.misu.framework.*`）

> 两个 biz 模块都已依赖 `misu-web-starter` + `misu-common`，且 web-starter 已有 `com.misu.framework.util.FileUtils`，是共享机制的天然归宿。**只抽「与业务无关的纯机制」**，存储模型与元数据持久化仍由各模块自管。

新增两个组件（无状态、可注入）：

1. **`HttpFileResponder`**（`com.misu.framework.web`）
   把 file-server 现有的 `writeFileToResponse + generateETag + isNotModified` 整体迁来、泛化为通用工具：
   ```
   void write(HttpServletResponse resp, HttpServletRequest req, File file,
              String downloadName, String mimeType, boolean attachment)
   ```
   职责：Range 解析、`Accept-Ranges`、`ETag`、`If-None-Match` / `If-Modified-Since` → 304、`Content-Disposition`、分块写出。
   - file-server：`FileAccessServiceImpl` 内部改调它（行为完全等价，已是同一份逻辑迁移）。
   - chat：`download` 从 `ResponseEntity<byte[]>` 改为直接写 `HttpServletResponse`，**白捡 Range + 断点 + 304**（直接改善图片/视频查看器）。

2. **`ChunkedUploadAssembler`**（`com.misu.framework.upload`）
   只做最低层「分片落盘 + per-uploadId 合并锁 + 全片到齐检测 + 顺序合并到目标文件」：
   ```
   void storeChunk(Path chunkDir, int index, MultipartFile part)
   boolean allPresent(Path chunkDir, int total)
   long mergeTo(Path chunkDir, int total, Path target)   // 带锁，合并后清理
   ```
   - chat：`saveUploadedChunk` 的合并段直接换成它。
   - file-server：`UploadServiceImpl` 在它之上叠加 **MD5 校验 / 哈希秒传 / 续传探测**（这些是 file-server 专属策略，不下沉）。

> 路径穿越防护：file-server 的 `FilePathGuard` 是虚拟路径专用，**不下沉**；chat 侧 uploadId 清洗保持现状（可选：抽一个极薄的 `sanitizeSegment` 到 common，优先级低）。

### 2.2 file-server 接口重划

`FileServiceImpl` 解体为下列接口 + impl（按 §1.2 接缝切）：

| 新接口 | 承接方法 | 主要调用方 |
|---|---|---|
| `FileService`（瘦身保留：用户写操作 + 列表查询） | `getFileList` `searchFiles` `uploadFile` `addFileInk` `createDirectory` `moveFile` `deleteFile` `batchDelete` `batchMove` `sharePrivateFileToPublic` `getTextContent` `saveTextContent` `getStorageUsage` `getFileDownloadLink` `getUploadStatus` `checkUploadByHash` | FileController、Torrent(`addFileInk`) |
| `FileAccessService`（取字节 / 流式服务 / 跨用户原语） | `downloadFile` `accessUserFile` `accessUserFileAsUser` `previewFile` `videoPreviewFile` `transcodedVideoFile` `transcodedVideoFileAsUser` `existsUserFile` `downloadDirectoryAsZip` | FileController、WebDav、FileShare、VideoRoom |
| `FileTrashService` | `listTrash` `restoreFromTrash` `purgeFromTrash` | FileController |
| `FileAdminService` | `listFilesAsAdmin` `getStorageUsageAsAdmin` `getStagingRoot` `listStaging` `shareStagingToPublic` `shareStagingToUser` `startFileMappingBackfill` `getFileMappingBackfillStatus` | FileAdminController |
| `FileMaintenanceService`（内部，无 controller） | 3 个 `@Scheduled`（`cleanExpiredTmpFiles` `cleanDeletedFileMappings` `cleanExpiredUploadLocks`）+ `doBackfillFileMapping` 执行体 | 定时器 / FileAdminService 触发 |

内部协作组件（**非对外接口，纯 @Component，去重核心**）：

- **`FileMappingManager`** — `file_mapping` CRUD + 虚拟路径树：`saveOrUpdateFileMapping` `moveFileMappingTree` `markDeletedByPrefix` `walkAndMap*` `hasMapping*Conflict` `cloneMappingSubtreeToPublic`。被 upload/move/delete/staging/backfill 共用。
- **`FilePathResolver`** — 路径常量 + `getUserRootDirectory` `getMappingUserId` `resolveUserRequestFile` `resolveMappedFile` `buildUploadStorageFile` `getParentPath`。
- **`UploadService`**（可并入 `FileService` impl 或独立）— 包 `ChunkedUploadAssembler` + MD5/秒传/续传。
- `getStorageUsage` / `getStorageUsageAsAdmin` 复用同一私有算量逻辑（去重）。

> 切分原则：**facade 不保留**（已选「重划边界」）。`FileServiceImpl` 不再作为大门面，按上表拆成多个聚焦 impl，各自只注入自己需要的协作组件。

### 2.3 controller 跟随调整

- `FileController` 字段 `fileService` → 拆为 `fileService` + `fileAccessService` + `fileTrashService` 三个注入，方法体逐个改调对应 service（签名/路由/返回不变，**对前端零感知**）。
- `FileAdminController` → 注入 `fileAdminService`。
- `WebDavServiceImpl` / `FileShareServiceImpl` / `VideoRoomServiceImpl` → 注入 `fileAccessService`。
- `TorrentServiceImpl` → 仍注入 `fileService`（`addFileInk` 留在 `FileService`）。

---

## 3. 迁移顺序（小步可回滚，每步可独立编译 + 验证）

> 关键约束：**push master 即上生产**，所以每个 PR 都要能独立编译、行为等价、可单独合并。

1. **Step 1 — 共享 `HttpFileResponder` 落地**
   - 在 web-starter 新增组件，把 file-server 三个方法逻辑迁入。
   - file-server `FileServiceImpl` 内部改调（行为等价）。验证现有下载/Range/预览不回归。
2. **Step 2 — chat 下载切到 `HttpFileResponder`**
   - `ChatController.downloadFile` 改为写 `HttpServletResponse`，`ChatFileServiceImpl.download` 返回文件句柄而非 `byte[]`。
   - 验证聊天图片/文件下载 + 图片查看器 Range（桌面 + 移动两端截图）。
3. **Step 3 — 共享 `ChunkedUploadAssembler` 落地 + chat 接入**
   - chat `saveUploadedChunk` 合并段替换。验证聊天分片上传（乱序/并发/断点）。
4. **Step 4 — file-server 内部抽 `FileMappingManager` / `FilePathResolver` / 上传组件**
   - 纯内部重构，`FileService` 接口与 impl 行为不变，先把 god class 的私有逻辑外移。
5. **Step 5 — 拆接口 `FileAccessService` / `FileTrashService` / `FileAdminService` / `FileMaintenanceService`**
   - 同步改 controller 注入与各内部调用方。每拆一个接口一个 PR。
6. **Step 6 — `FileServiceImpl` 收尾瘦身**，移除已外迁逻辑，复核三个 `@Scheduled` 归位 `FileMaintenanceService`。

> 也可把 Step 1–3（聊天复用）与 Step 4–6（file-server 拆分）作为两条独立线并行/分先后，互不阻塞。

---

## 4. 风险与红线（每条都对应 CLAUDE.md 既有坑）

- **事务边界**：`FileServiceImpl` 多方法带 `@Transactional`；拆 service 后跨 service 调用的事务传播要核对（同库默认 `REQUIRED` 可，但 self-invocation 失效问题在拆类后**反而更安全**，原本类内私有调用不走代理的，拆出后需确认是否需要事务）。逐方法核对。
- **`@Scheduled` 归属**：迁到 `FileMaintenanceService` 后必须仍被 Spring 扫描为 bean，cron 表达式 / `@Value` 默认值原样带走。
- **`@RequestParam` 显式名**：controller 改注入不动参数注解，保持显式 `("name")`。
- **chat download 改流式**：`ChatController` 原签名 `ResponseEntity<byte[]>` 要改为 void + 直写 response；前端 axios（`responseType:'arraybuffer'`）行为需回归验证，确认 `Authorization` 透传不受影响。
- **不引入新 maven 依赖**：共享组件放已有的 `misu-web-starter`，不新建模块（避免触发 §改代码必须保证 auto-deploy 跑通 的「加新模块」连锁改造）。
- **共享面属于全部 Java 服务触发点**：改 `misu-framework/**` 会触发**全部 Java 服务**重新构建部署（见 CLAUDE.md），合并前确认 file-server / chat 都能起。
- **hbm2ddl**：本次不新增实体 / 不动 `@Index`，无 DDL 影响。

---

## 5. 验收

- 编译：单模块 `mvn -pl ... -am compile -DskipTests`（file-server-biz、chat-biz、web-starter）。
- 行为回归（本地 dev + verifybot/verifyadmin）：
  - file-server：上传（含分片/秒传/续传）、下载（含 Range/断点/304）、预览、移动、删除、回收站还原/彻底删除、批量、staging 共享、搜索、文本编辑、存储用量、管理员视角。
  - chat：上传（单次 + 分片）、文件列表、下载、图片查看器（移动端滑动/缩放 + 桌面拖动切图，吃 Range）。
- 浏览器双视口截图（1280×800 + 414×800）覆盖聊天文件/图片路径。
- 每步独立编译通过 + 验证后再合并（auto-deploy 红了会自动 rollback，但不留红 release）。

---

## 6. 待你确认的开放点

1. **接口命名**：上表用了 `FileAccessService` / `FileTrashService` / `FileAdminService` / `FileMaintenanceService`，是否合你口味？（也可换 `FileServeService` / `TrashService` 等）
2. **拆分粒度**：`FileTrashService` 要不要并进 `FileService`（回收站本质也是用户写操作）？还是保持独立更清晰？
3. **`getTextContent`/`saveTextContent`**：留在 `FileService` 还是单独 `FileTextEditService`？（量小，倾向留 `FileService`）
4. **PR 拆法**：按 §3 的 6 步逐 PR 合并（每步一次 auto-deploy），还是聚成 2 个大 PR（聊天复用线 / file-server 拆分线）？
5. **聊天下载改流式**：是否同意把 `ChatController.downloadFile` 从 `ResponseEntity<byte[]>` 改为直写 response（前端需回归，但能补齐 Range）？若你想保守，可只做去重不改下载签名。
