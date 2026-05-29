package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.SaveTextRequestDto;
import com.misu.fileServer.domain.dto.TextContentResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileTextService;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 文本编辑相关 Service 实现。
 *
 * <p>逐字搬运自原 {@code FileServiceImpl}，行为完全不变：读取限 1 MB、UTF-8 解码（含 BOM 检测 /
 * 二进制探测）；覆盖保存内容字节同样卡 1 MB，写入前 M18 快照旧内容，写入后同步 mapping 的
 * size / md5 / updateTime。</p>
 *
 * @author misu
 */
@Slf4j
@Service
public class FileTextServiceImpl implements FileTextService {

    /** 文本预览/编辑大小上限（1 MB） */
    private static final long TEXT_PREVIEW_MAX_BYTES = 1024 * 1024L;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FilePathResolver filePathResolver;

    @Resource
    private FileAuthorityChecker fileAuthorityChecker;

    @Override
    public TextContentResponseDto getTextContent(Integer openType, String filePath) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        Path resolved = filePathResolver.resolveUserRequestFile(new FileRequestDto(filePath, openType));
        File file = resolved.toFile();
        if (!file.exists() || file.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或为目录");
        }
        if (file.length() > TEXT_PREVIEW_MAX_BYTES) {
            throw new ServiceException(HttpStatus.BAD_REQUEST,
                    "文件过大（>" + (TEXT_PREVIEW_MAX_BYTES / 1024) + "KB），不支持在线编辑");
        }
        TextContentResponseDto dto = new TextContentResponseDto();
        dto.setSizeBytes(file.length());
        try {
            byte[] bytes = Files.readAllBytes(resolved);
            String encodingHint = null;
            int offset = 0;
            // UTF-8 BOM 检测
            if (bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) {
                encodingHint = "utf-8-bom";
                offset = 3;
            }
            // 检测疑似二进制（前 8KB 含 NUL 字节）
            int probe = Math.min(bytes.length, 8 * 1024);
            boolean binaryLikely = false;
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) { binaryLikely = true; break; }
            }
            dto.setEncodingHint(encodingHint);
            dto.setBinaryLikely(binaryLikely);
            dto.setContent(new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.ERROR, "读取文件失败：" + e.getMessage());
        }
        return dto;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void saveTextContent(SaveTextRequestDto request) {
        fileAuthorityChecker.checkPublicWriteAuthority(request.getOpenType());
        Path resolved = filePathResolver.resolveUserRequestFile(new FileRequestDto(request.getFilePath(), request.getOpenType()));
        File file = resolved.toFile();
        if (!file.exists() || file.isDirectory()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或为目录");
        }
        // 写入限制：内容字节数也卡在 1 MB
        byte[] data = (request.getContent() == null ? "" : request.getContent()).getBytes(StandardCharsets.UTF_8);
        if (data.length > TEXT_PREVIEW_MAX_BYTES) {
            throw new ServiceException(HttpStatus.BAD_REQUEST,
                    "内容过大（>" + (TEXT_PREVIEW_MAX_BYTES / 1024) + "KB）");
        }

        // 找到 mapping（需要在写入前拿到，用于快照）
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(request.getOpenType(), loginUser.getUserId().toString());
        String relativePath = FilePathGuard.normalizeRelativePath(request.getFilePath());
        Optional<FileMapping> mappingOpt = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        request.getOpenType(), mappingUserId, relativePath);

        // M18：写入前快照旧内容
        mappingOpt.ifPresent(m -> fileVersionService.snapshotIfEligible(m, "TEXT_EDIT"));

        try {
            Files.write(resolved, data);
            mappingOpt.ifPresent(m -> {
                m.setFileSize((long) data.length);
                m.setFileMd5(DigestUtils.md5Hex(data));
                m.setUpdateTime(LocalDateTime.now());
                fileMappingRepository.save(m);
            });
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.ERROR, "写入文件失败：" + e.getMessage());
        }
    }
}
