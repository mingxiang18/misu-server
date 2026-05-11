package com.misu.fileServer.util;

import com.misu.fileServer.constant.FileType;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

/**
 * 文件类型判断工具
 */
public class FileTypeUtils {

    /** Files.probeContentType 对许多源代码 / 配置类无 MIME 信息，靠扩展名兜底归为 TEXT_FILE */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "log", "csv", "tsv",
            "json", "yml", "yaml", "xml", "toml", "ini", "conf", "properties", "env",
            "html", "htm", "css", "scss", "less",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue",
            "java", "kt", "scala", "groovy",
            "py", "rb", "go", "rs", "php", "swift", "lua", "pl",
            "sh", "bash", "zsh", "ps1", "bat",
            "sql", "gradle", "graphql", "proto"
    );

    /**
     * 获取文件类型
     */
    @SneakyThrows
    public static String getFileType(File file) {
        if (file.isDirectory()) {
            return FileType.DIRECTORY_FILE;
        }
        String mimeType = Files.probeContentType(file.toPath());
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) return FileType.IMAGE_FILE;
            if (mimeType.startsWith("video/")) return FileType.VIDEO_FILE;
            if ("application/pdf".equals(mimeType)) return FileType.DOCUMENT_FILE;
            if (mimeType.startsWith("text/")
                    || "application/json".equals(mimeType)
                    || "application/xml".equals(mimeType)
                    || "application/x-yaml".equals(mimeType)) {
                return FileType.TEXT_FILE;
            }
        }
        // 扩展名兜底（mime probe 在 JDK 自带 mime DB 之外的类型上经常返 null）
        String ext = StringUtils.substringAfterLast(file.getName(), '.').toLowerCase();
        if (StringUtils.isNotBlank(ext) && TEXT_EXTENSIONS.contains(ext)) {
            return FileType.TEXT_FILE;
        }
        return FileType.OTHER_FILE;
    }
}
