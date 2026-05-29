# 文件服务重构 —— 详细设计

> 配套总方案见 [file-service-refactor-plan.md](file-service-refactor-plan.md)。本文是**可执行的详细设计**：把工作切成两个边界清晰、可独立处理、互不重合的 PR，并把每个类/方法的归属定到位。
>
> 已确认决策：
> - 聊天下载**改流式**（`ResponseEntity<byte[]>` → 直写 `HttpServletResponse`，补齐 Range）。
> - 聚成**两个大 PR**：PR-A 聊天复用线、PR-B file-server 拆分线。
> - **PR-A 不碰 file-server**；file-server 切共享组件放在 PR-B（PR-A 合并后 file-server 会短暂与 framework 重复一份 serving 代码，由 PR-B 删除）。
> - 回收站 → 独立 `FileTrashService`；文本编辑 → 独立 `FileTextService`。

## 0. 模块改动边界（保证两 PR 不重合）

| 模块 | PR-A 聊天复用线 | PR-B file-server 拆分线 |
|---|---|---|
| `misu-framework/misu-web-starter` | **新增** 2 个组件文件（HttpFileResponder、ChunkedUploadAssembler） | **只引用，不改** 这两个文件 |
| `misu-chat` | 改 ChatController + ChatFileServiceImpl | 不动 |
| `misu-file-server` | **不动** | 全部改动集中在这里 |

> 唯一交集是 `misu-web-starter`：PR-A 在其中**新建文件**，PR-B 仅**消费**这些文件、不编辑它们 → **零行级重合**。依赖顺序：**PR-A 先合并，PR-B 后合并**（PR-B 用到 PR-A 新增的共享组件）。

> ⚠️ 改 `misu-framework/**` 会触发 CI 重建**全部 Java 服务**（CLAUDE.md）。PR-A 合并即意味着 file-server / account / chat 全部重新部署，合并前需确认三者都能起（共享组件是纯新增、无人调用，风险低，但仍要看 CI 绿）。

---

## PR-A：聊天复用线（misu-framework + misu-chat）

### A1. 新增 `HttpFileResponder`（`com.misu.framework.web`）

把 file-server 现有 `writeFileToResponse` + `generateETag` + `isNotModified`（FileServiceImpl.java:773-925）**逻辑等价迁出**为通用组件。file-server 此 PR 不动，仍用自己的副本；chat 直接用新组件。

```java
@Component
public class HttpFileResponder {
    /** 流式写出本地文件，支持 Range / ETag / If-None-Match / If-Modified-Since(304) / Content-Disposition。 */
    void write(HttpServletRequest req, HttpServletResponse resp,
               File file, String downloadName, String mimeType, boolean attachment);

    /** 已在内存的小字节（聊天旧 base64 兜底用），单次写出，不做 Range。 */
    void writeBytes(HttpServletResponse resp, byte[] bytes, String downloadName, String mimeType, boolean attachment);
}
```

- 行为与 file-server 现版完全一致（同一份代码迁移），不新造逻辑。
- `attachment=false` → `Content-Disposition: inline`（图片 `<img>` 内联）；`true` → `attachment`。

### A2. 新增 `ChunkedUploadAssembler`（`com.misu.framework.upload`）

只做与业务无关的「分片落盘 + 按 uploadId 合并锁 + 全片到齐检测 + 顺序合并」。MD5/秒传/续传等策略**不进**这里（属各模块）。

```java
@Component
public class ChunkedUploadAssembler {
    void storeChunk(Path chunkDir, int index, MultipartFile part);   // 写 part{index}
    boolean allPresent(Path chunkDir, int total);                    // 0..total-1 是否齐
    /** 带 per-key 合并锁；齐则顺序合并到 target，返回字节数，并清理 chunkDir。未齐返回 -1。 */
    long mergeIfComplete(String mergeKey, Path chunkDir, int total, Path target);
}
```

- 合并锁机制来自 chat 现有 `mergeLocks` / file-server 现有 `uploadMergeLocks`，统一到组件内 `ConcurrentHashMap`，键 = `mergeKey`（chat 用清洗后的 uploadId）。
- chunkDir 的清洗（防穿越）由调用方决定路径，组件不负责语义。

