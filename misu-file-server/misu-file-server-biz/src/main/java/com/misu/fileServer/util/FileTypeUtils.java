package com.misu.fileServer.util;

import com.misu.fileServer.constant.FileType;
import lombok.SneakyThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 文件类型判断工具
 */
public class FileTypeUtils {

    /**
     * 获取文件类型
     */
    @SneakyThrows
    public static String getFileType(File file) {
        if (file.isDirectory()) {
            return FileType.DIRECTORY_FILE;
        }else {
            String mimeType = Files.probeContentType(file.toPath());
            if (mimeType == null) {
                return FileType.OTHER_FILE;
            }else if (mimeType.startsWith("image/")) {
                return FileType.IMAGE_FILE;
            }else if (mimeType.startsWith("video/")) {
                return FileType.VIDEO_FILE;
            }else {
                return FileType.OTHER_FILE;
            }
        }
    }
}
