package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.UploadConcurrencyGuard;
import com.misu.fileServer.service.support.ChunkUploadSupport;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.FileResponseAssembler;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.fileServer.util.UploadExtensionGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Value("${file.quota.privateBytesPerUser:-1}")
    private long privateQuotaBytesPerUser;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private UploadExtensionGuard uploadExtensionGuard;

    @Resource
    private UploadConcurrencyGuard uploadConcurrencyGuard;

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FilePathResolver filePathResolver;

    @Resource
    private FileAuthorityChecker fileAuthorityChecker;

    @Resource
    private PhysicalFileOps physicalFileOps;

    @Resource
    private FileMappingManager fileMappingManager;

    @Resource
    private FileResponseAssembler fileResponseAssembler;

    @Resource
    private ChunkUploadSupport chunkUploadSupport;

    @Override
    public List<FileResponseDto> getFileList(FileRequestDto fileRequestDto) {
        Optional<LoginUser> loginUser = LoginMessageUtil.getLoginUser();
        if (loginUser.isPresent()) {
            String userId = filePathResolver.getMappingUserId(fileRequestDto.getOpenType(), loginUser.get().getUserId().toString());
            List<FileResponseDto> fileList = fileResponseAssembler.getFileListFromDirectory(fileRequestDto, userId);
            for (FileResponseDto responseDto : fileList) {
                //封装文件预览路径
                fileResponseAssembler.packagePreviewLink(fileRequestDto.getOpenType(), responseDto);
                fileResponseAssembler.packageVideoTranscodeInfo(fileRequestDto.getOpenType(), userId, responseDto);

                //设置下载路径
                responseDto.setDownloadLink(fileResponseAssembler.createUserFileAccessLink("/download", fileRequestDto.getFilePath() + responseDto.getFileName(),
                        fileRequestDto.getOpenType()));
                responseDto.setStreamLink(fileResponseAssembler.createUserFileAccessLink("/stream", fileRequestDto.getFilePath() + responseDto.getFileName(),
                        fileRequestDto.getOpenType()));

                responseDto.setFile(null);
            }
            return fileList;
        }else {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录或未认证使用文件系统");
        }
    }

    @Override
    public FileUploadResponse uploadFile(FileUploadRequest fileUploadRequest) {
        fileAuthorityChecker.checkPublicWriteAuthority(fileUploadRequest.getOpenType());
        chunkUploadSupport.checkUploadChunk(fileUploadRequest);
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String fileName = FilePathGuard.normalizeFileName(fileUploadRequest.getFileName());
        // Q3：上传扩展名黑名单校验，拒绝可执行 / 脚本类危险类型
        uploadExtensionGuard.requireSafeForUpload(fileName);
        String relativePath = FilePathGuard.normalizeRelativePath(fileUploadRequest.getFilePath(), true);
        String virtualPath = StringUtils.isBlank(relativePath) ? fileName : relativePath + "/" + fileName;
        String mappingUserId = filePathResolver.getMappingUserId(fileUploadRequest.getOpenType(), loginUser.getUserId().toString());
        Optional<FileMapping> existingMapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(fileUploadRequest.getOpenType(), mappingUserId, virtualPath);

        if (!fileUploadRequest.getCoverFlag() && existingMapping.isPresent()) {
            return new FileUploadResponse(2, "文件已存在");
        }

        File file = filePathResolver.buildUploadStorageFile(fileUploadRequest.getOpenType(), loginUser.getUserId().toString(), fileName);
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new ServiceException(HttpStatus.ERROR, "上传目录创建失败");
        }

        String fileMD5 = DigestUtils.md5Hex(fileUploadRequest.getOpenType() + ":" + mappingUserId + ":" + virtualPath);
        //上传文件
        // Q8：单用户上传并发限流，防止恶意并发耗尽 IO / 文件句柄
        try (UploadConcurrencyGuard.Releaser ignored = uploadConcurrencyGuard.acquire(mappingUserId)) {
        try {
            // 保存分片文件（落盘 + 到齐检查 + 带 per-key 合并锁的顺序合并均由 ChunkUploadSupport 委托给共享 assembler）
            chunkUploadSupport.storeChunk(fileMD5, fileUploadRequest);

            // 如果所有分片都上传完成，则合并文件；支持乱序上传。
            if (chunkUploadSupport.allChunksUploaded(fileMD5, fileUploadRequest.getTotalChunks())) {
                String contentMd5 = chunkUploadSupport.mergeChunks(file, fileMD5, fileUploadRequest.getTotalChunks());
                //上传完成后执行后置操作
                chunkUploadSupport.fileAddAfter(file);
                // M18：覆盖上传场景，先给旧 mapping 打快照（异常不影响主流程）
                existingMapping.ifPresent(m -> fileVersionService.snapshotIfEligible(m, "OVERWRITE"));
                fileMappingManager.saveOrUpdateFileMapping(fileUploadRequest.getOpenType(),
                        mappingUserId,
                        virtualPath,
                        relativePath,
                        fileName,
                        file,
                        contentMd5);
            }
        }catch (ServiceException se) {
            throw se;
        }catch (Exception e) {
            log.error("上传文件异常", e);
            //删除该次上传的分片目录
            File chunkDir = chunkUploadSupport.chunkDir(fileMD5).toFile();
            if (chunkDir.exists()) {
                physicalFileOps.deletePhysicalRecursively(chunkDir);
            }
            //删除上传目录的文件
            if (file.exists()) {
                file.delete();
            }
            throw new ServiceException(HttpStatus.ERROR, "上传文件异常");
        }
        } // end uploadConcurrencyGuard try-with-resources

        return new FileUploadResponse(1, "上传成功");
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void addFileInk(AddFileInkRequest addFileInkRequest) throws IOException {
        if (addFileInkRequest.getOpenType() == 0 && StringUtils.isBlank(addFileInkRequest.getUserId())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "添加私人文件时用户id不能为空");
        }

        //根据openType获取目录
        String fileDirectory = filePathResolver.getUserRootDirectory(addFileInkRequest.getOpenType(), addFileInkRequest.getUserId());

        // 拼接完整地址
        Path parentPath = FilePathGuard.resolveInsideRoot(fileDirectory, addFileInkRequest.getFilePath(), true);
        String fileName = FilePathGuard.normalizeFileName(addFileInkRequest.getFileName());
        File file = parentPath.resolve(fileName).normalize().toFile();
        if (!file.toPath().startsWith(Paths.get(fileDirectory).toAbsolutePath().normalize())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
        }
        String virtualPath = FilePathGuard.normalizeRelativePath(addFileInkRequest.getFilePath(), true);
        virtualPath = StringUtils.isBlank(virtualPath) ? fileName : virtualPath + "/" + fileName;
        String mappingUserId = filePathResolver.getMappingUserId(addFileInkRequest.getOpenType(), addFileInkRequest.getUserId());

        if (fileMappingManager.hasMappingUnderPath(addFileInkRequest.getOpenType(), mappingUserId, virtualPath)
                || fileMappingManager.hasMappingParentConflict(addFileInkRequest.getOpenType(), mappingUserId, virtualPath)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录下存在同名文件，无法同步至该目录");
        } else {
            Path target = Paths.get(addFileInkRequest.getInkFilePath());
            if (!Files.exists(target)) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "映射的原文件不存在");
            }
            fileMappingManager.mapPhysicalTreeToVirtualPaths(addFileInkRequest.getOpenType(), mappingUserId, virtualPath, target.toFile());
        }
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Boolean createDirectory(FileRequestDto fileRequestDto) {
        fileAuthorityChecker.checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = filePathResolver.getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());

        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        String parentPath = filePathResolver.getParentPath(relativePath);
        String fileName = FilePathGuard.normalizeFileName(Path.of(relativePath).getFileName().toString());
        File file = filePathResolver.buildVirtualDirectoryStorage(fileRequestDto.getOpenType(), loginUser.getUserId().toString(), fileName);

        if (file.exists() || fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                fileRequestDto.getOpenType(), mappingUserId, relativePath).isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "同名目录或文件已存在，无法创建");
        }
        if (!file.exists() && !file.mkdirs()) {
            return false;
        }
        fileMappingManager.saveOrUpdateFileMapping(fileRequestDto.getOpenType(), mappingUserId, relativePath, parentPath, fileName, file);
        return true;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void moveFile(FileRenameRequestDto fileRenameRequestDto) {
        fileAuthorityChecker.checkPublicWriteAuthority(fileRenameRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = filePathResolver.getMappingUserId(fileRenameRequestDto.getOpenType(), loginUser.getUserId().toString());

        String originRelativePath = FilePathGuard.normalizeRelativePath(fileRenameRequestDto.getOriginFilePath());
        String newRelativePath = FilePathGuard.normalizeRelativePath(fileRenameRequestDto.getNewFilePath());

        if (originRelativePath.equals(newRelativePath)) {
            return;
        }

        if (StringUtils.startsWith(newRelativePath + "/", originRelativePath + "/")) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "不允许将目录移动到自身子目录");
        }

        boolean sourceExists = fileMappingManager.hasMappingUnderPath(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath);
        if (!sourceExists) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "原文件不存在");
        }

        boolean targetExists = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        fileRenameRequestDto.getOpenType(), mappingUserId, newRelativePath)
                .isPresent();
        if (targetExists) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "移动失败，新的位置存在同名文件");
        }
        if (fileMappingManager.hasMoveDestinationConflict(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath, newRelativePath)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "移动失败，新的位置存在同名文件");
        }

        fileMappingManager.moveFileMappingTree(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath, newRelativePath);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void sharePrivateFileToPublic(SharePrivateFileToPublicRequestDto requestDto) {
        fileAuthorityChecker.checkPublicWriteAuthority(1);
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        String sourceRelativePath = FilePathGuard.normalizeRelativePath(requestDto.getSourceFilePath());
        String sourceFileName = FilePathGuard.normalizeFileName(Path.of(sourceRelativePath).getFileName().toString());
        Path sourcePath = filePathResolver.resolveUserRequestFile(0, loginUser.getUserId().toString(), sourceRelativePath);
        File sourceFile = sourcePath.toFile();
        if (!sourceFile.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "源文件不存在或已被删除");
        }

        String targetDirectoryRelativePath = FilePathGuard.normalizeRelativePath(requestDto.getTargetDirectoryPath(), true);
        String targetVirtualPath = StringUtils.isBlank(targetDirectoryRelativePath)
                ? sourceFileName
                : targetDirectoryRelativePath + "/" + sourceFileName;
        if (StringUtils.isNotBlank(targetDirectoryRelativePath)) {
            FileMapping targetDirectoryMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", targetDirectoryRelativePath)
                    .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "公共目标目录不存在"));
            if (!FileType.DIRECTORY_FILE.equals(targetDirectoryMapping.getFileType())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目标目录不存在");
            }
        }
        if (fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", targetVirtualPath)
                .isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目录已存在同名文件或文件夹");
        }
        fileMappingManager.savePublicFileMapping(targetVirtualPath, sourcePath, loginUser.getUserId().toString(), sourceRelativePath);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Boolean deleteFile(FileRequestDto fileRequestDto) {
        fileAuthorityChecker.checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = filePathResolver.getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        if (fileMappingManager.hasMappingUnderPath(fileRequestDto.getOpenType(), mappingUserId, relativePath)) {
            fileMappingManager.markDeletedByPrefix(fileRequestDto.getOpenType(), mappingUserId, relativePath);
            return true;
        }
        return false;
    }

    // =====================================================================
    // M3：文件搜索 + 回收站
    // =====================================================================

    @Override
    public PageResponseDto<FileResponseDto> searchFiles(SearchFileRequestDto request) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());

        int pageNumber = Math.max(0, request.getPageNumber() - 1);
        int pageSize = Math.max(1, request.getPageSize());
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        String keyword = StringUtils.trim(request.getKeyword());

        Page<FileMapping> page;
        if (StringUtils.isNotBlank(request.getFileType())) {
            page = fileMappingRepository
                    .findByOpenTypeAndUserIdAndDeletedFalseAndFileTypeAndFileNameContainingIgnoreCaseOrderByFileNameAsc(
                            request.getOpenType(), mappingUserId, request.getFileType(), keyword, pageable);
        } else {
            page = fileMappingRepository
                    .findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
                            request.getOpenType(), mappingUserId, keyword, pageable);
        }

        List<FileResponseDto> items = page.getContent().stream()
                .map(fileResponseAssembler::toFileResponseDto)
                .filter(dto -> dto.getFile() != null && dto.getFile().exists())
                .peek(dto -> {
                    fileResponseAssembler.packagePreviewLink(request.getOpenType(), dto);
                    fileResponseAssembler.packageVideoTranscodeInfo(request.getOpenType(), mappingUserId, dto);
                    String virtualPath = StringUtils.stripStart(dto.getFilePath(), "/") + dto.getFileName();
                    dto.setDownloadLink(fileResponseAssembler.createUserFileAccessLink("/download", virtualPath, request.getOpenType()));
                    dto.setStreamLink(fileResponseAssembler.createUserFileAccessLink("/stream", virtualPath, request.getOpenType()));
                    dto.setFile(null);
                })
                .collect(Collectors.toList());

        return new PageResponseDto<>(items, page.getTotalElements(), request.getPageNumber(), request.getPageSize());
    }

    // =====================================================================
    // M4：批量操作 + ZIP 文件夹下载
    // =====================================================================

    @Override
    @Transactional("fileServerTransactionManager")
    public BatchOperationResultDto batchDelete(BatchFileRequestDto request) {
        fileAuthorityChecker.checkPublicWriteAuthority(request.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
        BatchOperationResultDto result = new BatchOperationResultDto();

        for (String filePath : request.getFilePaths()) {
            try {
                String relativePath = FilePathGuard.normalizeRelativePath(filePath);
                if (!fileMappingManager.hasMappingUnderPath(request.getOpenType(), mappingUserId, relativePath)) {
                    result.addFailure(filePath, "不存在");
                    continue;
                }
                fileMappingManager.markDeletedByPrefix(request.getOpenType(), mappingUserId, relativePath);
                result.addSuccess();
            } catch (ServiceException se) {
                result.addFailure(filePath, se.getMessage());
            } catch (Exception e) {
                log.warn("batchDelete 失败，filePath={}", filePath, e);
                result.addFailure(filePath, "处理异常");
            }
        }
        return result;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public BatchOperationResultDto batchMove(BatchFileRequestDto request) {
        fileAuthorityChecker.checkPublicWriteAuthority(request.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
        String targetParent = FilePathGuard.normalizeRelativePath(
                StringUtils.defaultString(request.getTargetParentPath()), true);

        // 目标父目录必须是已存在的目录（或 root）
        if (StringUtils.isNotBlank(targetParent)) {
            FileMapping parentMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                            request.getOpenType(), mappingUserId, targetParent)
                    .orElse(null);
            if (parentMapping == null || !FileType.DIRECTORY_FILE.equals(parentMapping.getFileType())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "目标父目录不存在");
            }
        }

        BatchOperationResultDto result = new BatchOperationResultDto();
        for (String filePath : request.getFilePaths()) {
            try {
                String origin = FilePathGuard.normalizeRelativePath(filePath);
                String fileName = Path.of(origin).getFileName().toString();
                String newPath = StringUtils.isBlank(targetParent) ? fileName : targetParent + "/" + fileName;
                if (origin.equals(newPath)) {
                    result.addSuccess();
                    continue;
                }
                if (StringUtils.startsWith(newPath + "/", origin + "/")) {
                    result.addFailure(filePath, "不允许将目录移动到自身子目录");
                    continue;
                }
                if (!fileMappingManager.hasMappingUnderPath(request.getOpenType(), mappingUserId, origin)) {
                    result.addFailure(filePath, "原文件不存在");
                    continue;
                }
                boolean targetExists = fileMappingRepository
                        .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                                request.getOpenType(), mappingUserId, newPath)
                        .isPresent();
                if (targetExists
                        || fileMappingManager.hasMoveDestinationConflict(request.getOpenType(), mappingUserId, origin, newPath)) {
                    result.addFailure(filePath, "目标目录已存在同名文件");
                    continue;
                }
                fileMappingManager.moveFileMappingTree(request.getOpenType(), mappingUserId, origin, newPath);
                result.addSuccess();
            } catch (ServiceException se) {
                result.addFailure(filePath, se.getMessage());
            } catch (Exception e) {
                log.warn("batchMove 失败，filePath={}", filePath, e);
                result.addFailure(filePath, "处理异常");
            }
        }
        return result;
    }

    // =====================================================================
    // M5：配额展示 + 哈希秒传
    // =====================================================================

    @Override
    public StorageUsageResponseDto getStorageUsage(Integer openType) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(openType, loginUser.getUserId().toString());
        StorageUsageResponseDto dto = new StorageUsageResponseDto();
        dto.setOpenType(openType);
        dto.setUsedBytes(fileMappingRepository.sumUsedBytes(openType, mappingUserId));
        dto.setFileCount(fileMappingRepository.countUsedFiles(openType, mappingUserId));
        // 仅私人空间显示配额；公共空间无需配额（且需要 ADMIN 才能写）
        if (openType == 0 && privateQuotaBytesPerUser > 0) {
            dto.setQuotaBytes(privateQuotaBytesPerUser);
        }
        return dto;
    }

    @Override
    public UploadStatusResponseDto getUploadStatus(Integer openType, String fileName, String filePath, Integer totalChunks) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String normalizedName = FilePathGuard.normalizeFileName(fileName);
        String relativePath = FilePathGuard.normalizeRelativePath(StringUtils.defaultString(filePath), true);
        String virtualPath = StringUtils.isBlank(relativePath) ? normalizedName : relativePath + "/" + normalizedName;
        String mappingUserId = filePathResolver.getMappingUserId(openType, loginUser.getUserId().toString());
        String fileMD5 = DigestUtils.md5Hex(openType + ":" + mappingUserId + ":" + virtualPath);

        File chunkDir = chunkUploadSupport.chunkDir(fileMD5).toFile();
        List<Integer> uploaded = new ArrayList<>();
        if (chunkDir.exists() && chunkDir.isDirectory()) {
            File[] parts = chunkDir.listFiles((dir, name) -> name.startsWith("part"));
            if (parts != null) {
                String prefix = "part";
                for (File p : parts) {
                    String tail = p.getName().substring(prefix.length());
                    try {
                        uploaded.add(Integer.parseInt(tail));
                    } catch (NumberFormatException ignored) {
                        // 不是合法分片名，跳过
                    }
                }
            }
        }
        Collections.sort(uploaded);

        UploadStatusResponseDto dto = new UploadStatusResponseDto();
        dto.setUploadedChunks(uploaded);
        dto.setVirtualPath(virtualPath);
        if (totalChunks != null && totalChunks > 0 && uploaded.size() >= totalChunks) {
            // 校验是否每一片都齐全
            boolean allHere = true;
            for (int i = 0; i < totalChunks; i++) {
                if (!uploaded.contains(i)) { allHere = false; break; }
            }
            dto.setAllUploaded(allHere);
        } else {
            dto.setAllUploaded(false);
        }
        return dto;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public HashUploadCheckResponseDto checkUploadByHash(HashUploadCheckRequestDto request) {
        fileAuthorityChecker.checkPublicWriteAuthority(request.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String fileName = FilePathGuard.normalizeFileName(request.getFileName());
        uploadExtensionGuard.requireSafeForUpload(fileName);
        String relativePath = FilePathGuard.normalizeRelativePath(
                StringUtils.defaultString(request.getFilePath()), true);
        String virtualPath = StringUtils.isBlank(relativePath) ? fileName : relativePath + "/" + fileName;
        String mappingUserId = filePathResolver.getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
        String md5 = request.getFileMd5().toLowerCase(Locale.ROOT);

        Optional<FileMapping> existingAtSamePath = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        request.getOpenType(), mappingUserId, virtualPath);
        if (!Boolean.TRUE.equals(request.getCoverFlag()) && existingAtSamePath.isPresent()) {
            return new HashUploadCheckResponseDto(2, "文件已存在");
        }

        // 哈希查重：取最近 5 条 md5+size 全匹配的 active mapping，挑物理文件存在的一条复用
        List<FileMapping> hits = fileMappingRepository
                .findFirst5ByFileMd5AndFileSizeAndDeletedFalse(md5, request.getFileSize());
        Optional<FileMapping> hit = hits.stream()
                .filter(m -> StringUtils.isNotBlank(m.getTargetPath())
                        && Path.of(m.getTargetPath()).toFile().exists())
                .findFirst();
        if (hit.isEmpty()) {
            return new HashUploadCheckResponseDto(0, "未命中秒传，请走完整上传");
        }

        // M18：覆盖场景（同位置同文件名）先快照旧文件
        existingAtSamePath.ifPresent(m -> fileVersionService.snapshotIfEligible(m, "HASH_DEDUP"));

        FileMapping reuse = existingAtSamePath.orElseGet(FileMapping::new);
        reuse.setOpenType(request.getOpenType());
        reuse.setUserId(mappingUserId);
        reuse.setVirtualPath(virtualPath);
        reuse.setParentPath(StringUtils.defaultString(filePathResolver.getParentPath(virtualPath)));
        reuse.setFileName(fileName);
        reuse.setFileType(hit.get().getFileType());
        reuse.setFileSize(hit.get().getFileSize());
        reuse.setTargetPath(hit.get().getTargetPath());
        reuse.setFileMd5(md5);
        reuse.setDeleted(false);
        if (reuse.getCreateTime() == null) {
            reuse.setCreateTime(LocalDateTime.now());
        }
        reuse.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(reuse);

        // 触发 idempotent 后置（图片预览生成 / 视频转码状态）。
        File reusedFile = Path.of(hit.get().getTargetPath()).toFile();
        if (reusedFile.exists() && reusedFile.isFile()) {
            chunkUploadSupport.fileAddAfter(reusedFile);
        }
        return new HashUploadCheckResponseDto(1, "秒传成功");
    }
}
