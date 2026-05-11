package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.FileVersionDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.domain.entity.FileVersion;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.repository.FileVersionRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileVersionServiceImpl implements FileVersionService {

    @Value("${file-server.path}")
    private String fileServerPath;

    /** 大文件门槛：≤50 MB 才打快照（>50MB 直接跳过，节省磁盘） */
    @Value("${file.version.maxBytesPerSnapshot:52428800}")
    private long maxBytesPerSnapshot;

    /** 每文件保留的版本上限，超出删最旧 */
    @Value("${file.version.maxVersionsPerFile:5}")
    private int maxVersionsPerFile;

    @Resource
    private FileVersionRepository fileVersionRepository;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Override
    @Transactional("fileServerTransactionManager")
    public Optional<FileVersion> snapshotIfEligible(FileMapping currentMapping, String reason) {
        if (currentMapping == null || currentMapping.getId() == null) {
            return Optional.empty();
        }
        if (FileType.DIRECTORY_FILE.equals(currentMapping.getFileType())) {
            return Optional.empty();   // 目录占位不快照
        }
        if (StringUtils.isBlank(currentMapping.getTargetPath())) {
            return Optional.empty();
        }
        File current = Path.of(currentMapping.getTargetPath()).toFile();
        if (!current.exists() || !current.isFile()) {
            return Optional.empty();
        }
        if (current.length() > maxBytesPerSnapshot) {
            log.debug("skip snapshot: file too large mappingId={} size={}",
                    currentMapping.getId(), current.length());
            return Optional.empty();
        }

        try {
            int nextVersionNo = nextVersionNo(currentMapping.getId());
            File snapshotFile = buildSnapshotPath(currentMapping, nextVersionNo);
            Files.createDirectories(snapshotFile.toPath().getParent());
            Files.copy(current.toPath(), snapshotFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

            FileVersion version = new FileVersion();
            version.setMappingId(currentMapping.getId());
            version.setVersionNo(nextVersionNo);
            version.setFileSize(current.length());
            version.setFileMd5(computeMd5(current));
            version.setSnapshotTargetPath(snapshotFile.getAbsolutePath());
            version.setOriginalFileName(currentMapping.getFileName());
            version.setSnapshotReason(StringUtils.defaultString(reason, "OVERWRITE")
                    .toUpperCase(Locale.ROOT));
            version.setSnapshotByUserId(LoginMessageUtil.getLoginUser()
                    .map(u -> String.valueOf(u.getUserId())).orElse(null));
            version.setCreateTime(LocalDateTime.now());
            FileVersion saved = fileVersionRepository.save(version);

            enforceMaxVersions(currentMapping.getId());
            return Optional.of(saved);
        } catch (Exception e) {
            // 快照失败不影响主流程
            log.warn("snapshot failed mappingId={} reason={}", currentMapping.getId(), reason, e);
            return Optional.empty();
        }
    }

    @Override
    public List<FileVersionDto> listVersions(FileMapping currentMapping) {
        if (currentMapping == null || currentMapping.getId() == null) {
            return List.of();
        }
        return fileVersionRepository.findByMappingIdOrderByVersionNoDesc(currentMapping.getId())
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void restoreVersion(Long versionId) {
        FileVersion version = fileVersionRepository.findById(versionId)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "版本不存在"));
        FileMapping mapping = fileMappingRepository.findById(version.getMappingId())
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "原文件已不存在"));
        ensureMappingOwnership(mapping);

        File snapshotFile = Path.of(version.getSnapshotTargetPath()).toFile();
        if (!snapshotFile.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "版本快照已被清理，无法还原");
        }
        File current = Path.of(mapping.getTargetPath()).toFile();

        // 1) 当前内容打成一个新版本（保留循环还原能力）
        snapshotIfEligible(mapping, "RESTORE_DEMOTE");

        // 2) 把快照内容写回当前 mapping 的 targetPath
        try {
            Files.copy(snapshotFile.toPath(), current.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.ERROR, "还原失败：" + e.getMessage());
        }

        // 3) 更新 mapping 的 size / md5 / fileName（原名也回滚以保持一致）
        mapping.setFileSize(current.length());
        mapping.setFileMd5(version.getFileMd5());
        mapping.setUpdateTime(LocalDateTime.now());
        // 注意：不动 virtual_path / parent_path / file_name；文件名快照仅供参考
        fileMappingRepository.save(mapping);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void purgeVersion(Long versionId) {
        FileVersion version = fileVersionRepository.findById(versionId)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "版本不存在"));
        FileMapping mapping = fileMappingRepository.findById(version.getMappingId()).orElse(null);
        if (mapping != null) {
            ensureMappingOwnership(mapping);
        }
        deletePhysicalSnapshot(version);
        fileVersionRepository.delete(version);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void purgeAllVersionsForMapping(Long mappingId) {
        for (FileVersion v : fileVersionRepository.findByMappingIdOrderByVersionNoDesc(mappingId)) {
            deletePhysicalSnapshot(v);
        }
        fileVersionRepository.deleteByMappingId(mappingId);
    }

    // ============== internal ==============

    private void enforceMaxVersions(Long mappingId) {
        if (maxVersionsPerFile <= 0) return;
        long total = fileVersionRepository.countByMappingId(mappingId);
        if (total <= maxVersionsPerFile) return;
        List<FileVersion> oldest = fileVersionRepository.findOldestByMapping(mappingId);
        int toRemove = (int) (total - maxVersionsPerFile);
        for (int i = 0; i < toRemove && i < oldest.size(); i++) {
            FileVersion v = oldest.get(i);
            deletePhysicalSnapshot(v);
            fileVersionRepository.delete(v);
        }
    }

    private int nextVersionNo(Long mappingId) {
        Integer max = fileVersionRepository.findMaxVersionNoByMappingId(mappingId);
        return max == null ? 1 : max + 1;
    }

    private File buildSnapshotPath(FileMapping mapping, int versionNo) {
        String safeName = mapping.getFileName().replaceAll("[\\\\/:*?\"<>|]", "_");
        return Path.of(fileServerPath, "version",
                String.valueOf(mapping.getOpenType()),
                StringUtils.defaultString(mapping.getUserId(), "_"),
                String.valueOf(mapping.getId()),
                "v" + versionNo + "-" + safeName)
                .toAbsolutePath().normalize().toFile();
    }

    private static void deletePhysicalSnapshot(FileVersion version) {
        if (StringUtils.isBlank(version.getSnapshotTargetPath())) return;
        try {
            Files.deleteIfExists(Path.of(version.getSnapshotTargetPath()));
        } catch (IOException e) {
            log.warn("delete version snapshot failed id={} path={}", version.getId(), version.getSnapshotTargetPath(), e);
        }
    }

    private static String computeMd5(File f) {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            return DigestUtils.md5Hex(in);
        } catch (IOException e) {
            return null;
        }
    }

    private FileVersionDto toDto(FileVersion v) {
        FileVersionDto dto = new FileVersionDto();
        dto.setId(v.getId());
        dto.setVersionNo(v.getVersionNo());
        dto.setFileSize(v.getFileSize());
        dto.setFileMd5(v.getFileMd5());
        dto.setOriginalFileName(v.getOriginalFileName());
        dto.setSnapshotReason(v.getSnapshotReason());
        dto.setSnapshotByUserId(v.getSnapshotByUserId());
        dto.setCreateTime(v.getCreateTime());
        return dto;
    }

    /** 私人空间必须是当前登录用户；公共空间留给 checkPublicWriteAuthority 在调用方判 */
    private void ensureMappingOwnership(FileMapping mapping) {
        if (mapping.getOpenType() != null && mapping.getOpenType() == 0) {
            LoginUser loginUser = LoginMessageUtil.getLoginUser()
                    .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
            if (!StringUtils.equals(mapping.getUserId(), loginUser.getUserId().toString())) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "无权操作他人版本");
            }
        }
    }
}
