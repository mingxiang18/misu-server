package com.misu.fileServer.webdav;

import java.util.List;

/**
 * 通过 HTTP Basic Auth 校验通过的 WebDAV 用户身份。
 */
public record WebDavPrincipal(Long userId, String userName, List<String> authorities) {

    public String userIdString() {
        return String.valueOf(userId);
    }
}
