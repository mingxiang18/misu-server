package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.ShareStagingRequestDto;
import com.misu.fileServer.domain.dto.StagingEntryDto;
import com.misu.fileServer.domain.dto.StorageUsageResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileAdminService;
import com.misu.fileServer.service.FileMaintenanceService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.FileResponseAssembler;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理员 / staging / 回填触发 Service 实现。
 *
 * <p>逻辑全部从 {@code FileServiceImpl} 平移而来，行为完全不变。</p>
 *
 * @author misu
 */
@Slf4j
@Service
public class FileAdminServiceImpl implements FileAdminService {

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${file.quota.privateBytesPerUser:-1}")
    private long privateQuotaBytesPerUser;

    /** 超级管理员维护的 staging 物理目录，给 SCP / 本地挂载等线下方式投递文件用。 */
    @Value("${file.staging.path:}")
    private String stagingPath;

    @Resource
    private FileMappingRepository fileMappingRepository;

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
    private FileMaintenanceService fileMaintenanceService;

    @Override
    public List<FileResponseDto> listFilesAsAdmin(Integer openType, String userId, String parentPath) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        fileAuthorityChecker.checkAdminViewAuthority();
        if (openType == 0 && StringUtils.isBlank(userId)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户ID不能为空");
        }
        String mappingUserId = filePathResolver.getMappingUserId(openType, StringUtils.defaultString(userId).trim());
        String relativePath = FilePathGuard.normalizeRelativePath(StringUtils.defaultString(parentPath), true);
        return fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        openType, mappingUserId, relativePath)
                .stream()
                .map(fileResponseAssembler::toFileResponseDto)
                .filter(dto -> dto.getFile() != null && dto.getFile().exists())
                .peek(dto -> dto.setFile(null))
                .collect(Collectors.toList());
    }

    @Override
    public StorageUsageResponseDto getStorageUsageAsAdmin(Integer openType, String userId) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        fileAuthorityChecker.checkAdminViewAuthority();
        String mappingUserId = filePathResolver.getMappingUserId(openType, StringUtils.defaultString(userId).trim());
        if (openType == 0 && StringUtils.isBlank(userId)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户ID不能为空");
        }
        StorageUsageResponseDto dto = new StorageUsageResponseDto();
        dto.setOpenType(openType);
        dto.setUsedBytes(fileMappingRepository.sumUsedBytes(openType, mappingUserId));
        dto.setFileCount(fileMappingRepository.countUsedFiles(openType, mappingUserId));
        if (openType == 0 && privateQuotaBytesPerUser > 0) {
            dto.setQuotaBytes(privateQuotaBytesPerUser);
        }
        return dto;
    }

    // =====================================================================
    // staging 物理目录 —— 管理员维护的 "落地区"，可右键共享到公共 / 私人
    // =====================================================================

    @Override
    public String getStagingRoot() {
        fileAuthorityChecker.checkAdminViewAuthority();
        return resolveStagingRoot().toString();
    }

    @Override
    public List<StagingEntryDto> listStaging(String subPath) {
        fileAuthorityChecker.checkAdminViewAuthority();
        Path root = resolveStagingRoot();
        physicalFileOps.ensureDirectoryExists(root);
        Path target = FilePathGuard.resolveInsideRoot(root.toString(), StringUtils.defaultString(subPath), true);
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "staging 子目录不存在");
        }

        List<StagingEntryDto> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(target)) {
            stream.forEach(child -> entries.add(toStagingEntry(root, child)));
        } catch (IOException e) {
            log.error("列出 staging 目录失败 target={}", target, e);
            throw new ServiceException(HttpStatus.ERROR, "读取 staging 目录失败");
        }

        entries.sort(Comparator
                .comparing(StagingEntryDto::getDirectory, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StagingEntryDto::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return entries;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void shareStagingToPublic(ShareStagingRequestDto request) {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权共享 staging 文件到公共目录");
        }
        File physicalRoot = resolveStagingSource(request.getSourceStagingPath());
        String targetVirtualPath = resolveStagingTargetVirtualPath(physicalRoot, request.getTargetVirtualPath());
        ensurePublicTargetDirectoryAvailable(targetVirtualPath);
        fileMappingManager.mapPhysicalTreeToVirtualPaths(1, "public", targetVirtualPath, physicalRoot);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void shareStagingToUser(ShareStagingRequestDto request) {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权共享 staging 文件到用户私人目录");
        }
        if (StringUtils.isBlank(request.getTargetUserId())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目标用户ID不能为空");
        }
        File physicalRoot = resolveStagingSource(request.getSourceStagingPath());
        String targetUserId = request.getTargetUserId().trim();
        String targetVirtualPath = resolveStagingTargetVirtualPath(physicalRoot, request.getTargetVirtualPath());
        ensurePrivateTargetDirectoryAvailable(targetUserId, targetVirtualPath);
        fileMappingManager.mapPhysicalTreeToVirtualPaths(0, targetUserId, targetVirtualPath, physicalRoot);
    }

    @Override
    public void startFileMappingBackfill() {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权限执行回填");
        }
        fileMaintenanceService.runBackfillAsync();
    }

    @Override
    public Map<String, Object> getFileMappingBackfillStatus() {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权限查看回填状态");
        }
        return fileMaintenanceService.getBackfillStatus();
    }

    // ===================== staging 私有 helper =====================

    private Path resolveStagingRoot() {
        String configured = StringUtils.isBlank(stagingPath)
                ? Path.of(fileServerPath).resolve("staging").toString()
                : stagingPath;
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private StagingEntryDto toStagingEntry(Path root, Path child) {
        StagingEntryDto dto = new StagingEntryDto();
        dto.setName(child.getFileName().toString());
        dto.setRelativePath(root.relativize(child).toString().replace("\\", "/"));
        boolean directory = Files.isDirectory(child);
        dto.setDirectory(directory);
        long size = 0L;
        LocalDateTime lastModified = null;
        try {
            if (!directory) {
                size = Files.size(child);
            }
            lastModified = Files.getLastModifiedTime(child).toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (IOException ignored) {
            // 单条失败不影响整体列表
        }
        dto.setSize(size);
        dto.setLastModified(lastModified);
        return dto;
    }

    private File resolveStagingSource(String sourceStagingPath) {
        Path root = resolveStagingRoot();
        physicalFileOps.ensureDirectoryExists(root);
        Path target = FilePathGuard.resolveInsideRoot(root.toString(), sourceStagingPath);
        File source = target.toFile();
        if (!source.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "staging 源文件不存在");
        }
        return source;
    }

    private String resolveStagingTargetVirtualPath(File physicalRoot, String requestedTargetPath) {
        if (StringUtils.isBlank(requestedTargetPath)) {
            return FilePathGuard.normalizeFileName(physicalRoot.getName());
        }
        String normalized = FilePathGuard.normalizeRelativePath(requestedTargetPath, true);
        if (StringUtils.isBlank(normalized)) {
            return FilePathGuard.normalizeFileName(physicalRoot.getName());
        }
        // 如果用户传的是目录式路径（末尾 /），则在其下使用源文件名
        if (requestedTargetPath.endsWith("/")) {
            return normalized + "/" + FilePathGuard.normalizeFileName(physicalRoot.getName());
        }
        return normalized;
    }

    private void ensurePublicTargetDirectoryAvailable(String targetVirtualPath) {
        String parent = filePathResolver.getParentPath(targetVirtualPath);
        if (StringUtils.isNotBlank(parent)) {
            FileMapping parentMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", parent)
                    .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "公共目标目录不存在：" + parent));
            if (!FileType.DIRECTORY_FILE.equals(parentMapping.getFileType())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目标父级不是目录");
            }
        }
        if (fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", targetVirtualPath)
                .isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "公共目录已存在同名文件或文件夹");
        }
    }

    private void ensurePrivateTargetDirectoryAvailable(String userId, String targetVirtualPath) {
        String parent = filePathResolver.getParentPath(targetVirtualPath);
        if (StringUtils.isNotBlank(parent)) {
            FileMapping parentMapping = fileMappingRepository
                    .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, userId, parent)
                    .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "用户目标目录不存在：" + parent));
            if (!FileType.DIRECTORY_FILE.equals(parentMapping.getFileType())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "用户目标父级不是目录");
            }
        }
        if (fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, userId, targetVirtualPath)
                .isPresent()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户目录已存在同名文件或文件夹");
        }
    }
}
