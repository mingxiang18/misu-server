package com.misu.fileServer.webdav;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * WebDAV 资源视图：servlet 与 service 之间交换的值对象，与 JPA 实体解耦。
 */
@Data
public class WebDavResource {

    /** 规范化后的虚拟路径，无前导斜杠；根目录为 ""。 */
    private String virtualPath;

    /** 展示名（文件名 / 目录名）。 */
    private String displayName;

    /** 是否目录。 */
    private boolean directory;

    /** 文件字节数（目录恒为 0）。 */
    private long contentLength;

    /** MIME 类型（仅文件）。 */
    private String contentType;

    /** 最后修改时间。 */
    private LocalDateTime lastModified;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** ETag（仅文件）。 */
    private String etag;
}
