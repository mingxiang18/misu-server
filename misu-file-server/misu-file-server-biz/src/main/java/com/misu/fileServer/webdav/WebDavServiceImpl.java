package com.misu.fileServer.webdav;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileAccessService;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.fileServer.util.FileTypeUtils;
import com.misu.fileServer.util.UploadExtensionGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WebDAV 文件操作实现，直接作用于 {@code file_mapping}，不复用 {@code FileServiceImpl} 的私有逻辑。
 */
@Slf4j
@Service
public class WebDavServiceImpl implements WebDavService {

    /** WebDAV 仅暴露用户私有目录。 */
    private static final int OPEN_TYPE = 0;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FileAccessService fileAccessService;

    @Resource
    private UploadExtensionGuard uploadExtensionGuard;

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${webdav.max-upload-bytes:10737418240}")
    private long maxUploadBytes;

    @Override
    public WebDavResource stat(String userId, String virtualPath) {
        String path = FilePathGuard.normalizeRelativePath(virtualPath, true);
        if (path.isEmpty()) {
            return syntheticRoot();
        }
        Optional<FileMapping> mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, path);
        if (mapping.isEmpty()) {
            return null;
        }
        FileMapping found = mapping.get();
        if (!isDirectory(found) && !new File(found.getTargetPath()).exists()) {
            return null;
        }
        return toResource(found);
    }

    @Override
    public List<WebDavResource> listChildren(String userId, String virtualPath) {
        String path = FilePathGuard.normalizeRelativePath(virtualPath, true);
        return fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        OPEN_TYPE, userId, path)
                .stream()
                .filter(m -> isDirectory(m) || new File(m.getTargetPath()).exists())
                .map(this::toResource)
                .collect(Collectors.toList());
    }

    @Override
    public void get(String userId, String virtualPath, HttpServletRequest request, HttpServletResponse response) {
        String path = FilePathGuard.normalizeRelativePath(virtualPath);
        fileAccessService.accessUserFileAsUser(OPEN_TYPE, userId, path, request, response, false);
    }

    @Override
    public boolean store(String userId, String virtualPath, InputStream in) {
        String relativePath = FilePathGuard.normalizeRelativePath(virtualPath);
        String fileName = FilePathGuard.normalizeFileName(lastSegment(relativePath));
        uploadExtensionGuard.requireSafeForUpload(fileName);
        String parentPath = parentPath(relativePath);
        requireParentDirectory(userId, parentPath);

        Optional<FileMapping> existing = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, relativePath);
        if (existing.isPresent() && isDirectory(existing.get())) {
            throw new ServiceException(HttpStatus.BAD_METHOD, "目标是目录，无法写入文件");
        }

        File file = buildStorageFile(userId, fileName);
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        String md5;
        try {
            md5 = streamToFile(in, file);
        } catch (ServiceException se) {
            file.delete();
            throw se;
        } catch (IOException e) {
            file.delete();
            log.error("WebDAV 写文件失败: {}", relativePath, e);
            throw new ServiceException(HttpStatus.ERROR, "文件写入失败");
        }

        existing.ifPresent(m -> {
            try {
                fileVersionService.snapshotIfEligible(m, "OVERWRITE");
            } catch (RuntimeException ex) {
                log.warn("WebDAV 覆盖快照失败: {}", ex.getMessage());
            }
        });

        upsertFileRow(userId, relativePath, parentPath, fileName, file, md5);
        return existing.isEmpty();
    }

    @Override
    @Transactional
    public void mkcol(String userId, String virtualPath) {
        String relativePath = FilePathGuard.normalizeRelativePath(virtualPath);
        String dirName = FilePathGuard.normalizeFileName(lastSegment(relativePath));
        String parentPath = parentPath(relativePath);
        requireParentDirectory(userId, parentPath);
        if (fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, relativePath)
                .isPresent()) {
            throw new ServiceException(HttpStatus.BAD_METHOD, "同名目录或文件已存在");
        }
        File dir = buildVirtualDir(userId, dirName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new ServiceException(HttpStatus.ERROR, "目录创建失败");
        }
        FileMapping mapping = new FileMapping();
        mapping.setOpenType(OPEN_TYPE);
        mapping.setUserId(userId);
        mapping.setVirtualPath(relativePath);
        mapping.setParentPath(parentPath);
        mapping.setFileName(dirName);
        mapping.setFileType(FileType.DIRECTORY_FILE);
        mapping.setFileSize(0L);
        mapping.setTargetPath(normalizedPath(dir));
        mapping.setDeleted(false);
        mapping.setCreateTime(LocalDateTime.now());
        mapping.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(mapping);
    }

    @Override
    @Transactional
    public void delete(String userId, String virtualPath) {
        String relativePath = FilePathGuard.normalizeRelativePath(virtualPath);
        List<FileMapping> subtree = subtree(userId, relativePath);
        if (subtree.isEmpty()) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        markDeleted(subtree);
    }

    @Override
    @Transactional
    public void move(String userId, String src, String dest, boolean overwrite) {
        String srcRel = FilePathGuard.normalizeRelativePath(src);
        String destRel = FilePathGuard.normalizeRelativePath(dest);
        if (srcRel.equals(destRel)) {
            return;
        }
        if ((destRel + "/").startsWith(srcRel + "/")) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "不允许移动到自身子目录");
        }
        List<FileMapping> subtree = subtree(userId, srcRel);
        if (subtree.isEmpty()) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "源文件不存在");
        }
        requireParentDirectory(userId, parentPath(destRel));

        Optional<FileMapping> destExisting = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, destRel);
        if (destExisting.isPresent()) {
            if (!overwrite) {
                throw new ServiceException(WebDavStatus.PRECONDITION_FAILED, "目标已存在");
            }
            markDeleted(subtree(userId, destRel));
        }

        LocalDateTime now = LocalDateTime.now();
        for (FileMapping mapping : subtree) {
            String oldPath = mapping.getVirtualPath();
            String suffix = oldPath.equals(srcRel) ? "" : oldPath.substring(srcRel.length());
            String updated = destRel + suffix;
            mapping.setVirtualPath(updated);
            mapping.setParentPath(parentPath(updated));
            mapping.setFileName(lastSegment(updated));
            mapping.setUpdateTime(now);
        }
        fileMappingRepository.saveAll(subtree);
    }

    @Override
    @Transactional
    public void copy(String userId, String src, String dest, boolean overwrite) {
        String srcRel = FilePathGuard.normalizeRelativePath(src);
        String destRel = FilePathGuard.normalizeRelativePath(dest);
        if (srcRel.equals(destRel)) {
            return;
        }
        if ((destRel + "/").startsWith(srcRel + "/")) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "不允许复制到自身子目录");
        }
        List<FileMapping> subtree = subtree(userId, srcRel);
        if (subtree.isEmpty()) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "源文件不存在");
        }
        requireParentDirectory(userId, parentPath(destRel));

        Optional<FileMapping> destExisting = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, destRel);
        if (destExisting.isPresent()) {
            if (!overwrite) {
                throw new ServiceException(WebDavStatus.PRECONDITION_FAILED, "目标已存在");
            }
            markDeleted(subtree(userId, destRel));
        }

        LocalDateTime now = LocalDateTime.now();
        List<FileMapping> created = new ArrayList<>();
        for (FileMapping source : subtree) {
            String oldPath = source.getVirtualPath();
            String suffix = oldPath.equals(srcRel) ? "" : oldPath.substring(srcRel.length());
            String newPath = destRel + suffix;
            String newName = lastSegment(newPath);

            FileMapping copy = new FileMapping();
            copy.setOpenType(OPEN_TYPE);
            copy.setUserId(userId);
            copy.setVirtualPath(newPath);
            copy.setParentPath(parentPath(newPath));
            copy.setFileName(newName);
            copy.setFileType(source.getFileType());
            copy.setDeleted(false);
            copy.setCreateTime(now);
            copy.setUpdateTime(now);

            if (isDirectory(source)) {
                File dir = buildVirtualDir(userId, newName);
                dir.mkdirs();
                copy.setFileSize(0L);
                copy.setTargetPath(normalizedPath(dir));
            } else {
                File sourceFile = new File(source.getTargetPath());
                if (!sourceFile.exists()) {
                    log.warn("WebDAV COPY 跳过缺失的物理文件: {}", source.getVirtualPath());
                    continue;
                }
                File targetFile = buildStorageFile(userId, newName);
                File parentDir = targetFile.getParentFile();
                if (parentDir != null) {
                    parentDir.mkdirs();
                }
                try {
                    Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.error("WebDAV COPY 物理复制失败: {}", source.getVirtualPath(), e);
                    throw new ServiceException(HttpStatus.ERROR, "文件复制失败");
                }
                copy.setFileSize(source.getFileSize() != null ? source.getFileSize() : targetFile.length());
                copy.setFileMd5(source.getFileMd5());
                copy.setTargetPath(normalizedPath(targetFile));
            }
            created.add(copy);
        }
        fileMappingRepository.saveAll(created);
    }

    // ---------------------------------------------------------------- helpers

    private WebDavResource syntheticRoot() {
        WebDavResource root = new WebDavResource();
        root.setVirtualPath("");
        root.setDisplayName("/");
        root.setDirectory(true);
        root.setCreateTime(LocalDateTime.now());
        root.setLastModified(LocalDateTime.now());
        return root;
    }

    private WebDavResource toResource(FileMapping mapping) {
        WebDavResource resource = new WebDavResource();
        resource.setVirtualPath(mapping.getVirtualPath());
        resource.setDisplayName(mapping.getFileName());
        resource.setCreateTime(mapping.getCreateTime());
        resource.setLastModified(mapping.getUpdateTime() != null ? mapping.getUpdateTime() : mapping.getCreateTime());
        if (isDirectory(mapping)) {
            resource.setDirectory(true);
            return resource;
        }
        long size = mapping.getFileSize() != null ? mapping.getFileSize() : 0L;
        resource.setContentLength(size);
        String contentType = URLConnection.guessContentTypeFromName(mapping.getFileName());
        resource.setContentType(contentType != null ? contentType : "application/octet-stream");
        String tag = StringUtils.isNotBlank(mapping.getFileMd5()) ? mapping.getFileMd5() : Long.toHexString(size);
        resource.setEtag("\"" + tag + "-" + size + "\"");
        return resource;
    }

    private void requireParentDirectory(String userId, String parentPath) {
        if (parentPath.isEmpty()) {
            return;
        }
        FileMapping parent = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, parentPath)
                .orElseThrow(() -> new ServiceException(HttpStatus.CONFLICT, "父目录不存在"));
        if (!isDirectory(parent)) {
            throw new ServiceException(HttpStatus.CONFLICT, "父路径不是目录");
        }
    }

    private List<FileMapping> subtree(String userId, String relativePath) {
        String prefix = relativePath + "/";
        return fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(OPEN_TYPE, userId)
                .stream()
                .filter(m -> m.getVirtualPath().equals(relativePath) || m.getVirtualPath().startsWith(prefix))
                .collect(Collectors.toList());
    }

    private void markDeleted(List<FileMapping> rows) {
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        rows.forEach(m -> {
            m.setDeleted(true);
            m.setUpdateTime(now);
        });
        fileMappingRepository.saveAll(rows);
    }

    private void upsertFileRow(String userId, String virtualPath, String parentPath, String fileName,
                               File file, String md5) {
        FileMapping mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(OPEN_TYPE, userId, virtualPath)
                .orElseGet(FileMapping::new);
        mapping.setOpenType(OPEN_TYPE);
        mapping.setUserId(userId);
        mapping.setVirtualPath(virtualPath);
        mapping.setParentPath(parentPath);
        mapping.setFileName(fileName);
        mapping.setFileType(FileTypeUtils.getFileType(file));
        mapping.setFileSize(file.length());
        mapping.setTargetPath(normalizedPath(file));
        if (StringUtils.isNotBlank(md5)) {
            mapping.setFileMd5(md5);
        }
        mapping.setDeleted(false);
        if (mapping.getCreateTime() == null) {
            mapping.setCreateTime(LocalDateTime.now());
        }
        mapping.setUpdateTime(LocalDateTime.now());
        fileMappingRepository.save(mapping);
    }

    private String streamToFile(InputStream in, File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream out = new FileOutputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxUploadBytes) {
                    throw new ServiceException(WebDavStatus.PAYLOAD_TOO_LARGE, "上传文件超过大小上限");
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private File buildStorageFile(String userId, String fileName) {
        String extension = StringUtils.substringAfterLast(fileName, ".");
        String uniqueName = UUID.randomUUID() + (StringUtils.isBlank(extension) ? "" : "." + extension);
        return Path.of(fileServerPath, "storage", String.valueOf(OPEN_TYPE), userId, uniqueName)
                .toAbsolutePath().normalize().toFile();
    }

    private File buildVirtualDir(String userId, String dirName) {
        return Path.of(fileServerPath, "virtual-directory", String.valueOf(OPEN_TYPE), userId,
                        UUID.randomUUID() + "-" + dirName)
                .toAbsolutePath().normalize().toFile();
    }

    private static boolean isDirectory(FileMapping mapping) {
        return FileType.DIRECTORY_FILE.equals(mapping.getFileType());
    }

    private static String normalizedPath(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : "";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
