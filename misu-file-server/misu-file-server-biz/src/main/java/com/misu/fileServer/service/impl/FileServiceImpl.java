package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.common.util.ZipUtils;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.TorrentFileMapping;
import com.misu.fileServer.repository.TorrentFileMappingRepository;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.fileServer.util.FileTypeUtils;
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
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Resource
    private TokenService tokenService;
    
    @Resource
    private PreviewService previewService;

    @Resource
    private VideoTranscodeService videoTranscodeService;

    @Resource
    private TorrentFileMappingRepository torrentFileMappingRepository;

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
            String directory = getUserRootDirectory(fileRequestDto.getOpenType(), loginUser.get().getUserId().toString());
            List<FileResponseDto> fileList = getFileListFromDirectory(fileRequestDto, directory, userId);
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
    private List<FileResponseDto> getFileListFromDirectory(FileRequestDto fileRequestDto, String directory, String userId) {
        String requestPath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath(), true);
        Optional<TorrentFileMapping> coveringMapping = findCoveringMapping(fileRequestDto.getOpenType(), userId, requestPath);
        if (coveringMapping.isPresent()) {
            File mappedFile = resolveMappedFile(coveringMapping.get(), requestPath).toFile();
            if (mappedFile.exists() && mappedFile.isDirectory()) {
                return getFileListFromRealDirectory(mappedFile, requestPath);
            }
        }

        File directoryFile = new File(directory);
        File file = FilePathGuard.resolveInsideRoot(directory, requestPath, true).toFile();
        Map<String, FileResponseDto> fileMap = new LinkedHashMap<>();
        if (file.exists() && file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                Arrays.stream(files).map(oneFile -> buildFileResponseDto(oneFile, directoryFile))
                        .forEach(fileResponseDto -> fileMap.put(fileResponseDto.getFileName(), fileResponseDto));
            }
        }

        getMappingChildren(fileRequestDto.getOpenType(), userId, requestPath).forEach(fileResponseDto ->
                fileMap.putIfAbsent(fileResponseDto.getFileName(), fileResponseDto));

        return new ArrayList<>(fileMap.values());
    }

    private List<FileResponseDto> getFileListFromRealDirectory(File directory, String virtualDirectoryPath) {
        File[] files = directory.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
        return Arrays.stream(files).map(oneFile -> buildMappedFileResponseDto(oneFile, virtualDirectoryPath))
                .collect(Collectors.toList());
    }

    private FileResponseDto buildFileResponseDto(File oneFile, File directoryFile) {
        FileResponseDto fileResponseDto = new FileResponseDto();
        fileResponseDto.setFileName(oneFile.getName());
        fileResponseDto.setFileSize(oneFile.length());
        fileResponseDto.setFileType(FileTypeUtils.getFileType(oneFile));
        fileResponseDto.setFile(oneFile);

        String fileRelativePath = getRelativePath(oneFile, directoryFile);
        fileResponseDto.setFilePath("/" + (StringUtils.isBlank(fileRelativePath) ? "" : fileRelativePath + "/"));

        return fileResponseDto;
    }

    private FileResponseDto buildMappedFileResponseDto(File oneFile, String virtualDirectoryPath) {
        FileResponseDto fileResponseDto = new FileResponseDto();
        fileResponseDto.setFileName(oneFile.getName());
        fileResponseDto.setFileSize(oneFile.length());
        fileResponseDto.setFileType(FileTypeUtils.getFileType(oneFile));
        fileResponseDto.setFile(oneFile);
        fileResponseDto.setFilePath("/" + (StringUtils.isBlank(virtualDirectoryPath) ? "" : virtualDirectoryPath + "/"));
        return fileResponseDto;
    }

    /**
     * 获取相对路径
     */
    private static String getRelativePath(File originFile, File directoryFile) {
        // 使用 Path 把绝对路径中私密路径去掉，返回相对路径
        Path path = Paths.get(originFile.getParentFile().getAbsolutePath());
        Path base = Paths.get(directoryFile.getAbsolutePath());
        return base.relativize(path).toString().replace("\\", "/");
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
        String directory = getUserRootDirectory(fileUploadRequest.getOpenType(), loginUser.getUserId().toString());

        String fileName = FilePathGuard.normalizeFileName(fileUploadRequest.getFileName());
        Path parentPath = FilePathGuard.resolveInsideRoot(directory, fileUploadRequest.getFilePath(), true);
        File file = parentPath.resolve(fileName).normalize().toFile();
        if (!file.toPath().startsWith(Paths.get(directory).toAbsolutePath().normalize())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
        }

        //如果不允许覆盖且文件已存在，返回提示
        if (!fileUploadRequest.getCoverFlag() && file.exists()) {
            return new FileUploadResponse(2, "文件已存在");
        }

        //如果目录不存在，新建目录
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        String fileMD5 = DigestUtils.md5Hex(file.getAbsolutePath());
        //上传文件
        try {
            // 保存分片文件
            File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + fileUploadRequest.getChunkIndex());
            if (!chunkFile.getParentFile().exists()) {
                chunkFile.getParentFile().mkdirs();
            }
            fileUploadRequest.getFile().transferTo(chunkFile);

            // 如果所有分片都上传完成，则合并文件；支持乱序上传。
            if (allChunksUploaded(fileMD5, fileUploadRequest.getTotalChunks())) {
                mergeChunks(file, fileUploadRequest.getTotalChunks());
                //上传完成后执行后置操作
                fileAddAfter(file);
            }
        }catch (Exception e) {
            log.error("上传文件异常", e);
            //遍历删除分片
            for (int i = 0; i < fileUploadRequest.getTotalChunks(); i++) {
                File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + i);
                if (chunkFile.exists()) {
                    chunkFile.delete();
                }
            }
            //删除上传目录的文件
            if (file.exists()) {
                file.delete();
            }
            throw new ServiceException(HttpStatus.ERROR, "上传文件异常");
        }

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
    private void mergeChunks(File file, int totalChunks) throws IOException {
        //从绝对路径计算MD5
        String fileMD5 = DigestUtils.md5Hex(file.getAbsolutePath());

        try (FileOutputStream fileOutputStream = new FileOutputStream(file);) {
            for (int i = 0; i < totalChunks; i++) {
                // 分片文件
                File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + i);
                FileInputStream chunkInputStream = new FileInputStream(chunkFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = chunkInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                }

                chunkInputStream.close();
                chunkFile.delete();  // 删除分片文件
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

        if (file.exists() || torrentFileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(addFileInkRequest.getOpenType(), mappingUserId, virtualPath)
                .isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录下存在同名文件，无法同步至该目录");
        } else {
            Path target = Paths.get(addFileInkRequest.getInkFilePath());
            if (!Files.exists(target)) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "映射的原文件不存在");
            }
            TorrentFileMapping mapping = new TorrentFileMapping();
            mapping.setOpenType(addFileInkRequest.getOpenType());
            mapping.setUserId(mappingUserId);
            mapping.setVirtualPath(virtualPath);
            mapping.setTargetPath(target.toAbsolutePath().normalize().toString());
            mapping.setDeleted(false);
            mapping.setCreateTime(LocalDateTime.now());
            torrentFileMappingRepository.save(mapping);
        }
    }

    @Override
    public Boolean createDirectory(FileRequestDto fileRequestDto) {
        checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String fileDirectory = getUserRootDirectory(fileRequestDto.getOpenType(), loginUser.getUserId().toString());

        File file = FilePathGuard.resolveInsideRoot(fileDirectory, fileRequestDto.getFilePath()).toFile();

        if (file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "同名目录或文件已存在，无法创建");
        }else {
            return file.mkdirs();
        }
    }

    @Override
    public void moveFile(FileRenameRequestDto fileRenameRequestDto) {
        checkPublicWriteAuthority(fileRenameRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String fileDirectory = getUserRootDirectory(fileRenameRequestDto.getOpenType(), loginUser.getUserId().toString());

        File file = FilePathGuard.resolveInsideRoot(fileDirectory, fileRenameRequestDto.getOriginFilePath()).toFile();

        boolean isSuccess = false;
        if (file.exists()) {
            File newFile = FilePathGuard.resolveInsideRoot(fileDirectory, fileRenameRequestDto.getNewFilePath()).toFile();
            isSuccess = file.renameTo(newFile);

            //执行原文件删除后置操作
            fileDeleteAfter(file);
            //执行新文件添加的后置操作
            fileAddAfter(newFile);
        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "原文件不存在");
        }

        //如果不成功，抛出异常
        if (!isSuccess) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "移动失败，新的位置存在同名文件");
        }
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Boolean deleteFile(FileRequestDto fileRequestDto) {
        checkPublicWriteAuthority(fileRequestDto.getOpenType());
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        String mappingUserId = getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        Optional<TorrentFileMapping> mappingOptional = torrentFileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(fileRequestDto.getOpenType(), mappingUserId, relativePath);
        if (mappingOptional.isPresent()) {
            TorrentFileMapping mapping = mappingOptional.get();
            mapping.setDeleted(true);
            mapping.setUpdateTime(LocalDateTime.now());
            torrentFileMappingRepository.save(mapping);
            return true;
        }

        String fileDirectory = getUserRootDirectory(fileRequestDto.getOpenType(), loginUser.getUserId().toString());

        File file = FilePathGuard.resolveInsideRoot(fileDirectory, fileRequestDto.getFilePath()).toFile();

        //如果文件存在，执行删除
        if (file.exists()) {
            return deleteFile(file);
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
        String mappingUserId = getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        Optional<TorrentFileMapping> mapping = findCoveringMapping(fileRequestDto.getOpenType(), mappingUserId, relativePath);
        if (mapping.isPresent()) {
            return resolveMappedFile(mapping.get(), relativePath);
        }
        String directory = getUserRootDirectory(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        return FilePathGuard.resolveInsideRoot(directory, relativePath);
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

    private List<FileResponseDto> getMappingChildren(Integer openType, String userId, String requestPath) {
        String prefix = StringUtils.isBlank(requestPath) ? "" : requestPath + "/";
        return torrentFileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId)
                .stream()
                .filter(mapping -> StringUtils.startsWith(mapping.getVirtualPath(), prefix))
                .map(mapping -> toMappingChild(mapping, requestPath))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private FileResponseDto toMappingChild(TorrentFileMapping mapping, String requestPath) {
        String childPath = StringUtils.isBlank(requestPath)
                ? mapping.getVirtualPath()
                : StringUtils.removeStart(mapping.getVirtualPath(), requestPath + "/");
        if (StringUtils.isBlank(childPath)) {
            return null;
        }

        String childName = StringUtils.substringBefore(childPath, "/");
        Path childTarget;
        if (StringUtils.contains(childPath, "/")) {
            childTarget = Path.of(mapping.getTargetPath());
        } else {
            childTarget = Path.of(mapping.getTargetPath());
        }
        File targetFile = childTarget.toFile();
        if (!targetFile.exists()) {
            return null;
        }

        FileResponseDto fileResponseDto = new FileResponseDto();
        fileResponseDto.setFileName(childName);
        fileResponseDto.setFileSize(targetFile.length());
        fileResponseDto.setFileType(StringUtils.contains(childPath, "/") ? FileType.DIRECTORY_FILE : FileTypeUtils.getFileType(targetFile));
        fileResponseDto.setFile(targetFile);
        fileResponseDto.setFilePath("/" + (StringUtils.isBlank(requestPath) ? "" : requestPath + "/"));
        return fileResponseDto;
    }

    private Optional<TorrentFileMapping> findCoveringMapping(Integer openType, String userId, String relativePath) {
        return torrentFileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId)
                .stream()
                .filter(mapping -> relativePath.equals(mapping.getVirtualPath())
                        || StringUtils.startsWith(relativePath, mapping.getVirtualPath() + "/"))
                .max(Comparator.comparingInt(mapping -> mapping.getVirtualPath().length()));
    }

    private Path resolveMappedFile(TorrentFileMapping mapping, String relativePath) {
        Path targetRoot = Path.of(mapping.getTargetPath()).toAbsolutePath().normalize();
        String suffix = "";
        if (!relativePath.equals(mapping.getVirtualPath())) {
            suffix = relativePath.substring(mapping.getVirtualPath().length() + 1);
        }
        Path targetPath = targetRoot.resolve(suffix).normalize();
        if (!targetPath.startsWith(targetRoot)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
        }
        return targetPath;
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
            Optional<TorrentFileMapping> mapping = findCoveringMapping(1, "public", relativePath);
            if (mapping.isPresent()) {
                return resolveMappedFile(mapping.get(), relativePath);
            }
            return FilePathGuard.resolveInsideRoot(fileServerPath + PUBLIC_DIRECTORY, relativePath);
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
            Optional<TorrentFileMapping> mapping = findCoveringMapping(0, userId, relativePath);
            if (mapping.isPresent()) {
                return resolveMappedFile(mapping.get(), relativePath);
            }
            return FilePathGuard.resolveInsideRoot(fileServerPath + PRIVATE_DIRECTORY + userId + "/", relativePath);
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
}
