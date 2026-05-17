package com.misu.fileServer.webdav;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.util.FilePathGuard;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自研轻量 WebDAV servlet。映射到 {@code /dav/*}，自行做 HTTP Basic Auth，
 * 把每个用户的私有目录树（openType=0）暴露为可读写的 WebDAV 挂载。
 */
@Slf4j
public class WebDavServlet extends HttpServlet {

    private static final String ALLOW_METHODS =
            "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK";

    private final transient WebDavAuthenticator authenticator;
    private final transient WebDavService webDavService;
    private final transient WebDavLockManager lockManager;

    public WebDavServlet(WebDavAuthenticator authenticator, WebDavService webDavService,
                         WebDavLockManager lockManager) {
        this.authenticator = authenticator;
        this.webDavService = webDavService;
        this.lockManager = lockManager;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setHeader("DAV", "1, 2");
        response.setHeader("MS-Author-Via", "DAV");

        String method = request.getMethod();
        if ("OPTIONS".equals(method)) {
            doOptions(response);
            return;
        }

        try {
            WebDavPrincipal principal = authenticator.authenticate(request);
            if (principal == null) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"MisuCloudDrive\"");
                sendError(response, HttpStatus.UNAUTHORIZED, "WebDAV 鉴权失败");
                return;
            }
            String path = resourcePath(request);
            switch (method) {
                case "PROPFIND" -> doPropfind(request, response, principal, path);
                case "PROPPATCH" -> doProppatch(request, response, path);
                case "GET" -> doGetOrHead(request, response, principal, path, true);
                case "HEAD" -> doGetOrHead(request, response, principal, path, false);
                case "PUT" -> doPut(request, response, principal, path);
                case "MKCOL" -> doMkcol(request, response, principal, path);
                case "DELETE" -> doDelete(response, principal, path);
                case "MOVE" -> doMoveOrCopy(request, response, principal, path, true);
                case "COPY" -> doMoveOrCopy(request, response, principal, path, false);
                case "LOCK" -> doLock(request, response, principal, path);
                case "UNLOCK" -> doUnlock(request, response, path);
                default -> response.sendError(HttpStatus.BAD_METHOD, "不支持的方法: " + method);
            }
        } catch (ServiceException e) {
            int code = e.getCode() != null ? e.getCode() : HttpStatus.ERROR;
            sendError(response, code, e.getMessage());
        } catch (Exception e) {
            log.error("WebDAV 处理异常 method={} uri={}", method, request.getRequestURI(), e);
            sendError(response, HttpStatus.ERROR, "服务器内部错误");
        }
    }

    private void doOptions(HttpServletResponse response) {
        response.setStatus(HttpStatus.SUCCESS);
        response.setHeader("Allow", ALLOW_METHODS);
        response.setContentLength(0);
    }

    private void doPropfind(HttpServletRequest request, HttpServletResponse response,
                            WebDavPrincipal principal, String path) throws IOException {
        WebDavResource target = webDavService.stat(principal.userIdString(), path);
        if (target == null) {
            sendError(response, HttpStatus.NOT_FOUND, "资源不存在");
            return;
        }
        boolean deep = !"0".equals(depthHeader(request));
        List<WebDavResource> resources = new ArrayList<>();
        resources.add(target);
        if (deep && target.isDirectory()) {
            resources.addAll(webDavService.listChildren(principal.userIdString(), path));
        }
        String xml = WebDavXmlBuilder.multiStatus(resources, hrefPrefix(request), lockManager);
        writeXml(response, WebDavStatus.MULTI_STATUS, xml);
    }

    private void doProppatch(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        // 不持久化任何属性，仅返回成功，避免 Finder 因 PROPPATCH 失败而中止拷贝。
        String href = WebDavXmlBuilder.encodeHref(hrefPrefix(request), path, false);
        writeXml(response, WebDavStatus.MULTI_STATUS, WebDavXmlBuilder.propPatchResponse(href));
    }

    private void doGetOrHead(HttpServletRequest request, HttpServletResponse response,
                             WebDavPrincipal principal, String path, boolean writeBody) throws IOException {
        WebDavResource resource = webDavService.stat(principal.userIdString(), path);
        if (resource == null) {
            sendError(response, HttpStatus.NOT_FOUND, "资源不存在");
            return;
        }
        if (resource.isDirectory()) {
            sendError(response, HttpStatus.BAD_METHOD, "目录不支持直接下载");
            return;
        }
        if (!writeBody) {
            response.setStatus(HttpStatus.SUCCESS);
            response.setContentLengthLong(resource.getContentLength());
            if (resource.getContentType() != null) {
                response.setContentType(resource.getContentType());
            }
            if (resource.getEtag() != null) {
                response.setHeader("ETag", resource.getEtag());
            }
            response.setHeader("Last-Modified", WebDavXmlBuilder.httpDate(resource.getLastModified()));
            response.setHeader("Accept-Ranges", "bytes");
            return;
        }
        webDavService.get(principal.userIdString(), path, request, response);
    }

    private void doPut(HttpServletRequest request, HttpServletResponse response,
                       WebDavPrincipal principal, String path) throws IOException {
        if (path.isEmpty()) {
            sendError(response, HttpStatus.FORBIDDEN, "不能写入根目录");
            return;
        }
        boolean created = webDavService.store(principal.userIdString(), path, request.getInputStream());
        response.setStatus(created ? HttpStatus.CREATED : HttpStatus.NO_CONTENT);
    }

    private void doMkcol(HttpServletRequest request, HttpServletResponse response,
                         WebDavPrincipal principal, String path) throws IOException {
        if (request.getContentLengthLong() > 0) {
            sendError(response, HttpStatus.UNSUPPORTED_TYPE, "MKCOL 不接受请求体");
            return;
        }
        if (path.isEmpty()) {
            sendError(response, HttpStatus.BAD_METHOD, "根目录已存在");
            return;
        }
        webDavService.mkcol(principal.userIdString(), path);
        response.setStatus(HttpStatus.CREATED);
    }

    private void doDelete(HttpServletResponse response, WebDavPrincipal principal, String path) throws IOException {
        if (path.isEmpty()) {
            sendError(response, HttpStatus.FORBIDDEN, "不能删除根目录");
            return;
        }
        webDavService.delete(principal.userIdString(), path);
        response.setStatus(HttpStatus.NO_CONTENT);
    }

    private void doMoveOrCopy(HttpServletRequest request, HttpServletResponse response,
                              WebDavPrincipal principal, String path, boolean move) throws IOException {
        if (path.isEmpty()) {
            sendError(response, HttpStatus.FORBIDDEN, "不能移动 / 复制根目录");
            return;
        }
        String destination = destinationPath(request);
        boolean overwrite = !"F".equalsIgnoreCase(request.getHeader("Overwrite"));
        boolean destExisted = webDavService.stat(principal.userIdString(), destination) != null;
        if (move) {
            webDavService.move(principal.userIdString(), path, destination, overwrite);
        } else {
            webDavService.copy(principal.userIdString(), path, destination, overwrite);
        }
        response.setStatus(destExisted ? HttpStatus.NO_CONTENT : HttpStatus.CREATED);
    }

    private void doLock(HttpServletRequest request, HttpServletResponse response,
                        WebDavPrincipal principal, String path) throws IOException {
        long timeout = parseTimeout(request);
        boolean hasBody = request.getContentLengthLong() > 0;
        WebDavLockManager.LockEntry entry;
        if (!hasBody && request.getHeader("If") != null) {
            entry = lockManager.refresh(path).orElse(null);
            if (entry == null) {
                sendError(response, WebDavStatus.PRECONDITION_FAILED, "锁不存在或已过期");
                return;
            }
        } else {
            entry = lockManager.lock(path, principal.userName(), timeout);
        }
        response.setStatus(HttpStatus.SUCCESS);
        response.setHeader("Lock-Token", "<" + entry.token() + ">");
        writeXml(response, HttpStatus.SUCCESS,
                WebDavXmlBuilder.lockDiscoveryProp(entry, hrefPrefix(request)));
    }

    private void doUnlock(HttpServletRequest request, HttpServletResponse response, String path) {
        String token = request.getHeader("Lock-Token");
        if (token != null) {
            token = token.replace("<", "").replace(">", "").trim();
        }
        lockManager.unlock(path, token);
        response.setStatus(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------- helpers

    private String resourcePath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty()) {
            return "";
        }
        return FilePathGuard.normalizeRelativePath(pathInfo, true);
    }

    private String hrefPrefix(HttpServletRequest request) {
        return request.getContextPath() + request.getServletPath();
    }

    private String depthHeader(HttpServletRequest request) {
        String depth = request.getHeader("Depth");
        return depth == null ? "infinity" : depth.trim();
    }

    private String destinationPath(HttpServletRequest request) {
        String destination = request.getHeader("Destination");
        if (destination == null || destination.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "缺少 Destination 头");
        }
        int marker = destination.indexOf("/dav/");
        String raw;
        if (marker >= 0) {
            raw = destination.substring(marker + "/dav/".length());
        } else {
            int bare = destination.indexOf("/dav");
            raw = bare >= 0 ? destination.substring(bare + "/dav".length()) : destination;
        }
        int query = raw.indexOf('?');
        if (query >= 0) {
            raw = raw.substring(0, query);
        }
        // 保护字面 '+'，再按 UTF-8 解码（路径里 '+' 是字面量，空格为 %20）。
        String decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        return FilePathGuard.normalizeRelativePath(decoded, true);
    }

    private long parseTimeout(HttpServletRequest request) {
        String header = request.getHeader("Timeout");
        if (header == null) {
            return 0;
        }
        for (String part : header.split(",")) {
            String token = part.trim();
            if (token.regionMatches(true, 0, "Second-", 0, 7)) {
                try {
                    return Long.parseLong(token.substring(7).trim());
                } catch (NumberFormatException ignored) {
                    // 忽略非法值，回落默认超时
                }
            }
        }
        return 0;
    }

    private void writeXml(HttpServletResponse response, int status, String xml) throws IOException {
        response.setStatus(status);
        response.setContentType("application/xml; charset=utf-8");
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * 直接 setStatus 写错误响应，不用 {@code sendError}：后者会触发容器 ERROR 派发，
     * 重新进入 Spring Security 过滤链并把状态码改写成 403。
     */
    private void sendError(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("text/plain; charset=utf-8");
        byte[] body = (message != null ? message : "").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        try {
            response.getOutputStream().write(body);
        } catch (IOException e) {
            log.warn("WebDAV 写错误响应失败: {}", e.getMessage());
        }
    }
}
