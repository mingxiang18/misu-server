package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.util.FileTypeUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * file_mapping CRUD + 虚拟路径树管理组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的「file_mapping 表 + 虚拟路径树」职责：围绕
 * {@link FileMappingRepository} 做 mapping 的新建 / 更新、按前缀逻辑删除、子树移动（前缀重写）、
 * 物理目录树到虚拟路径的登记、子树 / 父路径 / 移动目标冲突检测，以及把某用户子树克隆到 public。
 * 方法签名 / 逻辑与原 god class 完全一致，仅可见性放宽为 public 供 FileServiceImpl 委托；
 * 路径计算复用已抽出的 {@link FilePathResolver}，不重复实现。</p>
 *
 * @author misu
 */
@Slf4j
@Component
public class FileMappingManager {

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FilePathResolver filePathResolver;

    public boolean hasMappingUnderPath(Integer openType, String userId, String relativePath) {
        String prefix = relativePath + "/";
        return fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId)
                .stream()
                .anyMatch(one -> one.getVirtualPath().equals(relativePath)
                        || StringUtils.startsWith(one.getVirtualPath(), prefix));
    }

    public boolean hasMappingParentConflict(Integer openType, String userId, String relativePath) {
        String current = relativePath;
        while (StringUtils.isNotBlank(current)) {
            String parent = filePathResolver.getParentPath(current);
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

    public boolean hasMoveDestinationConflict(Integer openType, String userId, String originRelativePath, String newRelativePath) {
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

    public void mapPhysicalTreeToVirtualPaths(Integer openType, String userId, String rootVirtualPath, File physicalRoot) {
        saveOrUpdateFileMapping(openType, userId, rootVirtualPath, filePathResolver.getParentPath(rootVirtualPath),
                Path.of(rootVirtualPath).getFileName().toString(), physicalRoot);
        if (!physicalRoot.isDirectory()) {
            return;
        }
        walkAndMapChildren(openType, userId, rootVirtualPath, physicalRoot, physicalRoot);
    }

    public void walkAndMapChildren(Integer openType, String userId, String rootVirtualPath, File rootPhysical, File current) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        Path rootPath = rootPhysical.toPath().toAbsolutePath().normalize();
        for (File child : children) {
            Path childPath = child.toPath().toAbsolutePath().normalize();
            String suffix = rootPath.relativize(childPath).toString().replace("\\", "/");
            String childVirtualPath = rootVirtualPath + "/" + suffix;
            saveOrUpdateFileMapping(openType, userId, childVirtualPath, filePathResolver.getParentPath(childVirtualPath), child.getName(), child);
            if (child.isDirectory()) {
                walkAndMapChildren(openType, userId, rootVirtualPath, rootPhysical, child);
            }
        }
    }

    public void saveOrUpdateFileMapping(Integer openType, String mappingUserId, String virtualPath,
                                        String parentPath, String fileName, File file) {
        saveOrUpdateFileMapping(openType, mappingUserId, virtualPath, parentPath, fileName, file, null);
    }

    public void saveOrUpdateFileMapping(Integer openType, String mappingUserId, String virtualPath,
                                        String parentPath, String fileName, File file, String fileMd5) {
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
        if (StringUtils.isNotBlank(fileMd5)) {
            mapping.setFileMd5(fileMd5);
        }
        mapping.setDeleted(false);
        if (mapping.getCreateTime() == null) {
            mapping.setCreateTime(LocalDateTime.now());
        }
        mapping.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(mapping);
    }

    public void markDeletedByPrefix(Integer openType, String mappingUserId, String relativePath) {
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

    public void moveFileMappingTree(Integer openType, String mappingUserId, String originRelativePath, String newRelativePath) {
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
                    one.setParentPath(filePathResolver.getParentPath(updatedPath));
                    one.setFileName(Path.of(updatedPath).getFileName().toString());
                    one.setUpdateTime(now);
                    fileMappingRepository.save(one);
                });
    }

    public void cloneMappingSubtreeToPublic(String sourceUserId, String sourceRootPath, String targetRootPath) {
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
            target.setParentPath(filePathResolver.getParentPath(targetVirtualPath));
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

    public void savePublicFileMapping(String targetVirtualPath,
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
}
