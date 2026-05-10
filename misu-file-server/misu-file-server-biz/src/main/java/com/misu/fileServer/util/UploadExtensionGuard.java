package com.misu.fileServer.util;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上传扩展名安全校验。命中黑名单 → 直接拒绝。
 * 黑名单可通过配置覆盖：file.upload.blockedExtensions。默认覆盖常见可执行 / 脚本 / 客户端可被
 * 当作 HTML 渲染的格式，防止上传后被作为 XSS / 恶意分发跳板。
 */
@Component
public class UploadExtensionGuard {

    private static final String DEFAULT_BLOCKED = "exe,bat,cmd,com,scr,msi,sh,ps1,vbs,vbe,jse,wsf,wsh,hta,jar,reg";

    private final Set<String> blockedExtensions;

    public UploadExtensionGuard(@Value("${file.upload.blockedExtensions:" + DEFAULT_BLOCKED + "}") String blockedCsv) {
        this.blockedExtensions = parseCsv(blockedCsv);
    }

    private Set<String> parseCsv(String csv) {
        if (StringUtils.isBlank(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 校验文件名（含扩展名）。命中黑名单时抛 ServiceException(BAD_REQUEST)。
     * fileName 必须已通过 FilePathGuard.normalizeFileName 处理。
     */
    public void requireSafeForUpload(String fileName) {
        String ext = extractExtension(fileName);
        if (ext != null && blockedExtensions.contains(ext)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST,
                    "禁止上传 ." + ext + " 类型文件");
        }
    }

    public boolean isBlocked(String fileName) {
        String ext = extractExtension(fileName);
        return ext != null && blockedExtensions.contains(ext);
    }

    private String extractExtension(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        String ext = StringUtils.substringAfterLast(fileName, '.');
        if (StringUtils.isBlank(ext)) {
            return null;
        }
        return ext.toLowerCase(Locale.ROOT);
    }

    Set<String> getBlockedExtensions() {
        return blockedExtensions;
    }
}
