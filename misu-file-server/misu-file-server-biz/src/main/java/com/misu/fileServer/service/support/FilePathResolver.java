package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件服务路径解析组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的「路径解析」职责：基于 {@code openType / userId / 相对路径}
 * 计算用户根目录、物理存储位置、预览文件位置，以及把虚拟路径 / FileMapping 还原为物理 {@link File}。
 * 方法签名 / 逻辑与原 god class 完全一致，仅可见性放宽为 public 供 FileServiceImpl 委托。</p>
 *
 * @author misu
 */
@Component
public class FilePathResolver {

    public final static String PUBLIC_DIRECTORY = "public/";
    public final static String PRIVATE_DIRECTORY = "private/";
    public final static String PREVIEW_DIRECTORY = "preview/";
    public final static String TMP_DIRECTORY = "tmp/";
    public final static String STORAGE_DIRECTORY = "storage/";
    public final static String VIRTUAL_DIRECTORY_STORAGE = "virtual-directory/";

    @Value("${file-server.path}")
    private String fileServerPath;

    @Resource
    private FileMappingRepository fileMappingRepository;

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

    public Path resolveUserRequestFile(FileRequestDto fileRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        return resolveUserRequestFile(fileRequestDto.getOpenType(), loginUser.getUserId().toString(), fileRequestDto.getFilePath());
    }

    public Path resolveUserRequestFile(Integer openType, String userId, String filePath) {
        String mappingUserId = getMappingUserId(openType, userId);
        String relativePath = FilePathGuard.normalizeRelativePath(filePath);
        Optional<FileMapping> mapping = fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, relativePath);
        if (mapping.isPresent()) {
            return resolveMappedFile(mapping.get()).toPath();
        }
        throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
    }

    public File getPreviewFile(File originFile) {
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

    public String getUserRootDirectory(Integer openType, String userId) {
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

    public String getMappingUserId(Integer openType, String loginUserId) {
        if (openType != null && openType == 1) {
            return "public";
        }
        return loginUserId;
    }

    public String getParentPath(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index > 0 ? relativePath.substring(0, index) : "";
    }

    public File buildUploadStorageFile(Integer openType, String userId, String fileName) {
        String extension = StringUtils.substringAfterLast(fileName, ".");
        String uniqueName = UUID.randomUUID() + (StringUtils.isBlank(extension) ? "" : "." + extension);
        Path path = Path.of(fileServerPath, STORAGE_DIRECTORY, String.valueOf(openType), userId, uniqueName)
                .toAbsolutePath()
                .normalize();
        return path.toFile();
    }

    public File buildVirtualDirectoryStorage(Integer openType, String userId, String directoryName) {
        Path path = Path.of(fileServerPath, VIRTUAL_DIRECTORY_STORAGE, String.valueOf(openType), userId,
                        UUID.randomUUID() + "-" + directoryName)
                .toAbsolutePath()
                .normalize();
        return path.toFile();
    }

    public File resolveMappedFile(FileMapping mapping) {
        return Path.of(mapping.getTargetPath()).toAbsolutePath().normalize().toFile();
    }
}
