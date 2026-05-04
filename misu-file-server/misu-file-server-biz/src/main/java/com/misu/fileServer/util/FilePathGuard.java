package com.misu.fileServer.util;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件服务路径安全工具。
 */
public final class FilePathGuard {

    private FilePathGuard() {
    }

    public static Path resolveInsideRoot(String rootDirectory, String requestPath) {
        return resolveInsideRoot(rootDirectory, requestPath, false);
    }

    public static Path resolveInsideRoot(String rootDirectory, String requestPath, boolean allowRoot) {
        Path rootPath = Paths.get(rootDirectory).toAbsolutePath().normalize();
        String relativePath = normalizeRelativePath(requestPath, allowRoot);
        Path targetPath = rootPath.resolve(relativePath).normalize();
        if (!targetPath.startsWith(rootPath)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
        }
        return targetPath;
    }

    public static String normalizeRelativePath(String requestPath) {
        return normalizeRelativePath(requestPath, false);
    }

    public static String normalizeRelativePath(String requestPath, boolean allowRoot) {
        if (requestPath == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件路径不能为空");
        }

        String path = requestPath.replace('\\', '/').trim();
        if (path.indexOf('\0') >= 0) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件路径不合法");
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (StringUtils.isBlank(path)) {
            if (allowRoot) {
                return "";
            }
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件路径不能为空");
        }

        try {
            Path normalizedPath = Paths.get(path).normalize();
            if (normalizedPath.isAbsolute()) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
            }
            String normalized = normalizedPath.toString().replace('\\', '/');
            if (normalized.equals(".") || normalized.startsWith("../") || normalized.equals("..")
                    || normalized.contains("/../")) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "文件路径不合法");
            }
            return normalized;
        } catch (InvalidPathException e) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件路径不合法");
        }
    }

    public static String normalizeFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }
        String normalizedFileName = fileName.trim();
        if (normalizedFileName.indexOf('\0') >= 0
                || normalizedFileName.contains("/")
                || normalizedFileName.contains("\\")
                || ".".equals(normalizedFileName)
                || "..".equals(normalizedFileName)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }
        return normalizedFileName;
    }
}
