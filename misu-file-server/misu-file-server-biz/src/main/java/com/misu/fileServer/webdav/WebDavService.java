package com.misu.fileServer.webdav;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.InputStream;
import java.util.List;

/**
 * WebDAV 文件操作门面。独立于 {@code FileService}，仅作用于用户私有目录树（openType=0）。
 * 所有方法显式接收 {@code userId}，不依赖 SecurityContext。
 */
public interface WebDavService {

    /** 查询单个虚拟路径的资源；不存在返回 null。空路径表示用户私有根。 */
    WebDavResource stat(String userId, String virtualPath);

    /** 列出某目录下的直接子项。 */
    List<WebDavResource> listChildren(String userId, String virtualPath);

    /** 流式输出文件内容到响应（含 Range / ETag）。 */
    void get(String userId, String virtualPath, HttpServletRequest request, HttpServletResponse response);

    /**
     * 单流写入文件（WebDAV PUT）。新建返回 true，覆盖返回 false。
     */
    boolean store(String userId, String virtualPath, InputStream in);

    /** 创建目录（WebDAV MKCOL）。 */
    void mkcol(String userId, String virtualPath);

    /** 软删除文件 / 目录子树（WebDAV DELETE），进入回收站。 */
    void delete(String userId, String virtualPath);

    /** 移动 / 重命名子树（WebDAV MOVE）。 */
    void move(String userId, String src, String dest, boolean overwrite);

    /** 复制子树，含物理文件副本（WebDAV COPY）。 */
    void copy(String userId, String src, String dest, boolean overwrite);
}