### A3. 改 `ChatFileServiceImpl`

- `saveUploadedChunk`（ChatFileServiceImpl.java:115-194）合并段替换为 `assembler.mergeIfComplete(...)`，删掉本类的 `mergeLocks`、手写合并循环、`deleteQuietly`（合并清理移入组件）。落盘 part 用 `assembler.storeChunk`。
- 内部 `FileDownload` 结构调整以支持流式：磁盘文件不再预读 `byte[]`，改携带 `File`（或可解析出 File 的相对路径）；`netUrl` / 旧 base64 兜底保留。
  ```java
  class FileDownload {
      String fileName; String mimeType;
      File diskFile;     // 磁盘文件 → 走 HttpFileResponder.write
      String netUrl;     // 外链 → 302
      byte[] bytes;      // 旧 base64 兜底 → writeBytes
  }
  ```
- `download(fileId)` 的三分支（ChatFileServiceImpl.java:371-412）：磁盘→`diskFile`；外链→`netUrl`；旧 base64→`bytes`。

### A4. 改 `ChatController.downloadFile`（ChatController.java:268-294）

签名 `ResponseEntity<byte[]>` → `void`，注入 `HttpServletRequest req, HttpServletResponse resp`：

```
鉴权同现状（成员校验、deleted 校验）
d = download(fileId)
if d == null            → resp.sendError(404)
else if d.netUrl != null → resp.sendRedirect(d.netUrl) (302)
else inline = "image".equals(category)
     if d.diskFile != null → httpFileResponder.write(req, resp, d.diskFile, d.fileName, d.mimeType, !inline)
     else if d.bytes != null → httpFileResponder.writeBytes(resp, d.bytes, d.fileName, d.mimeType, !inline)
```

> 注意保持 `@RequestParam`/`@PathVariable` 显式命名（项目硬规则）。前端 axios 仍 `responseType:'arraybuffer'`，鉴权头透传不变。

### A5. PR-A 验收

- 编译：`mvn -pl misu-framework/misu-web-starter -am compile`、`-pl misu-chat/misu-chat-biz -am compile`。
- chat：单次上传、分片上传（乱序/并发/断点续传到齐）、文件列表、下载（磁盘/外链/旧 base64 三分支）、图片查看器移动+桌面双端（Range 拖动定位）。双视口截图（1280×800 + 414×800）。
- CI：合并前确认 file-server/account/chat 全部构建绿（共享组件改动触发全量）。

---

## PR-B：file-server 拆分线（仅 misu-file-server）

`FileServiceImpl`（2221 行）解体。目标接口 6 个 + 内部协作组件若干。**所有公开方法签名、controller 路由、返回结构对前端零变化**，只改内部归属与注入。

### B1. 目标接口与方法归属（公开方法全覆盖）

| 接口 | 方法 | 调用方 |
|---|---|---|
| **FileService**（瘦身：用户写 + 查询 + 上传） | `getFileList` `searchFiles` `uploadFile` `addFileInk` `createDirectory` `moveFile` `deleteFile` `batchDelete` `batchMove` `sharePrivateFileToPublic` `getStorageUsage` `getUploadStatus` `checkUploadByHash` | FileController、Torrent(`addFileInk`) |
| **FileAccessService**（取字节/流式/跨用户/链接） | `getFileDownloadLink` `downloadFile` `accessUserFile` `accessUserFileAsUser` `previewFile` `videoPreviewFile` `transcodedVideoFile` `transcodedVideoFileAsUser` `existsUserFile` `downloadDirectoryAsZip` | FileController、WebDav、FileShare、VideoRoom |
| **FileTrashService** | `listTrash` `restoreFromTrash` `purgeFromTrash` | FileController |
| **FileTextService** | `getTextContent` `saveTextContent` | FileController |
| **FileAdminService** | `listFilesAsAdmin` `getStorageUsageAsAdmin` `getStagingRoot` `listStaging` `shareStagingToPublic` `shareStagingToUser` `startFileMappingBackfill` `getFileMappingBackfillStatus` | FileAdminController |
| **FileMaintenanceService**（内部，无 controller） | `cleanExpiredTmpFiles` `cleanDeletedFileMappings` `cleanExpiredUploadLocks`（3×`@Scheduled`）+ `runBackfill()` 执行体 | 定时器；`startFileMappingBackfill` 委托它 |

