package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.common.util.ZipUtils;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.UploadConcurrencyGuard;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.fileServer.util.FileTypeUtils;
import com.misu.fileServer.util.UploadExtensionGuard;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    private final static String PUBLIC_DIRECTORY = "public/";
    private final static String PRIVATE_DIRECTORY = "private/";
    private final static String PREVIEW_DIRECTORY = "preview/";
    private final static String TMP_DIRECTORY = "tmp/";
    private final static String STORAGE_DIRECTORY = "storage/";
    private final static String VIRTUAL_DIRECTORY_STORAGE = "virtual-directory/";
    private final static long TMP_FILE_EXPIRE_MILLIS = 24 * 60 * 60 * 1000L;
    private final static String FILE_DOWNLOAD_TOKEN_CACHE_KEY = "file-download-token:";

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${token.expireTtl:86400000}")
    private long tokenExpireTtl;

    @Value("${file.download.directory.maxBytes:209715200}")
    private long directoryDownloadMaxBytes;

    @Value("${file.download.directory.maxFiles:1000}")
    private long directoryDownloadMaxFiles;

    @Value("${file.mapping.gc.retentionDays:7}")
    private long fileMappingGcRetentionDays;

    @Value("${file.upload.lock.expireMillis:1800000}")
    private long uploadLockExpireMillis;

    @Resource
    private TokenService tokenService;
    
    @Resource
    private PreviewService previewService;

    @Resource
    private VideoTranscodeService videoTranscodeService;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private ThreadPoolTaskExecutor fileExecutor;

    @Resource
    private UploadExtensionGuard uploadExtensionGuard;

    @Resource
    private UploadConcurrencyGuard uploadConcurrencyGuard;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile LocalDateTime backfillStartTime;
    private volatile LocalDateTime backfillEndTime;
    private volatile String backfillLastError;
    private final AtomicLong backfillProcessedCount = new AtomicLong(0L);
    private final AtomicLong backfillCreatedCount = new AtomicLong(0L);
    private final AtomicLong backfillUpdatedCount = new AtomicLong(0L);
    private final Map<String, Object> uploadMergeLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> uploadMergeLockAccessTime = new ConcurrentHashMap<>();

    /**
     * 初始化检查文件目录是否存在，不存在则创建
     */
    @PostConstruct
    public void initFileDirectory() {
        File publicDirectory = new File(fileServerPath + PUBLIC_DIRECTORY);
        if (!publicDirectory.exists()) {
            publicDirectory.mkdirs();
        }

        File privateDirectory = new File(fileServerPath + PRIVATE_DIRECTORY);
        if (!privateDirectory.exists()) {
            privateDirectory.mkdirs();
        }
    }

    @Override
    public List<FileResponseDto> getFileList(FileRequestDto fileRequestDto) {
        Optional<LoginUser> loginUser = LoginMessageUtil.getLoginUser();
        if (loginUser.isPresent()) {
            String userId = getMappingUserId(fileRequestDto.getOpenType(), loginUser.get().getUserId().toString());
            List<FileResponseDto> fileList = getFileListFromDirectory(fileRequestDto, userId);
            for (FileResponseDto responseDto : fileList) {
                //封装文件预览路径
                packagePreviewLink(fileRequestDto.getOpenType(), responseDto);
                packageVideoTranscodeInfo(fileRequestDto.getOpenType(), responseDto);

                //设置下载路径
                responseDto.setDownloadLink(createUserFileAccessLink("/download", fileRequestDto.getFilePath() + responseDto.getFileName(),
                        fileRequestDto.getOpenType()));
                responseDto.setStreamLink(createUserFileAccessLink("/stream", fileRequestDto.getFilePath() + responseDto.getFileName(),
                        fileRequestDto.getOpenType()));

                responseDto.setFile(null);
            }
            return fileList;
        }else {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录或未认证使用文件系统");
        }
    }

    private void packageVideoTranscodeInfo(Integer openType, FileResponseDto responseDto) {
        if (!FileType.VIDEO_FILE.equals(responseDto.getFileType())) {
            return;
        }

        VideoTranscodeStatusDto status = videoTranscodeService.getOrCreateTranscodeStatus(responseDto.getFile());
        responseDto.setTranscodeState(status.getState());
        responseDto.setTranscodeProgress(status.getProgress());
        responseDto.setTranscodeMessage(status.getMessage());
        responseDto.setTranscodeMaxBytes(videoTranscodeService.getMaxBytes());

        if (videoTranscodeService.getVideoPreviewFile(responseDto.getFile()).exists()) {
            responseDto.setVideoPreviewLink(createUserFileAccessLink("/videoPreview",
                    responseDto.getFilePath() + responseDto.getFileName(), openType));
        }
        if (VideoTranscodeState.SUCCESS.equals(status.getState())) {
            String transcodedStreamLink = createUserFileAccessLink("/transcodedVideo",
                    responseDto.getFilePath() + responseDto.getFileName(), openType);
            responseDto.setTranscodedStreamLink(transcodedStreamLink);
        }
    }

    private void packagePreviewLink(Integer openType, FileResponseDto responseDto) {
        //图片类型的文件预览链接设置
        if (FileType.IMAGE_FILE.equals(responseDto.getFileType())) {
            File previewFile = getPreviewFile(responseDto.getFile());
            //如果预览文件存在，生成预览链接，如果不存在，添加到缩略图生成队列
            if (previewFile.exists()) {
                responseDto.setPreviewLink(createUserFileAccessLink("/preview",
                        responseDto.getFilePath() + responseDto.getFileName(), openType));
            }else {
                previewService.generatePreviewFile(responseDto.getFile());
            }
        }
    }

    /**
     * 从指定目录获取文件列表
     */
    private List<FileResponseDto> getFileListFromDirectory(FileRequestDto fileRequestDto, String userId) {
        String requestPath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath(), true);
        return fileMappingRepository.findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        fileRequestDto.getOpenType(), userId, requestPath)
                .stream()
                .map(this::toFileResponseDto)
                .filter(dto -> dto.getFile() != null && dto.getFile().exists())
                .collect(Collectors.toList());
    }

    private FileResponseDto toFileResponseDto(FileMapping mapping) {
        FileResponseDto fileResponseDto = new FileResponseDto();
        fileResponseDto.setFileName(mapping.getFileName());
        fileResponseDto.setFileSize(mapping.getFileSize());
        fileResponseDto.setFileType(mapping.getFileType());
        fileResponseDto.setFile(resolveMappedFile(mapping));
        fileResponseDto.setFilePath("/" + (StringUtils.isBlank(mapping.getParentPath()) ? "" : mapping.getParentPath() + "/"));
        return fileResponseDto;
    }

    @Override
    public String getFileDownloadLink(FileRequestDto fileRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        Path targetPath = resolveUserRequestFile(fileRequestDto);
        if (!targetPath.toFile().exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }

        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        String filePath = fileRequestDto.getOpenType() == 1 ?
                PUBLIC_DIRECTORY + relativePath :
                PRIVATE_DIRECTORY + loginUser.getUserId() + "/" + relativePath;

        return createFileDownloadLink(filePath);
    }

    /**
     * 创建文件临时下载链接
     */
    private String createFileDownloadLink(String filePath) {
        Map<String, Object> claims = new HashMap<>();
        Long userId = LoginMessageUtil.getLoginUser().get().getUserId();
        claims.put("userId", userId);
        claims.put("filePath", filePath);
        String cacheKey = FILE_DOWNLOAD_TOKEN_CACHE_KEY + userId + ":" + filePath;
        String fileToken = CacheUtils.getCacheObject(cacheKey);
        if (StringUtils.isBlank(fileToken)) {
            fileToken = tokenService.createToken(claims);
            long cacheMillis = tokenExpireTtl > 120_000L ? tokenExpireTtl - 60_000L : Math.max(1_000L, tokenExpireTtl / 2);
            CacheUtils.setCacheObject(cacheKey, fileToken, cacheMillis, ChronoUnit.MILLIS);
        }
        return "fileServer/file/downloadFile?fileToken=" + fileToken;
    }

    private String createUserFileAccessLink(String accessPath, String filePath, Integer openType) {
        String relativePath = FilePathGuard.normalizeRelativePath(filePath);
        return "fileServer/file" + accessPath + "?openType=" + openType
                + "&filePath=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8);
    }

    @Override
    public void downloadFile(FileDownloadRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        try {
            Claims claims = tokenService.parseToken(fileRequestDto.getFileToken());
            String tokenFilePath = claims.get("filePath", String.class);
            if (claims.getExpiration().after(new Date()) ) {
                File file = resolveTokenFile(tokenFilePath, claims).toFile();
                if (file.exists()) {
                    writeFileToResponse(response, request, file, true);
                    return;
                }else {
                    throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
                }
            }
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接已过期");
        }catch (ServiceException se) {
            throw se;
        }catch (Exception e) {
            log.error("token解析失败", e);
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接已过期");
        }
    }

    @Override
    public void accessUserFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response, boolean attachment) {
        File file = resolveUserRequestFile(fileRequestDto).toFile();
        if (!file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        writeFileToResponse(response, request, file, attachment);
    }

    @Override
    public void accessUserFileAsUser(Integer openType, String userId, String filePath,
                                     HttpServletRequest request, HttpServletResponse response, boolean attachment) {
        File file = resolveUserRequestFile(openType, userId, filePath).toFile();
        if (!file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        writeFileToResponse(response, request, file, attachment);
    }

    @Override
    public void previewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        File originFile = resolveUserRequestFile(fileRequestDto).toFile();
        if (!originFile.exists() || originFile.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        if (!FileType.IMAGE_FILE.equals(FileTypeUtils.getFileType(originFile))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前文件不支持缩略图预览");
        }

        File previewFile = getPreviewFile(originFile);
        if (!previewFile.exists()) {
            previewService.generatePreviewFile(originFile);
            previewFile = originFile;
        }
        writeFileToResponse(response, request, previewFile, false);
    }

    @Override
    public void videoPreviewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        File originFile = resolveUserRequestFile(fileRequestDto).toFile();
        if (!originFile.exists() || originFile.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        if (!FileType.VIDEO_FILE.equals(FileTypeUtils.getFileType(originFile))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前文件不支持视频封面预览");
        }

        File previewFile = videoTranscodeService.getVideoPreviewFile(originFile);
        if (!previewFile.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "视频封面尚未生成");
        }
        writeFileToResponse(response, request, previewFile, false);
    }

    @Override
    public void transcodedVideoFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        File originFile = resolveUserRequestFile(fileRequestDto).toFile();
        writeTranscodedVideoToResponse(originFile, request, response);
    }

    @Override
    public void transcodedVideoFileAsUser(Integer openType, String userId, String filePath,
                                          HttpServletRequest request, HttpServletResponse response) {
        File originFile = resolveUserRequestFile(openType, userId, filePath).toFile();
        writeTranscodedVideoToResponse(originFile, request, response);
    }

    private void writeTranscodedVideoToResponse(File originFile, HttpServletRequest request, HttpServletResponse response) {
        if (!originFile.exists() || originFile.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        if (!FileType.VIDEO_FILE.equals(FileTypeUtils.getFileType(originFile))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前文件不支持视频播放");
        }

        VideoTranscodeStatusDto status = videoTranscodeService.getOrCreateTranscodeStatus(originFile);
        if (!VideoTranscodeState.SUCCESS.equals(status.getState())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, StringUtils.defaultIfBlank(status.getMessage(), "视频尚未完成转码"));
        }

        File transcodedFile = videoTranscodeService.getTranscodedFile(originFile);
        if (!transcodedFile.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "转码视频不存在或已被删除");
        }
        writeFileToResponse(response, request, transcodedFile, false);
    }

    @Override
    public FileUploadResponse uploadFile(FileUploadRequest fileUploadRequest) {
        checkPublicWriteAuthority(fileUploadRequest.getOpenType());
        checkUploadChunk(fileUploadRequest);
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String fileName = FilePathGuard.normalizeFileName(fileUploadRequest.getFileName());
        // Q3：上传扩展名黑名单校验，拒绝可执行 / 脚本类危险类型
        uploadExtensionGuard.requireSafeForUpload(fileName);
        String relativePath = FilePathGuard.normalizeRelativePath(fileUploadRequest.getFilePath(), true);
        String virtualPath = StringUtils.isBlank(relativePath) ? fileName : relativePath + "/" + fileName;
        String mappingUserId = getMappingUserId(fileUploadRequest.getOpenType(), loginUser.getUserId().toString());
        Optional<FileMapping> existingMapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(fileUploadRequest.getOpenType(), mappingUserId, virtualPath);

        if (!fileUploadRequest.getCoverFlag() && existingMapping.isPresent()) {
            return new FileUploadResponse(2, "文件已存在");
        }

        File file = buildUploadStorageFile(fileUploadRequest.getOpenType(), loginUser.getUserId().toString(), fileName);
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new ServiceException(HttpStatus.ERROR, "上传目录创建失败");
        }

        String fileMD5 = DigestUtils.md5Hex(fileUploadRequest.getOpenType() + ":" + mappingUserId + ":" + virtualPath);
        //上传文件
        boolean mergeCompleted = false;
        // Q8：单用户上传并发限流，防止恶意并发耗尽 IO / 文件句柄
        try (UploadConcurrencyGuard.Releaser ignored = uploadConcurrencyGuard.acquire(mappingUserId)) {
        try {
            Object lock = uploadMergeLocks.computeIfAbsent(fileMD5, k -> new Object());
            uploadMergeLockAccessTime.put(fileMD5, System.currentTimeMillis());
            synchronized (lock) {
                uploadMergeLockAccessTime.put(fileMD5, System.currentTimeMillis());
                // 保存分片文件
                File chunkFile = new File(fileServerPath + TMP_DIRECTORY + fileMD5 + ".part" + fileUploadRequest.getChunkIndex());
                if (!chunkFile.getParentFile().exists()) {
                    chunkFile.getParentFile().mkdirs();
                }
                fileUploadRequest.getFile().transferTo(chunkFile);
                uploadMergeLockAccessTime.put(fileMD5, System.currentTimeMillis());

                // 如果所有分片都上传完成，则合并文件；支持乱序上传。
                if (allChunksUploaded(fileMD5, fileUploadRequest.getTotalChunks())) {
                    mergeChunks(file, fileMD5, fileUploadRequest.getTotalChunks());
                    //上传完成后执行后置操作
                    fileAddAfter(file);
                    saveOrUpdateFileMapping(fileUploadRequest.getOpenType(),
                            mappingUserId,
                            virtualPath,
                            relativePath,
                            fileName,
                            file);
                    mergeCompleted = true;
                }
            }
        }catch (ServiceException se) {
            throw se;
        }catch (Exception e) {
            log.error("上传文件异常", e);
            //遍历删除分片
            for (int i = 0; i < fileUploadRequest.getTotalChunks(); i++) {
                File chunkFile = new File(fileServerPath + TMP_DIRECTORY + fileMD5 + ".part" + i);
                if (chunkFile.exists()) {
                    chunkFile.delete();
                }
            }
            //删除上传目录的文件
            if (file.exists()) {
                file.delete();
            }
            throw new ServiceException(HttpStatus.ERROR, "上传文件异常");
        } finally {
            if (mergeCompleted) {
                uploadMergeLocks.remove(fileMD5);
                uploadMergeLockAccessTime.remove(fileMD5);
            }
        }
        } // end uploadConcurrencyGuard try-with-resources

        return new FileUploadResponse(1, "上传成功");
    }

    private void checkUploadChunk(FileUploadRequest fileUploadRequest) {
        if (fileUploadRequest.getTotalChunks() <= 0) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件总块数不合法");
        }
        if (fileUploadRequest.getChunkIndex() < 0 || fileUploadRequest.getChunkIndex() >= fileUploadRequest.getTotalChunks()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件块索引不合法");
        }
    }

    private boolean allChunksUploaded(String fileMD5, int totalChunks) {
        for (int i = 0; i < totalChunks; i++) {
            File chunkFile = new File(fileServerPath + TMP_DIRECTORY + fileMD5 + ".part" + i);
            if (!chunkFile.exists()) {
                return false;
            }
        }
        return true;
    }

    // 合并所有分片
    // Q2：分片输入流改 try-with-resources，避免循环中途异常导致 N-1 个分片句柄泄漏。
    private void mergeChunks(File file, String fileMD5, int totalChunks) throws IOException {
        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            for (int i = 0; i < totalChunks; i++) {
                File chunkFile = new File(fileServerPath + TMP_DIRECTORY + fileMD5 + ".part" + i);
                try (FileInputStream chunkInputStream = new FileInputStream(chunkFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = chunkInputStream.read(buffer)) != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead);
                    }
                }
                chunkFile.delete();
            }
        }
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void addFileInk(AddFileInkRequest addFileInkRequest) throws IOException {
        if (addFileInkRequest.getOpenType() == 0 && StringUtils.isBlank(addFileInkRequest.getUserId())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "添加私人文件时用户id不能为空");
        }

        //根据openType获取目录
        String fileDirectory = getUserRootDirectory(addFileInkRequest.getOpenType(), addFileInkRequest.getUserId());

        // 拼接完整地址
        Path parentPath = FilePathGuard.resolveInsideRoot(fileDirectory, addFileInkRequest.getFilePath(), true);
        String fileName = FilePathGuard.normalizeFileName(addFileInkRequest.getFileName());
        File file = parentPath.resolve(fileName).normalize().toFile();
        if (!file.toPath().startsWith(Paths.get(fileDirectory).toAbsolutePath().normalize())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
        }
        String virtualPath = FilePathGuard.normalizeRelativePath(addFileInkRequest.getFilePath(), true);
        virtualPath = StringUtils.isBlank(virtualPath) ? fileName : virtualPath + "/" + fileName;
        String mappingUserId = getMappingUserId(addFileInkRequest.getOpenType(), addFileInkRequest.getUserId());

        if (hasMappingUnderPath(addFileInkRequest.getOpenType(), mappingUserId, virtualPath)
                || hasMappingParentConflict(addFileInkRequest.getOpenType(), mappingUserId, virtualPath)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录下存在同名文件，无法同步至该目录");
        } else {
            Path target = Paths.get(addFileInkRequest.getInkFilePath());
            if (!Files.exists(target)) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "映射的原文件不存在");
            }
            mapPhysicalTreeToVirtualPaths(addFileInkRequest.getOpenType(), mappingUserId, virtualPath, target.toFile());
        }
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Boolean createDirectory(FileRequestDto fileRequestDto) {
        checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());

        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        String parentPath = getParentPath(relativePath);
        String fileName = FilePathGuard.normalizeFileName(Path.of(relativePath).getFileName().toString());
        File file = buildVirtualDirectoryStorage(fileRequestDto.getOpenType(), loginUser.getUserId().toString(), fileName);

        if (file.exists() || fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                fileRequestDto.getOpenType(), mappingUserId, relativePath).isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "同名目录或文件已存在，无法创建");
        }
        if (!file.exists() && !file.mkdirs()) {
            return false;
        }
        saveOrUpdateFileMapping(fileRequestDto.getOpenType(), mappingUserId, relativePath, parentPath, fileName, file);
        return true;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void moveFile(FileRenameRequestDto fileRenameRequestDto) {
        checkPublicWriteAuthority(fileRenameRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = getMappingUserId(fileRenameRequestDto.getOpenType(), loginUser.getUserId().toString());

        String originRelativePath = FilePathGuard.normalizeRelativePath(fileRenameRequestDto.getOriginFilePath());
        String newRelativePath = FilePathGuard.normalizeRelativePath(fileRenameRequestDto.getNewFilePath());

        if (originRelativePath.equals(newRelativePath)) {
            return;
        }

        if (StringUtils.startsWith(newRelativePath + "/", originRelativePath + "/")) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "不允许将目录移动到自身子目录");
        }

        boolean sourceExists = hasMappingUnderPath(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath);
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
        if (hasMoveDestinationConflict(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath, newRelativePath)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "移动失败，新的位置存在同名文件");
        }

        moveFileMappingTree(fileRenameRequestDto.getOpenType(), mappingUserId, originRelativePath, newRelativePath);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void sharePrivateFileToPublic(SharePrivateFileToPublicRequestDto requestDto) {
        checkPublicWriteAuthority(1);
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        String sourceRelativePath = FilePathGuard.normalizeRelativePath(requestDto.getSourceFilePath());
        String sourceFileName = FilePathGuard.normalizeFileName(Path.of(sourceRelativePath).getFileName().toString());
        Path sourcePath = resolveUserRequestFile(0, loginUser.getUserId().toString(), sourceRelativePath);
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
        savePublicFileMapping(targetVirtualPath, sourcePath, loginUser.getUserId().toString(), sourceRelativePath);
    }

    private void savePublicFileMapping(String targetVirtualPath,
                                       Path sourcePath,
                                       String sourceUserId,
                                       String sourceRelativePath) {
        if (hasMappingUnderPath(1, "public", targetVirtualPath)
                || hasMappingParentConflict(1, "public", targetVirtualPath)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目录已存在同名文件或文件夹");
        }
        Optional<FileMapping> sourceRootMapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, sourceUserId, sourceRelativePath);
        if (sourceRootMapping.isPresent() && FileType.DIRECTORY_FILE.equals(sourceRootMapping.get().getFileType())) {
            try {
                cloneMappingSubtreeToPublic(sourceUserId, sourceRelativePath, targetVirtualPath);
            } catch (Exception e) {
                log.error("共享目录映射失败，sourceUserId={}, sourceRelativePath={}, targetVirtualPath={}",
                        sourceUserId, sourceRelativePath, targetVirtualPath, e);
                throw new ServiceException(HttpStatus.ERROR, "共享目录失败，请稍后重试");
            }
            return;
        }
        mapPhysicalTreeToVirtualPaths(1, "public", targetVirtualPath, sourcePath.toFile());
    }

    private void cloneMappingSubtreeToPublic(String sourceUserId, String sourceRootPath, String targetRootPath) {
        String sourcePrefix = sourceRootPath + "/";
        List<FileMapping> sourceMappings = fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(0, sourceUserId)
                .stream()
                .filter(one -> one.getVirtualPath().equals(sourceRootPath)
                        || StringUtils.startsWith(one.getVirtualPath(), sourcePrefix))
                .toList();
        if (sourceMappings.isEmpty()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "源目录映射不存在");
        }
        List<FileMapping> targetMappings = new ArrayList<>(sourceMappings.size());
        for (FileMapping source : sourceMappings) {
            String suffix = source.getVirtualPath().equals(sourceRootPath)
                    ? ""
                    : source.getVirtualPath().substring(sourceRootPath.length());
            String targetVirtualPath = targetRootPath + suffix;
            if (fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", targetVirtualPath).isPresent()) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目录存在冲突路径：" + targetVirtualPath);
            }
            FileMapping target = new FileMapping();
            target.setOpenType(1);
            target.setUserId("public");
            target.setVirtualPath(targetVirtualPath);
            target.setParentPath(getParentPath(targetVirtualPath));
            target.setFileName(Path.of(targetVirtualPath).getFileName().toString());
            target.setFileType(source.getFileType());
            target.setFileSize(source.getFileSize());
            target.setTargetPath(source.getTargetPath());
            target.setDeleted(false);
            target.setCreateTime(LocalDateTime.now());
            target.setUpdateTime(LocalDateTime.now());
            targetMappings.add(target);
        }
        fileMappingRepository.saveAll(targetMappings);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Boolean deleteFile(FileRequestDto fileRequestDto) {
        checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        if (hasMappingUnderPath(fileRequestDto.getOpenType(), mappingUserId, relativePath)) {
            markDeletedByPrefix(fileRequestDto.getOpenType(), mappingUserId, relativePath);
            return true;
        }
        return false;
    }

    /**
     * 删除文件或递归删除目录
     */
    private Boolean deleteFile(File deleteFile) {
        boolean isSuccess = true;

        //如果不是符号链接，且是文件夹，递归删除内部文件
        if (!Files.isSymbolicLink(deleteFile.toPath()) && deleteFile.isDirectory()) {
            for (File subFile : deleteFile.listFiles()) {
                deleteFile(subFile);
            }
        }

        //删除文件
        isSuccess = isSuccess && deleteFile.delete();
        if (isSuccess) {
            //执行文件删除后置操作
            fileDeleteAfter(deleteFile);
        }

        return isSuccess;
    }

    /**
     * 文件在http中响应
     */
    @SneakyThrows
    private void writeFileToResponse(HttpServletResponse response, HttpServletRequest request, File file, boolean attachment) {
        if (file.exists()) {
            File tmpFile = null;
            File responseFile = file;
            try {
            if (file.isDirectory()) {
                checkDirectoryDownloadLimit(file);
                tmpFile = new File(fileServerPath + TMP_DIRECTORY + System.currentTimeMillis() + ".zip");
                if (!tmpFile.getParentFile().exists()) {
                    tmpFile.getParentFile().mkdirs();
                }
                try (FileOutputStream tmpFileOutputStream = new FileOutputStream(tmpFile)){
                    ZipUtils.toZip(file, tmpFileOutputStream);
                    //将下载文件指向压缩后的临时文件
                    responseFile = tmpFile;
                }
            }
            //生成文件唯一标识
            String eTag = generateETag(responseFile);

            String contentType;
            try {
                // 根据文件路径探测 MIME 类型
                contentType = Files.probeContentType(responseFile.toPath());
                if (contentType == null) {
                    // 默认二进制流类型
                    contentType = "application/octet-stream";
                }
            } catch (IOException e) {
                contentType = "application/octet-stream";
            }
            //设置部分响应信息
            response.setContentType(MediaType.parseMediaType(contentType).toString());
            response.setCharacterEncoding("utf-8");
            String disposition = attachment ? "attachment" : "inline";
            response.setHeader("Content-disposition", disposition + ";filename="+ URLEncoder.encode(responseFile.getName(), StandardCharsets.UTF_8));
            //缓存24小时
            response.setHeader("Cache-Control", "private, max-age=86400");
            //文件资源唯一标识
            response.setHeader("ETag", eTag);
            response.setDateHeader("Last-Modified", responseFile.lastModified());

            //获取Range字段
            String rangeString = request.getHeader("Range");
            if (StringUtils.isBlank(rangeString) && isNotModified(request, responseFile, eTag)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
            if (StringUtils.isNotBlank(rangeString)) {
                //如果范围存在，设定文件读取开始位置后输出
                try (RandomAccessFile targetFile = new RandomAccessFile(responseFile, "r")){
                    // Parse the Range header
                    HttpRange range = HttpRange.parseRanges(rangeString).get(0);
                    long rangeStart = range.getRangeStart(targetFile.length());
                    long rangeEnd = Math.min(range.getRangeEnd(targetFile.length()), targetFile.length() - 1);

                    //设置此次相应返回的数据长度
                    response.setHeader("Content-Length", String.valueOf(rangeEnd - rangeStart + 1));
                    response.setHeader("Accept-Ranges", "bytes");
                    //设置此次相应返回的数据范围
                    response.setHeader("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + targetFile.length());
                    //返回码需要为206，而不是200
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    //设定文件读取开始位置（以字节为单位）
                    targetFile.seek(rangeStart);
                    byte[] b = new byte[8192];
                    int length;
                    long bytesRead = 0;

                    try {
                        while ((length = targetFile.read(b)) > 0) {
                            // 调整最后一个块的大小
                            if (bytesRead + length > (rangeEnd - rangeStart + 1)) {
                                length = (int) (rangeEnd - rangeStart + 1 - bytesRead);
                            }
                            response.getOutputStream().write(b, 0, length);
                            bytesRead += length;

                            // 如果已读取完range长度的内容，则停止
                            if (bytesRead >= (rangeEnd - rangeStart + 1)) {
                                break;
                            }
                        }
                        response.getOutputStream().flush();
                    }catch (IOException e) {
                        // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                        //比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                    }
                }catch (Exception e) {
                    log.error("读取文件失败", e);
                }
            }else {
                //设置此次相应返回的数据长度
                response.setHeader("Content-Length", String.valueOf(responseFile.length()));

                try (BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(responseFile));){
                    byte[] b = new byte[8192];
                    int length;
                    try {
                        while ((length = fileInputStream.read(b)) > 0) {
                            response.getOutputStream().write(b, 0, length);
                        }
                        response.getOutputStream().flush();
                    }catch (IOException e) {
                        // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                        //比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                    }
                }catch (Exception e) {
                    log.error("读取文件失败", e);
                }
            }
            } finally {
                if (tmpFile != null && tmpFile.exists() && !tmpFile.delete()) {
                    log.warn("临时ZIP文件删除失败：{}", tmpFile.getAbsolutePath());
                }
            }

        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在");
        }
    }

    /**
     * 生成弱Etag标识
     */
    private String generateETag(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;  // 文件不存在或不是一个文件时返回 null
        }
        long lastModified = file.lastModified();  // 获取最后修改时间
        long fileSize = file.length();            // 获取文件大小

        String fileTag = String.valueOf(lastModified) + String.valueOf(fileSize);
        // 生成 ETag，将最后修改时间和文件大小组合，使用 MD5 编码
        return "W/\"" + DigestUtils.md5Hex(fileTag) + "\"";
    }

    private boolean isNotModified(HttpServletRequest request, File file, String eTag) {
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (StringUtils.isNotBlank(ifNoneMatch) && StringUtils.equals(ifNoneMatch, eTag)) {
            return true;
        }
        long ifModifiedSince;
        try {
            ifModifiedSince = request.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (ifModifiedSince < 0) {
            return false;
        }
        return file.lastModified() / 1000 <= ifModifiedSince / 1000;
    }

    private void checkDirectoryDownloadLimit(File directory) {
        DirectoryDownloadStat stat = getDirectoryDownloadStat(directory.toPath());
        if (stat.fileCount > directoryDownloadMaxFiles) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录文件数量过多，请选择较小目录或单文件下载");
        }
        if (stat.totalBytes > directoryDownloadMaxBytes) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录体积过大，请选择较小目录或单文件下载");
        }
    }

    private DirectoryDownloadStat getDirectoryDownloadStat(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            DirectoryDownloadStat stat = new DirectoryDownloadStat();
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        stat.fileCount++;
                        try {
                            stat.totalBytes += Files.size(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            return stat;
        } catch (UncheckedIOException | IOException e) {
            log.error("统计目录下载大小失败", e);
            throw new ServiceException(HttpStatus.ERROR, "统计目录下载大小失败");
        }
    }

    private static class DirectoryDownloadStat {
        private long fileCount;
        private long totalBytes;
    }

    /**
     * 文件上传后置操作
     */
    private void fileAddAfter(File uploadFile) {
        //如果文件是图片，生成缩略图
        if (FileType.IMAGE_FILE.equals(FileTypeUtils.getFileType(uploadFile))) {
            previewService.generatePreviewFile(uploadFile);
        }
        if (FileType.VIDEO_FILE.equals(FileTypeUtils.getFileType(uploadFile))) {
            videoTranscodeService.getOrCreateTranscodeStatus(uploadFile);
        }
    }

    /**
     * 文件删除后置操作
     */
    private void fileDeleteAfter(File deleteFile) {
        //删除预览图片
        previewService.deletePreviewFile(deleteFile);
    }

    private Path resolveUserRequestFile(FileRequestDto fileRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        return resolveUserRequestFile(fileRequestDto.getOpenType(), loginUser.getUserId().toString(), fileRequestDto.getFilePath());
    }

    private Path resolveUserRequestFile(Integer openType, String userId, String filePath) {
        String mappingUserId = getMappingUserId(openType, userId);
        String relativePath = FilePathGuard.normalizeRelativePath(filePath);
        Optional<FileMapping> mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, relativePath);
        if (mapping.isPresent()) {
            return resolveMappedFile(mapping.get()).toPath();
        }
        throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
    }

    private File getPreviewFile(File originFile) {
        Path rootPath = Paths.get(fileServerPath).toAbsolutePath().normalize();
        Path originPath = originFile.toPath().toAbsolutePath().normalize();
        Path relativePath;
        try {
            relativePath = rootPath.relativize(originPath);
        } catch (IllegalArgumentException e) {
            String extension = StringUtils.substringAfterLast(originFile.getName(), ".");
            String fileName = DigestUtils.md5Hex(originPath.toString()) + (StringUtils.isBlank(extension) ? "" : "." + extension);
            relativePath = Paths.get("external").resolve(fileName);
        }
        return rootPath.resolve(PREVIEW_DIRECTORY).resolve(relativePath).normalize().toFile();
    }

    private String getUserRootDirectory(Integer openType, String userId) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        if (openType == 1) {
            return fileServerPath + PUBLIC_DIRECTORY;
        }
        if (openType == 0) {
            if (StringUtils.isBlank(userId)) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "用户id不能为空");
            }
            return fileServerPath + PRIVATE_DIRECTORY + userId + "/";
        }
        throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不合法");
    }

    private String getMappingUserId(Integer openType, String loginUserId) {
        if (openType != null && openType == 1) {
            return "public";
        }
        return loginUserId;
    }

    private String getParentPath(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index > 0 ? relativePath.substring(0, index) : "";
    }

    private boolean hasMappingUnderPath(Integer openType, String userId, String relativePath) {
        String prefix = relativePath + "/";
        return fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId)
                .stream()
                .anyMatch(one -> one.getVirtualPath().equals(relativePath)
                        || StringUtils.startsWith(one.getVirtualPath(), prefix));
    }

    private boolean hasMappingParentConflict(Integer openType, String userId, String relativePath) {
        String current = relativePath;
        while (StringUtils.isNotBlank(current)) {
            String parent = getParentPath(current);
            if (StringUtils.equals(current, parent)) {
                break;
            }
            if (StringUtils.isBlank(parent)) {
                break;
            }
            Optional<FileMapping> parentMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, userId, parent);
            if (parentMapping.isPresent() && !FileType.DIRECTORY_FILE.equals(parentMapping.get().getFileType())) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    private boolean hasMoveDestinationConflict(Integer openType, String userId, String originRelativePath, String newRelativePath) {
        String originPrefix = originRelativePath + "/";
        List<String> movingPaths = fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId)
                .stream()
                .map(FileMapping::getVirtualPath)
                .filter(path -> path.equals(originRelativePath) || StringUtils.startsWith(path, originPrefix))
                .toList();
        if (movingPaths.isEmpty()) {
            return false;
        }
        Set<String> movingPathSet = new HashSet<>(movingPaths);
        for (String oldPath : movingPaths) {
            String suffix = oldPath.equals(originRelativePath) ? "" : oldPath.substring(originRelativePath.length());
            String destinationPath = newRelativePath + suffix;
            Optional<FileMapping> destinationMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, userId, destinationPath);
            if (destinationMapping.isPresent() && !movingPathSet.contains(destinationPath)) {
                return true;
            }
        }
        return false;
    }

    private void mapPhysicalTreeToVirtualPaths(Integer openType, String userId, String rootVirtualPath, File physicalRoot) {
        saveOrUpdateFileMapping(openType, userId, rootVirtualPath, getParentPath(rootVirtualPath),
                Path.of(rootVirtualPath).getFileName().toString(), physicalRoot);
        if (!physicalRoot.isDirectory()) {
            return;
        }
        walkAndMapChildren(openType, userId, rootVirtualPath, physicalRoot, physicalRoot);
    }

    private void walkAndMapChildren(Integer openType, String userId, String rootVirtualPath, File rootPhysical, File current) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        Path rootPath = rootPhysical.toPath().toAbsolutePath().normalize();
        for (File child : children) {
            Path childPath = child.toPath().toAbsolutePath().normalize();
            String suffix = rootPath.relativize(childPath).toString().replace("\\", "/");
            String childVirtualPath = rootVirtualPath + "/" + suffix;
            saveOrUpdateFileMapping(openType, userId, childVirtualPath, getParentPath(childVirtualPath), child.getName(), child);
            if (child.isDirectory()) {
                walkAndMapChildren(openType, userId, rootVirtualPath, rootPhysical, child);
            }
        }
    }

    private File buildUploadStorageFile(Integer openType, String userId, String fileName) {
        String extension = StringUtils.substringAfterLast(fileName, ".");
        String uniqueName = UUID.randomUUID() + (StringUtils.isBlank(extension) ? "" : "." + extension);
        Path path = Path.of(fileServerPath, STORAGE_DIRECTORY, String.valueOf(openType), userId, uniqueName)
                .toAbsolutePath()
                .normalize();
        return path.toFile();
    }

    private File buildVirtualDirectoryStorage(Integer openType, String userId, String directoryName) {
        Path path = Path.of(fileServerPath, VIRTUAL_DIRECTORY_STORAGE, String.valueOf(openType), userId,
                        UUID.randomUUID() + "-" + directoryName)
                .toAbsolutePath()
                .normalize();
        return path.toFile();
    }

    private void saveOrUpdateFileMapping(Integer openType, String mappingUserId, String virtualPath,
                                         String parentPath, String fileName, File file) {
        FileMapping mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, virtualPath)
                .orElseGet(FileMapping::new);
        mapping.setOpenType(openType);
        mapping.setUserId(mappingUserId);
        mapping.setVirtualPath(virtualPath);
        mapping.setParentPath(StringUtils.defaultString(parentPath));
        mapping.setFileName(fileName);
        mapping.setFileType(FileTypeUtils.getFileType(file));
        mapping.setFileSize(file.length());
        mapping.setTargetPath(file.toPath().toAbsolutePath().normalize().toString());
        mapping.setDeleted(false);
        if (mapping.getCreateTime() == null) {
            mapping.setCreateTime(LocalDateTime.now());
        }
        mapping.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(mapping);
    }

    private void markDeletedByPrefix(Integer openType, String mappingUserId, String relativePath) {
        String prefix = relativePath + "/";
        LocalDateTime now = LocalDateTime.now();
        fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, mappingUserId)
                .stream()
                .filter(one -> one.getVirtualPath().equals(relativePath)
                        || StringUtils.startsWith(one.getVirtualPath(), prefix))
                .forEach(one -> {
                    one.setDeleted(true);
                    one.setUpdateTime(now);
                    fileMappingRepository.save(one);
                });
    }

    private void moveFileMappingTree(Integer openType, String mappingUserId, String originRelativePath, String newRelativePath) {
        String originPrefix = originRelativePath + "/";
        LocalDateTime now = LocalDateTime.now();
        fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, mappingUserId)
                .stream()
                .filter(one -> one.getVirtualPath().equals(originRelativePath)
                        || StringUtils.startsWith(one.getVirtualPath(), originPrefix))
                .forEach(one -> {
                    String oldPath = one.getVirtualPath();
                    String suffix = oldPath.equals(originRelativePath)
                            ? ""
                            : oldPath.substring(originRelativePath.length());
                    String updatedPath = newRelativePath + suffix;
                    one.setVirtualPath(updatedPath);
                    one.setParentPath(getParentPath(updatedPath));
                    one.setFileName(Path.of(updatedPath).getFileName().toString());
                    one.setUpdateTime(now);
                    fileMappingRepository.save(one);
                });
    }

    private File resolveMappedFile(FileMapping mapping) {
        return Path.of(mapping.getTargetPath()).toAbsolutePath().normalize().toFile();
    }

    private void checkPublicWriteAuthority(Integer openType) {
        if (openType != null && openType == 1
                && !AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "当前用户无法修改公共文件");
        }
    }

    private Path resolveTokenFile(String tokenFilePath, Claims claims) {
        if (StringUtils.startsWith(tokenFilePath, PUBLIC_DIRECTORY)) {
            String relativePath = tokenFilePath.substring(PUBLIC_DIRECTORY.length());
            Optional<FileMapping> mapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", relativePath);
            if (mapping.isPresent()) {
                return resolveMappedFile(mapping.get()).toPath();
            }
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
        }
        if (StringUtils.startsWith(tokenFilePath, PRIVATE_DIRECTORY)) {
            String privatePath = tokenFilePath.substring(PRIVATE_DIRECTORY.length());
            int separatorIndex = privatePath.indexOf('/');
            if (separatorIndex <= 0) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
            }
            String userId = privatePath.substring(0, separatorIndex);
            Object tokenUserId = claims.get("userId");
            if (tokenUserId == null || !userId.equals(String.valueOf(tokenUserId))) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
            }
            String relativePath = privatePath.substring(separatorIndex + 1);
            Optional<FileMapping> mapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, userId, relativePath);
            if (mapping.isPresent()) {
                return resolveMappedFile(mapping.get()).toPath();
            }
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
        }
        if (StringUtils.startsWith(tokenFilePath, PREVIEW_DIRECTORY)) {
            String relativePath = tokenFilePath.substring(PREVIEW_DIRECTORY.length());
            return FilePathGuard.resolveInsideRoot(fileServerPath + PREVIEW_DIRECTORY, relativePath);
        }
        throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
    }

    /**
     * 清理过期上传分片和兜底残留的临时ZIP。
     */
    @Scheduled(cron = "${file.tmpClean:0 30 3 * * ?}")
    public void cleanExpiredTmpFiles() {
        File tmpDirectory = new File(fileServerPath + TMP_DIRECTORY);
        if (!tmpDirectory.exists() || !tmpDirectory.isDirectory()) {
            return;
        }
        long expireBefore = Instant.now().toEpochMilli() - TMP_FILE_EXPIRE_MILLIS;
        File[] tmpFiles = tmpDirectory.listFiles((dir, name) -> name.contains(".part") || name.endsWith(".zip"));
        if (tmpFiles == null) {
            return;
        }
        for (File tmpFile : tmpFiles) {
            if (tmpFile.isFile() && tmpFile.lastModified() < expireBefore && !tmpFile.delete()) {
                log.warn("过期临时文件删除失败：{}", tmpFile.getAbsolutePath());
            }
        }
    }

    /**
     * 清理已逻辑删除且无有效映射引用的物理文件。
     *
     * <p>Q1：原实现 findAll() 一次性把全表载入堆，规模上来后 GC 抖动 / OOM 风险高。
     * 改为：(a) 仅取所有 active mapping 的不重复 targetPath（按引用集合）；
     * (b) 用 streamDeletedBefore 流式扫描"已删 + 过保留期"候选，逐条处理。
     * 仅遍历真正需要 GC 的子集，并保留原本的"被 active 引用就跳过"语义。</p>
     */
    @Scheduled(cron = "${file.mapping.gc.cron:0 0 4 * * ?}")
    @Transactional("fileServerTransactionManager")
    public void cleanDeletedFileMappings() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(Math.max(0, fileMappingGcRetentionDays));
        Set<String> activeTargetPaths = new HashSet<>(fileMappingRepository.findDistinctActiveTargetPaths());
        try (Stream<FileMapping> deletedStream = fileMappingRepository.streamDeletedBefore(threshold)) {
            deletedStream.forEach(mapping -> {
                String targetPath = mapping.getTargetPath();
                if (StringUtils.isBlank(targetPath) || activeTargetPaths.contains(targetPath)) {
                    return;
                }
                File target = Path.of(targetPath).toFile();
                if (target.exists() && !deletePhysicalRecursively(target)) {
                    log.warn("物理文件清理失败，id={}, targetPath={}", mapping.getId(), targetPath);
                    return;
                }
                fileMappingRepository.delete(mapping);
            });
        }
    }

    /**
     * 清理长时间未活动的上传合并锁，防止异常中断后锁对象堆积。
     */
    @Scheduled(cron = "${file.upload.lock.clean.cron:0 */10 * * * ?}")
    public void cleanExpiredUploadLocks() {
        long expireBefore = System.currentTimeMillis() - Math.max(60_000L, uploadLockExpireMillis);
        uploadMergeLockAccessTime.forEach((key, lastAccess) -> {
            if (lastAccess != null && lastAccess < expireBefore) {
                uploadMergeLocks.remove(key);
                uploadMergeLockAccessTime.remove(key);
            }
        });
    }

    private boolean deletePhysicalRecursively(File target) {
        if (!target.exists()) {
            return true;
        }
        boolean success = true;
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    success = deletePhysicalRecursively(child) && success;
                }
            }
        }
        return target.delete() && success;
    }

    @Override
    public void startFileMappingBackfill() {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "当前用户无权限执行回填");
        }
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "回填任务正在执行中，请稍后再试");
        }
        backfillStartTime = LocalDateTime.now();
        backfillEndTime = null;
        backfillLastError = null;
        backfillProcessedCount.set(0L);
        backfillCreatedCount.set(0L);
        backfillUpdatedCount.set(0L);
        fileExecutor.execute(() -> {
            try {
                doBackfillFileMapping();
            } catch (Exception e) {
                backfillLastError = e.getMessage();
                log.error("file_mapping 回填任务执行失败", e);
            } finally {
                backfillEndTime = LocalDateTime.now();
                backfillRunning.set(false);
            }
        });
    }

    @Override
    public Map<String, Object> getFileMappingBackfillStatus() {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "当前用户无权限查看回填状态");
        }
        Map<String, Object> status = new HashMap<>();
        status.put("running", backfillRunning.get());
        status.put("startTime", backfillStartTime);
        status.put("endTime", backfillEndTime);
        status.put("lastError", backfillLastError);
        status.put("processedCount", backfillProcessedCount.get());
        status.put("createdCount", backfillCreatedCount.get());
        status.put("updatedCount", backfillUpdatedCount.get());
        return status;
    }

    @Override
    public boolean existsUserFile(Integer openType, String userId, String filePath, boolean allowDirectory) {
        String mappingUserId = getMappingUserId(openType, userId);
        String relativePath = FilePathGuard.normalizeRelativePath(filePath, true);
        if (allowDirectory) {
            Optional<FileMapping> directoryMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, relativePath);
            if (directoryMapping.isPresent()) {
                File mappedFile = resolveMappedFile(directoryMapping.get());
                return mappedFile.exists() && mappedFile.isDirectory();
            }
            String prefix = StringUtils.isBlank(relativePath) ? "" : relativePath + "/";
            boolean hasChildren = fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, mappingUserId)
                    .stream()
                    .anyMatch(one -> StringUtils.isBlank(prefix) || StringUtils.startsWith(one.getVirtualPath(), prefix));
            if (hasChildren) {
                return true;
            }
        }
        try {
            Path path = resolveUserRequestFile(openType, userId, filePath);
            File file = path.toFile();
            if (!file.exists()) {
                return false;
            }
            return allowDirectory || !file.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    private void doBackfillFileMapping() {
        File publicRoot = Path.of(fileServerPath, "public").toFile();
        if (publicRoot.exists() && publicRoot.isDirectory()) {
            upsertTree(1, "public", publicRoot);
        }

        File privateRoot = Path.of(fileServerPath, "private").toFile();
        if (privateRoot.exists() && privateRoot.isDirectory()) {
            File[] userDirectories = privateRoot.listFiles(File::isDirectory);
            if (userDirectories != null) {
                for (File userDirectory : userDirectories) {
                    String userId = userDirectory.getName();
                    if (StringUtils.isNotBlank(userId)) {
                        upsertTree(0, userId, userDirectory);
                    }
                }
            }
        }
    }

    private void upsertTree(Integer openType, String userId, File rootDirectory) {
        List<FileMapping> existing = fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId);
        Map<String, FileMapping> existingMap = new HashMap<>();
        for (FileMapping mapping : existing) {
            existingMap.put(mapping.getVirtualPath(), mapping);
        }
        walkAndUpsert(openType, userId, rootDirectory, rootDirectory, existingMap);
    }

    private void walkAndUpsert(Integer openType, String userId, File rootDirectory, File current,
                               Map<String, FileMapping> existingMap) {
        if (!current.exists()) {
            return;
        }

        if (!rootDirectory.equals(current)) {
            String virtualPath = rootDirectory.toPath()
                    .toAbsolutePath()
                    .normalize()
                    .relativize(current.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace("\\", "/");
            FileMapping oldMapping = existingMap.get(virtualPath);
            saveOrUpdateFileMapping(openType, userId, virtualPath, getParentPath(virtualPath), current.getName(), current);
            if (oldMapping == null) {
                backfillCreatedCount.incrementAndGet();
            } else {
                backfillUpdatedCount.incrementAndGet();
            }
            backfillProcessedCount.incrementAndGet();
        }

        if (!current.isDirectory()) {
            return;
        }
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            walkAndUpsert(openType, userId, rootDirectory, child, existingMap);
        }
    }

    // =====================================================================
    // M3：文件搜索 + 回收站
    // =====================================================================

    @Override
    public PageResponseDto<FileResponseDto> searchFiles(SearchFileRequestDto request) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());

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
                .map(this::toFileResponseDto)
                .filter(dto -> dto.getFile() != null && dto.getFile().exists())
                .peek(dto -> {
                    packagePreviewLink(request.getOpenType(), dto);
                    packageVideoTranscodeInfo(request.getOpenType(), dto);
                    String virtualPath = StringUtils.stripStart(dto.getFilePath(), "/") + dto.getFileName();
                    dto.setDownloadLink(createUserFileAccessLink("/download", virtualPath, request.getOpenType()));
                    dto.setStreamLink(createUserFileAccessLink("/stream", virtualPath, request.getOpenType()));
                    dto.setFile(null);
                })
                .collect(Collectors.toList());

        return new PageResponseDto<>(items, page.getTotalElements(), request.getPageNumber(), request.getPageSize());
    }

    @Override
    public PageResponseDto<TrashFileResponseDto> listTrash(Integer openType, Integer pageNumber, Integer pageSize) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        checkPublicWriteAuthority(openType);
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = getMappingUserId(openType, loginUser.getUserId().toString());

        int normalizedPageNumber = Math.max(1, pageNumber == null ? 1 : pageNumber);
        int normalizedPageSize = pageSize == null ? 50 : Math.max(1, Math.min(200, pageSize));
        Pageable pageable = PageRequest.of(normalizedPageNumber - 1, normalizedPageSize);

        Page<FileMapping> page = fileMappingRepository
                .findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(openType, mappingUserId, pageable);

        List<TrashFileResponseDto> items = page.getContent().stream()
                .map(this::toTrashResponseDto)
                .collect(Collectors.toList());
        return new PageResponseDto<>(items, page.getTotalElements(), normalizedPageNumber, normalizedPageSize);
    }

    private TrashFileResponseDto toTrashResponseDto(FileMapping mapping) {
        TrashFileResponseDto dto = new TrashFileResponseDto();
        dto.setId(mapping.getId());
        dto.setFileName(mapping.getFileName());
        dto.setFileType(mapping.getFileType());
        dto.setFileSize(mapping.getFileSize());
        dto.setOriginalPath(mapping.getVirtualPath());
        dto.setOriginalParentPath(mapping.getParentPath());
        dto.setDeleteTime(mapping.getUpdateTime() != null ? mapping.getUpdateTime() : mapping.getCreateTime());
        return dto;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void restoreFromTrash(Long id) {
        if (id == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "id 不能为空");
        }
        FileMapping mapping = fileMappingRepository.findById(id)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "回收站项不存在或已被永久删除"));
        if (!Boolean.TRUE.equals(mapping.getDeleted())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目标项不在回收站中");
        }
        checkPublicWriteAuthority(mapping.getOpenType());
        ensureMappingOwnership(mapping);

        // 还原前先确认目标 virtualPath 上没有 active mapping 占位
        Optional<FileMapping> activeAtSamePath = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        mapping.getOpenType(), mapping.getUserId(), mapping.getVirtualPath());
        if (activeAtSamePath.isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "原位置已有同名文件，请先重命名当前文件再还原");
        }

        // 还原物理文件需存在；若已被 GC 物理清掉则不允许还原
        if (StringUtils.isNotBlank(mapping.getTargetPath())
                && !Path.of(mapping.getTargetPath()).toFile().exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "底层文件已被清理，无法还原");
        }

        mapping.setDeleted(false);
        mapping.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(mapping);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void purgeFromTrash(Long id) {
        if (id == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "id 不能为空");
        }
        FileMapping mapping = fileMappingRepository.findById(id)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "回收站项不存在或已被永久删除"));
        if (!Boolean.TRUE.equals(mapping.getDeleted())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目标项不在回收站中，请先删除再永久删除");
        }
        checkPublicWriteAuthority(mapping.getOpenType());
        ensureMappingOwnership(mapping);

        String targetPath = mapping.getTargetPath();
        if (StringUtils.isNotBlank(targetPath)) {
            Set<String> activeTargetPaths = new HashSet<>(fileMappingRepository.findDistinctActiveTargetPaths());
            if (!activeTargetPaths.contains(targetPath)) {
                File target = Path.of(targetPath).toFile();
                if (target.exists() && !deletePhysicalRecursively(target)) {
                    log.warn("永久删除物理文件失败，id={}, targetPath={}", id, targetPath);
                    throw new ServiceException(HttpStatus.ERROR, "底层文件清理失败，请稍后重试");
                }
            }
        }
        fileMappingRepository.delete(mapping);
    }

    /**
     * 校验 mapping 归属：私人空间必须是当前登录用户；公共空间必须是管理员。
     * checkPublicWriteAuthority 已经覆盖公共空间，本方法只补私人空间归属。
     */
    private void ensureMappingOwnership(FileMapping mapping) {
        if (mapping.getOpenType() != null && mapping.getOpenType() == 0) {
            LoginUser loginUser = LoginMessageUtil.getLoginUser()
                    .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
            if (!StringUtils.equals(mapping.getUserId(), loginUser.getUserId().toString())) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "无权操作他人文件");
            }
        }
    }

    // =====================================================================
    // M4：批量操作 + ZIP 文件夹下载
    // =====================================================================

    @Override
    @Transactional("fileServerTransactionManager")
    public BatchOperationResultDto batchDelete(BatchFileRequestDto request) {
        checkPublicWriteAuthority(request.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
        BatchOperationResultDto result = new BatchOperationResultDto();

        for (String filePath : request.getFilePaths()) {
            try {
                String relativePath = FilePathGuard.normalizeRelativePath(filePath);
                if (!hasMappingUnderPath(request.getOpenType(), mappingUserId, relativePath)) {
                    result.addFailure(filePath, "不存在");
                    continue;
                }
                markDeletedByPrefix(request.getOpenType(), mappingUserId, relativePath);
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
        checkPublicWriteAuthority(request.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
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
                if (!hasMappingUnderPath(request.getOpenType(), mappingUserId, origin)) {
                    result.addFailure(filePath, "原文件不存在");
                    continue;
                }
                boolean targetExists = fileMappingRepository
                        .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                                request.getOpenType(), mappingUserId, newPath)
                        .isPresent();
                if (targetExists
                        || hasMoveDestinationConflict(request.getOpenType(), mappingUserId, origin, newPath)) {
                    result.addFailure(filePath, "目标目录已存在同名文件");
                    continue;
                }
                moveFileMappingTree(request.getOpenType(), mappingUserId, origin, newPath);
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

    @Override
    public void downloadDirectoryAsZip(FileRequestDto fileRequestDto, HttpServletResponse response) {
        File directory = resolveUserRequestFile(fileRequestDto).toFile();
        if (!directory.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录不存在或已被删除");
        }
        if (!directory.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前路径不是目录");
        }
        // 复用既有的体积/文件数限制
        checkDirectoryDownloadLimit(directory);

        String zipBaseName = directory.getName();
        if (StringUtils.isBlank(zipBaseName) || ".".equals(zipBaseName) || "..".equals(zipBaseName)) {
            zipBaseName = "download";
        }
        String fallbackName = zipBaseName + ".zip";
        String encodedName = URLEncoder.encode(fallbackName, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/zip");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"download.zip\"; filename*=UTF-8''" + encodedName);
        response.setHeader("Cache-Control", "no-store");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            Path rootPath = directory.toPath();
            // 用 try-with-resources 包住 Files.walk，确保流释放
            try (Stream<Path> walk = Files.walk(rootPath)) {
                Iterator<Path> it = walk.iterator();
                while (it.hasNext()) {
                    Path path = it.next();
                    if (path.equals(rootPath)) {
                        continue;
                    }
                    String entryName = rootPath.relativize(path).toString().replace('\\', '/');
                    try {
                        if (Files.isDirectory(path)) {
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName + "/"));
                            zos.closeEntry();
                        } else {
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        }
                    } catch (IOException ioe) {
                        log.warn("ZIP 单项失败，跳过：{}", path, ioe);
                    }
                }
            }
        } catch (IOException e) {
            // tomcat 原话：客户端断连导致的 IO 异常吃掉，仅记日志
            log.warn("流式 ZIP 下载中断", e);
        }
    }
}
