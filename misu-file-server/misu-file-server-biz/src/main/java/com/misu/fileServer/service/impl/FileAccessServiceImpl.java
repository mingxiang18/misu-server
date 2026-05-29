package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.common.util.ZipUtils;
import com.misu.framework.web.HttpFileResponder;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.FileDownloadRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileAccessService;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.fileServer.util.FileTypeUtils;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文件读取 / 流式访问 Service 实现。
 *
 * <p>取字节 / 流式服务 / 跨用户访问从 {@code FileServiceImpl} 原样搬入，内部统一改走
 * {@link HttpFileResponder#write} 写出（Range/Accept-Ranges/ETag/If-None-Match/
 * If-Modified-Since(304)/Content-Disposition）。MIME 类型沿用原 {@code writeFileToResponse}
 * 的 {@code Files.probeContentType} 探测方式以保持 Content-Type 完全一致；目录会先打成
 * 临时 ZIP 再交给 responder 写出（与原行为一致）。目录流式 ZIP、转码视频状态判断仍由本 service 负责。</p>
 *
 * @author misu
 */
@Slf4j
@Service
public class FileAccessServiceImpl implements FileAccessService {

    private final static String FILE_DOWNLOAD_TOKEN_CACHE_KEY = "file-download-token:";

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${token.expireTtl:86400000}")
    private long tokenExpireTtl;

    @Resource
    private TokenService tokenService;

    @Resource
    private PreviewService previewService;

    @Resource
    private VideoTranscodeService videoTranscodeService;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FilePathResolver filePathResolver;

    @Resource
    private PhysicalFileOps physicalFileOps;

    @Resource
    private HttpFileResponder httpFileResponder;

    @Override
    public String getFileDownloadLink(FileRequestDto fileRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        Path targetPath = filePathResolver.resolveUserRequestFile(fileRequestDto);
        if (!targetPath.toFile().exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }

        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());
        String filePath = fileRequestDto.getOpenType() == 1 ?
                FilePathResolver.PUBLIC_DIRECTORY + relativePath :
                FilePathResolver.PRIVATE_DIRECTORY + loginUser.getUserId() + "/" + relativePath;

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
        File file = filePathResolver.resolveUserRequestFile(fileRequestDto).toFile();
        if (!file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        writeFileToResponse(response, request, file, attachment);
    }

    @Override
    public void accessUserFileAsUser(Integer openType, String userId, String filePath,
                                     HttpServletRequest request, HttpServletResponse response, boolean attachment) {
        File file = filePathResolver.resolveUserRequestFile(openType, userId, filePath).toFile();
        if (!file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        writeFileToResponse(response, request, file, attachment);
    }

    @Override
    public void previewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        File originFile = filePathResolver.resolveUserRequestFile(fileRequestDto).toFile();
        if (!originFile.exists() || originFile.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
        }
        if (!FileType.IMAGE_FILE.equals(FileTypeUtils.getFileType(originFile))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前文件不支持缩略图预览");
        }

        File previewFile = filePathResolver.getPreviewFile(originFile);
        if (!previewFile.exists()) {
            previewService.generatePreviewFile(originFile);
            previewFile = originFile;
        }
        writeFileToResponse(response, request, previewFile, false);
    }

    @Override
    public void videoPreviewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        File originFile = filePathResolver.resolveUserRequestFile(fileRequestDto).toFile();
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
        File originFile = filePathResolver.resolveUserRequestFile(fileRequestDto).toFile();
        writeTranscodedVideoToResponse(originFile, request, response);
    }

    @Override
    public void transcodedVideoFileAsUser(Integer openType, String userId, String filePath,
                                          HttpServletRequest request, HttpServletResponse response) {
        File originFile = filePathResolver.resolveUserRequestFile(openType, userId, filePath).toFile();
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
        // PASSTHROUGH：源文件本身就是 Safari 可播放的 HEVC+hvc1+AAC+MP4，没有 transcoded 产物，直接拉源文件
        if (VideoTranscodeState.PASSTHROUGH.equals(status.getState())) {
            writeFileToResponse(response, request, originFile, false);
            return;
        }
        if (!VideoTranscodeState.SUCCESS.equals(status.getState())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, StringUtils.defaultIfBlank(status.getMessage(), "视频尚未完成转码"));
        }

        File transcodedFile = videoTranscodeService.getTranscodedFile(originFile);
        if (!transcodedFile.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "转码视频不存在或已被删除");
        }
        writeFileToResponse(response, request, transcodedFile, false);
    }

    /**
     * 文件在 http 中响应。
     *
     * <p>目录会先压成临时 ZIP（与原 {@code FileServiceImpl#writeFileToResponse} 行为一致），
     * 随后把实际写出（Range/ETag/304/Content-Disposition）委托给 {@link HttpFileResponder#write}。
     * MIME 类型沿用原来的 {@code Files.probeContentType} 探测方式以保持 Content-Type 完全一致。</p>
     */
    @SneakyThrows
    private void writeFileToResponse(HttpServletResponse response, HttpServletRequest request, File file, boolean attachment) {
        if (file.exists()) {
            File tmpFile = null;
            File responseFile = file;
            try {
                if (file.isDirectory()) {
                    physicalFileOps.checkDirectoryDownloadLimit(file);
                    tmpFile = new File(fileServerPath + FilePathResolver.TMP_DIRECTORY + System.currentTimeMillis() + ".zip");
                    if (!tmpFile.getParentFile().exists()) {
                        tmpFile.getParentFile().mkdirs();
                    }
                    try (FileOutputStream tmpFileOutputStream = new FileOutputStream(tmpFile)) {
                        ZipUtils.toZip(file, tmpFileOutputStream);
                        //将下载文件指向压缩后的临时文件
                        responseFile = tmpFile;
                    }
                }

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

                httpFileResponder.write(request, response, responseFile, responseFile.getName(), contentType, attachment);
            } finally {
                if (tmpFile != null && tmpFile.exists() && !tmpFile.delete()) {
                    log.warn("临时ZIP文件删除失败：{}", tmpFile.getAbsolutePath());
                }
            }
        } else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在");
        }
    }

    private Path resolveTokenFile(String tokenFilePath, Claims claims) {
        if (StringUtils.startsWith(tokenFilePath, FilePathResolver.PUBLIC_DIRECTORY)) {
            String relativePath = tokenFilePath.substring(FilePathResolver.PUBLIC_DIRECTORY.length());
            Optional<FileMapping> mapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", relativePath);
            if (mapping.isPresent()) {
                return filePathResolver.resolveMappedFile(mapping.get()).toPath();
            }
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
        }
        if (StringUtils.startsWith(tokenFilePath, FilePathResolver.PRIVATE_DIRECTORY)) {
            String privatePath = tokenFilePath.substring(FilePathResolver.PRIVATE_DIRECTORY.length());
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
                return filePathResolver.resolveMappedFile(mapping.get()).toPath();
            }
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
        }
        if (StringUtils.startsWith(tokenFilePath, FilePathResolver.PREVIEW_DIRECTORY)) {
            String relativePath = tokenFilePath.substring(FilePathResolver.PREVIEW_DIRECTORY.length());
            return FilePathGuard.resolveInsideRoot(fileServerPath + FilePathResolver.PREVIEW_DIRECTORY, relativePath);
        }
        throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接不合法");
    }

    @Override
    public boolean existsUserFile(Integer openType, String userId, String filePath, boolean allowDirectory) {
        String mappingUserId = filePathResolver.getMappingUserId(openType, userId);
        String relativePath = FilePathGuard.normalizeRelativePath(filePath, true);
        if (allowDirectory) {
            Optional<FileMapping> directoryMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, relativePath);
            if (directoryMapping.isPresent()) {
                File mappedFile = filePathResolver.resolveMappedFile(directoryMapping.get());
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
            Path path = filePathResolver.resolveUserRequestFile(openType, userId, filePath);
            File file = path.toFile();
            if (!file.exists()) {
                return false;
            }
            return allowDirectory || !file.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void downloadDirectoryAsZip(FileRequestDto fileRequestDto, HttpServletResponse response) {
        // 本项目用 file_mapping 维护虚拟目录树，物理文件并不真的住在虚拟目录的 placeholder 下，
        // 因此 ZIP 必须按 mapping 子树驱动而不是 Files.walk(物理目录)。
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(fileRequestDto.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath());

        FileMapping rootMapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        fileRequestDto.getOpenType(), mappingUserId, relativePath)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "目录不存在或已被删除"));
        if (!FileType.DIRECTORY_FILE.equals(rootMapping.getFileType())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前路径不是目录");
        }

        List<FileMapping> subtree = fileMappingRepository.findActiveSubtree(
                fileRequestDto.getOpenType(), mappingUserId, relativePath, relativePath + "/%");
        // 体积/文件数限制（不计目录占位）
        long totalBytes = 0L;
        long fileCount = 0L;
        for (FileMapping m : subtree) {
            if (FileType.DIRECTORY_FILE.equals(m.getFileType())) {
                continue;
            }
            fileCount++;
            totalBytes += m.getFileSize() == null ? 0L : m.getFileSize();
        }
        if (fileCount > physicalFileOps.getDirectoryDownloadMaxFiles()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录文件数量过多，请选择较小目录或单文件下载");
        }
        if (totalBytes > physicalFileOps.getDirectoryDownloadMaxBytes()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录体积过大，请选择较小目录或单文件下载");
        }

        String zipBaseName = rootMapping.getFileName();
        if (StringUtils.isBlank(zipBaseName) || ".".equals(zipBaseName) || "..".equals(zipBaseName)) {
            zipBaseName = "download";
        }
        String fallbackName = zipBaseName + ".zip";
        String encodedName = URLEncoder.encode(fallbackName, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/zip");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"download.zip\"; filename*=UTF-8''" + encodedName);
        response.setHeader("Cache-Control", "no-store");

        String rootPrefix = relativePath.endsWith("/") ? relativePath : relativePath + "/";
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            for (FileMapping m : subtree) {
                if (m.getId().equals(rootMapping.getId())) {
                    continue;
                }
                String virtual = m.getVirtualPath();
                String entryName = virtual.startsWith(rootPrefix)
                        ? virtual.substring(rootPrefix.length())
                        : virtual;
                if (StringUtils.isBlank(entryName)) {
                    continue;
                }
                try {
                    if (FileType.DIRECTORY_FILE.equals(m.getFileType())) {
                        zos.putNextEntry(new java.util.zip.ZipEntry(entryName + "/"));
                        zos.closeEntry();
                    } else {
                        File physical = StringUtils.isBlank(m.getTargetPath())
                                ? null : Path.of(m.getTargetPath()).toFile();
                        if (physical == null || !physical.exists() || !physical.isFile()) {
                            log.warn("ZIP 跳过缺失文件 mappingId={} target={}", m.getId(), m.getTargetPath());
                            continue;
                        }
                        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                        Files.copy(physical.toPath(), zos);
                        zos.closeEntry();
                    }
                } catch (IOException ioe) {
                    log.warn("ZIP 单项失败，跳过：{}", virtual, ioe);
                }
            }
        } catch (IOException e) {
            // tomcat 原话：客户端断连导致的 IO 异常吃掉，仅记日志
            log.warn("流式 ZIP 下载中断", e);
        }
    }
}