> `getFileDownloadLink` 与 `downloadFile`（token 下载）成对，连同私有 `resolveTokenFile`/`createFileDownloadLink`/`createUserFileAccessLink` 一并归 `FileAccessService`。

### B2. 内部协作组件（@Component，去重核心；非对外接口）

| 组件 | 承接私有方法 | 被谁注入 |
|---|---|---|
| **FilePathResolver** | 路径常量(`PUBLIC_/PRIVATE_/PREVIEW_/TMP_/STORAGE_/VIRTUAL_DIRECTORY_`)、`getUserRootDirectory` `getMappingUserId` `getParentPath` `resolveUserRequestFile`×2 `resolveMappedFile` `buildUploadStorageFile` `buildVirtualDirectoryStorage` `getPreviewFile` `initFileDirectory` | 几乎所有 file 服务 |
| **FileMappingManager** | `saveOrUpdateFileMapping`×2 `markDeletedByPrefix` `moveFileMappingTree` `mapPhysicalTreeToVirtualPaths` `walkAndMapChildren` `hasMappingUnderPath` `hasMappingParentConflict` `hasMoveDestinationConflict` `cloneMappingSubtreeToPublic` `savePublicFileMapping` | FileService、FileTrash、FileAdmin、FileMaintenance |
| **FileResponseAssembler** | `toFileResponseDto` `packagePreviewLink` `packageVideoTranscodeInfo` `getFileListFromDirectory` `toTrashResponseDto` | FileService(查询)、FileAdmin、FileTrash |
| **FileAuthorityChecker** | `checkPublicWriteAuthority` `checkAdminViewAuthority` `ensureMappingOwnership` | 写操作 / admin / trash |
| **ChunkUploadSupport** | `checkUploadChunk` `allChunksUploaded` `mergeChunks` `bytesToHex` `fileAddAfter`（包 PR-A 的 `ChunkedUploadAssembler` + MD5/秒传/续传/MD5 校验） | FileService(上传) |
| **PhysicalFileOps** | `deleteFile(File)` `deletePhysicalRecursively` `ensureDirectoryExists` `getDirectoryDownloadStat` `checkDirectoryDownloadLimit` `fileDeleteAfter` | FileAccess(zip/下载)、FileTrash、FileMaintenance |
| **StagingSupport**（可并入 FileAdminServiceImpl） | `resolveStagingRoot` `toStagingEntry` `resolveStagingSource` `resolveStagingTargetVirtualPath` `ensurePublicTargetDirectoryAvailable` `ensurePrivateTargetDirectoryAvailable` | FileAdmin |

backfill 状态字段（`backfillRunning` / 计数器 / 起止时间 / `doBackfillFileMapping` / `upsertTree` / `walkAndUpsert`）随 `FileMaintenanceService` 走。

> HTTP 写响应（`writeFileToResponse`/`generateETag`/`isNotModified`/`writeTranscodedVideoToResponse`）→ `FileAccessServiceImpl` 改调 **PR-A 的 `HttpFileResponder`**，删除 file-server 本地副本（这是 PR-B 才做、PR-A 不碰的点）。`writeTranscodedVideoToResponse` 仍可保留为 access 内私有（它有转码视频特殊处理），或也走 responder——以行为等价为准。

### B3. controller / 调用方改注入

| 文件 | 改动 |
|---|---|
| `FileController` | 拆注入：`fileService` + `fileAccessService` + `fileTrashService` + `fileTextService`，逐方法改调对应 service。路由/参数/返回不变。 |
| `FileAdminController` | 注入 `fileAdminService`。 |
| `WebDavServiceImpl` / `FileShareServiceImpl` | `fileService.accessUserFileAsUser` → `fileAccessService.accessUserFileAsUser`。 |
| `VideoRoomServiceImpl` | 三个方法 → `fileAccessService`。 |
| `TorrentServiceImpl` | `addFileInk` 仍在 `FileService`，无需改类型（注入名不变）。 |

