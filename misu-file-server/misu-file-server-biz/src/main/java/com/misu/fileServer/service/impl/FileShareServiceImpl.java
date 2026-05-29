package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.CreateShareRequestDto;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.ShareResponseDto;
import com.misu.fileServer.domain.dto.SharedInfoResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.domain.entity.FileShare;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.repository.FileShareRepository;
import com.misu.fileServer.service.FileAccessService;
import com.misu.fileServer.service.FileShareService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 外链分享业务实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>token 用 16 字节 SecureRandom + hex 生成（32 hex chars，无歧义字符）</li>
 *   <li>密码经 BCryptPasswordEncoder 加盐哈希，原文不入库</li>
 *   <li>下载时原子 INC downloadCount，再校验上限；同 race condition 下最多多一份</li>
 *   <li>分享指向 (openType, sourceUserId, virtualPath) 而不是 mappingId，原文件软删后链接自动失效</li>
 * </ul>
 */
@Slf4j
@Service
public class FileShareServiceImpl implements FileShareService {

    private static final SecureRandom RNG = new SecureRandom();

    @Resource
    private FileShareRepository fileShareRepository;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FileAccessService fileAccessService;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional("fileServerTransactionManager")
    public ShareResponseDto createShare(CreateShareRequestDto request) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));

        Integer openType = request.getOpenType();
        String relativePath = FilePathGuard.normalizeRelativePath(request.getFilePath());
        String sourceUserId = openType != null && openType == 1 ? "public" : loginUser.getUserId().toString();

        // 必须是当前用户能看到的存活文件
        FileMapping mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, sourceUserId, relativePath)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除"));

        FileShare share = new FileShare();
        share.setShareToken(generateToken());
        share.setOwnerUserId(loginUser.getUserId().toString());
        share.setOpenType(openType);
        share.setSourceUserId(sourceUserId);
        share.setSourceVirtualPath(relativePath);
        share.setExpireTime(LocalDateTime.now().plusMinutes(request.getExpireMinutes() == null ? 1440 : request.getExpireMinutes()));
        if (StringUtils.isNotBlank(request.getPassword())) {
            share.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        share.setMaxDownloads(request.getMaxDownloads());
        share.setDownloadCount(0);
        share.setRevoked(false);
        share.setCreateTime(LocalDateTime.now());
        share.setUpdateTime(LocalDateTime.now());

        FileShare saved = fileShareRepository.save(share);
        return toShareResponseDto(saved, mapping);
    }

    @Override
    public PageResponseDto<ShareResponseDto> listShares(Integer pageNumber, Integer pageSize) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        int p = Math.max(1, pageNumber == null ? 1 : pageNumber);
        int s = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        Pageable pageable = PageRequest.of(p - 1, s);

        Page<FileShare> page = fileShareRepository
                .findByOwnerUserIdAndRevokedFalseOrderByCreateTimeDesc(loginUser.getUserId().toString(), pageable);

        List<ShareResponseDto> items = page.getContent().stream()
                .map(share -> toShareResponseDto(share, lookupMapping(share)))
                .collect(Collectors.toList());
        return new PageResponseDto<>(items, page.getTotalElements(), p, s);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void revokeShare(Long id) {
        if (id == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "id 不能为空");
        }
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        FileShare share = fileShareRepository.findById(id)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "分享不存在或已被撤销"));
        if (!StringUtils.equals(share.getOwnerUserId(), loginUser.getUserId().toString())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "无权撤销他人分享");
        }
        if (Boolean.TRUE.equals(share.getRevoked())) {
            return;
        }
        share.setRevoked(true);
        share.setUpdateTime(LocalDateTime.now());
        fileShareRepository.save(share);
    }

    @Override
    public SharedInfoResponseDto getSharedInfo(String token) {
        FileShare share = fileShareRepository.findFirstByShareToken(token)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "分享不存在或已失效"));

        SharedInfoResponseDto dto = new SharedInfoResponseDto();
        dto.setRequirePassword(StringUtils.isNotBlank(share.getPasswordHash()));
        dto.setExpired(share.getExpireTime() != null && share.getExpireTime().isBefore(LocalDateTime.now()));
        dto.setRevoked(Boolean.TRUE.equals(share.getRevoked()));
        dto.setExhausted(share.getMaxDownloads() != null && share.getDownloadCount() >= share.getMaxDownloads());
        dto.setDownloadCount(share.getDownloadCount());
        dto.setMaxDownloads(share.getMaxDownloads());
        dto.setExpireTime(share.getExpireTime());

        FileMapping mapping = lookupMapping(share);
        if (mapping != null) {
            dto.setFileName(mapping.getFileName());
            dto.setFileType(mapping.getFileType());
            dto.setFileSize(mapping.getFileSize());
        }
        return dto;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void downloadShared(String token, String password,
                                HttpServletRequest request, HttpServletResponse response) {
        FileShare share = fileShareRepository.findFirstByShareToken(token)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "分享不存在或已失效"));
        if (Boolean.TRUE.equals(share.getRevoked())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "分享已被撤销");
        }
        if (share.getExpireTime() != null && share.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "分享已过期");
        }
        if (share.getMaxDownloads() != null && share.getDownloadCount() >= share.getMaxDownloads()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "下载次数已用完");
        }
        if (StringUtils.isNotBlank(share.getPasswordHash())) {
            if (StringUtils.isBlank(password) || !passwordEncoder.matches(password, share.getPasswordHash())) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "访问密码不正确");
            }
        }

        FileMapping mapping = lookupMapping(share);
        if (mapping == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "源文件已被删除");
        }
        // 自增下载次数（atomic update by id；race 时最多多走一份，可接受）
        fileShareRepository.incrementDownloadCount(share.getId(), LocalDateTime.now());

        // 委托既有的"按用户视角访问文件"，绕过登录态用 sourceUserId
        fileAccessService.accessUserFileAsUser(share.getOpenType(), share.getSourceUserId(),
                share.getSourceVirtualPath(), request, response, true);
    }

    // ================ helpers ================

    private static String generateToken() {
        byte[] buf = new byte[16];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private FileMapping lookupMapping(FileShare share) {
        return fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(
                        share.getOpenType(), share.getSourceUserId(), share.getSourceVirtualPath())
                .orElse(null);
    }

    private ShareResponseDto toShareResponseDto(FileShare share, FileMapping mapping) {
        ShareResponseDto dto = new ShareResponseDto();
        dto.setId(share.getId());
        dto.setShareToken(share.getShareToken());
        dto.setSourceVirtualPath(share.getSourceVirtualPath());
        dto.setOpenType(share.getOpenType());
        dto.setExpireTime(share.getExpireTime());
        dto.setHasPassword(StringUtils.isNotBlank(share.getPasswordHash()));
        dto.setMaxDownloads(share.getMaxDownloads());
        dto.setDownloadCount(share.getDownloadCount());
        dto.setRevoked(Boolean.TRUE.equals(share.getRevoked()));
        dto.setExpired(share.getExpireTime() != null && share.getExpireTime().isBefore(LocalDateTime.now()));
        dto.setExhausted(share.getMaxDownloads() != null && share.getDownloadCount() >= share.getMaxDownloads());
        dto.setCreateTime(share.getCreateTime());
        if (mapping != null) {
            dto.setFileName(mapping.getFileName());
            dto.setFileType(mapping.getFileType());
            dto.setFileSize(mapping.getFileSize());
        } else {
            // 源文件已删除时仍返回基本信息以便用户撤销 / 知晓状态
            String virtualPath = StringUtils.defaultString(share.getSourceVirtualPath());
            int idx = virtualPath.lastIndexOf('/');
            dto.setFileName(idx >= 0 ? virtualPath.substring(idx + 1) : virtualPath);
            dto.setFileType("unknown");
            dto.setFileSize(0L);
        }
        return dto;
    }
}
