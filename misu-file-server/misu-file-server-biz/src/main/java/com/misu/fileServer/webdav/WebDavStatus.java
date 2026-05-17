package com.misu.fileServer.webdav;

/**
 * WebDAV 相关 HTTP 状态码，补充 {@code com.misu.common.constant.HttpStatus} 未覆盖的取值。
 */
public final class WebDavStatus {

    public static final int MULTI_STATUS = 207;
    public static final int PRECONDITION_FAILED = 412;
    public static final int PAYLOAD_TOO_LARGE = 413;
    public static final int LOCKED = 423;
    public static final int INSUFFICIENT_STORAGE = 507;

    private WebDavStatus() {
    }
}