### B4. PR-B 内部推进顺序（一个 PR 内分 commit，便于回溯）

1. 抽 `FilePathResolver` + `FileAuthorityChecker`（无状态、依赖少，先抽）。
2. 抽 `FileMappingManager` + `FileResponseAssembler` + `PhysicalFileOps`。
3. 抽 `ChunkUploadSupport`，接 PR-A 的 `ChunkedUploadAssembler`，删 file-server 旧合并锁/合并循环。
4. 落 `FileMaintenanceService`（迁 3 个 `@Scheduled` + backfill 执行体与状态）。
5. 落 `FileAdminService`（+StagingSupport），改 `FileAdminController`。
6. 落 `FileAccessService`，内部改调 `HttpFileResponder`，删本地 serving 副本；改 WebDav/FileShare/VideoRoom 注入。
7. 落 `FileTrashService` + `FileTextService`，改 `FileController`。
8. `FileServiceImpl` 收尾，只剩用户写 + 查询 + 上传委托。

### B5. PR-B 风险点（逐条对照 CLAUDE.md）

- **事务边界**：原类内私有调用（self-invocation）不经 Spring 代理、`@Transactional` 不生效的隐患，拆成独立 bean 后反而修正——需逐方法核对原本期望的事务语义，避免「原来没事务、现在跨 bean 加了事务」或反之改变行为。重点：`uploadFile`/`moveFile`/`batchMove`/`purgeFromTrash`/staging 共享。
- **`@Scheduled` 归位**：迁到 `FileMaintenanceService` 后该类必须是 Spring bean，cron 的 `@Value` 默认值（`file.tmpClean` / `file.mapping.gc.cron` / `file.upload.lock.clean.cron`）原样带走。
- **`purgeFromTrash` / `cleanDeletedFileMappings` 联动**：CLAUDE.md 要求新指向 `file_mapping` 的实体要同时挂这两处——本次只搬不改逻辑，需保证搬家后两处仍调到同一套 GC（`FileVersionService.purgeAllVersionsForMapping` 等联动不丢）。
- **403/401 语义**：`FileAuthorityChecker` 抛 `HttpStatus.FORBIDDEN`(403) 与 401 严格分开，搬家不改异常类型。
- **`@RequestParam` 显式名**：controller 改注入不动参数注解。
- 不新增实体 / 不动 `@Index` → 无 DDL 影响。

### B6. PR-B 验收

- 编译：`mvn -pl misu-file-server/misu-file-server-biz -am compile -DskipTests`。
- 行为回归（verifybot + verifyadmin）：上传（分片/秒传/续传）、下载（Range/断点/304）、预览、视频封面/转码播放、移动、删除、回收站还原/彻底删除、批量删/移、搜索、文本读写、存储用量、管理员视角、staging 共享、放映室共享播放（VideoRoom→accessUserFileAsUser）、WebDAV 取文件、Torrent addFileInk、3 个定时任务可手动触发验证、backfill 启动+状态查询。
- WebDav/FileShare/VideoRoom 三个内部调用方回归。

---

## 待你确认（开放点）

1. **接口命名**：`FileService` / `FileAccessService` / `FileTrashService` / `FileTextService` / `FileAdminService` / `FileMaintenanceService` —— 是否采用？要否换词（如 `FileServeService`、`FileGcService`）？
2. **内部组件命名**：`FilePathResolver` / `FileMappingManager` / `FileResponseAssembler` / `FileAuthorityChecker` / `ChunkUploadSupport` / `PhysicalFileOps` —— 是否采用？
3. **`writeTranscodedVideoToResponse`**：转码视频写响应是否也强制走 `HttpFileResponder`（统一），还是保留 access 内私有（它有特殊处理）？倾向：以行为等价为先，能统一就统一，不行就留私有。
4. **`StagingSupport`**：独立组件，还是直接并进 `FileAdminServiceImpl`（staging 只 admin 用）？倾向并进，少一个类。
5. 详细设计是否就绪到可以开工 PR-A？（PR-A 不依赖上述命名争议，可先动）
