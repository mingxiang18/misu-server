package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.TrashFileResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileTrashService;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.FileResponseAssembler;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回收站相关 Service 实现。
 *
 * <p>逐字搬运自原 {@code FileServiceImpl}，行为完全不变：列表分页 + 删除时间倒序、还原（含归属
 * 校验 / 同名占位 / 底层物理文件存在性校验）、永久删除（物理文件 GC 仅在无其它 active mapping 引用时
 * 删除 + 级联清版本快照 {@link FileVersionService#purgeAllVersionsForMapping}）。</p>
 *
 * @author misu
 */
@Slf4j
@Service
public class FileTrashServiceImpl implements FileTrashService {

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FilePathResolver filePathResolver;

    @Resource
    private FileAuthorityChecker fileAuthorityChecker;

    @Resource
    private PhysicalFileOps physicalFileOps;

    @Resource
    private FileResponseAssembler fileResponseAssembler;

    @Override
    public PageResponseDto<TrashFileResponseDto> listTrash(Integer openType, Integer pageNumber, Integer pageSize) {
        if (openType == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件公开类型不能为空");
        }
        fileAuthorityChecker.checkPublicWriteAuthority(openType);
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String mappingUserId = filePathResolver.getMappingUserId(openType, loginUser.getUserId().toString());

        int normalizedPageNumber = Math.max(1, pageNumber == null ? 1 : pageNumber);
        int normalizedPageSize = pageSize == null ? 50 : Math.max(1, Math.min(200, pageSize));
        Pageable pageable = PageRequest.of(normalizedPageNumber - 1, normalizedPageSize);

        Page<FileMapping> page = fileMappingRepository
                .findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(openType, mappingUserId, pageable);

        List<TrashFileResponseDto> items = page.getContent().stream()
                .map(fileResponseAssembler::toTrashResponseDto)
                .collect(Collectors.toList());
        return new PageResponseDto<>(items, page.getTotalElements(), normalizedPageNumber, normalizedPageSize);
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
        fileAuthorityChecker.checkPublicWriteAuthority(mapping.getOpenType());
        fileAuthorityChecker.ensureMappingOwnership(mapping);

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
        fileAuthorityChecker.checkPublicWriteAuthority(mapping.getOpenType());
        fileAuthorityChecker.ensureMappingOwnership(mapping);

        String targetPath = mapping.getTargetPath();
        if (StringUtils.isNotBlank(targetPath)) {
            Set<String> activeTargetPaths = new HashSet<>(fileMappingRepository.findDistinctActiveTargetPaths());
            if (!activeTargetPaths.contains(targetPath)) {
                File target = Path.of(targetPath).toFile();
                if (target.exists() && !physicalFileOps.deletePhysicalRecursively(target)) {
                    log.warn("永久删除物理文件失败，id={}, targetPath={}", id, targetPath);
                    throw new ServiceException(HttpStatus.ERROR, "底层文件清理失败，请稍后重试");
                }
            }
        }
        // M18：级联清版本快照（不论物理还在不在）
        fileVersionService.purgeAllVersionsForMapping(mapping.getId());
        fileMappingRepository.delete(mapping);
    }
}
