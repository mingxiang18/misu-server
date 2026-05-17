package com.misu.fileServer.webdav;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 构建 WebDAV 响应 XML（PROPFIND multistatus / PROPPATCH / LOCK lockdiscovery）。
 * 纯字符串拼接 + 手动 XML 转义，无需外部 XML 库。
 */
public final class WebDavXmlBuilder {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    private static final String SUPPORTED_LOCK =
            "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>"
                    + "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>";

    private WebDavXmlBuilder() {
    }

    /** RFC 1123 GMT 时间格式，用于 getlastmodified / Last-Modified 头。 */
    public static String httpDate(LocalDateTime time) {
        LocalDateTime value = time != null ? time : LocalDateTime.now();
        return value.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(HTTP_DATE);
    }

    /** ISO-8601 UTC 时间格式，用于 creationdate。 */
    public static String isoDate(LocalDateTime time) {
        LocalDateTime value = time != null ? time : LocalDateTime.now();
        return value.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(ISO_DATE);
    }

    /**
     * 构建 PROPFIND 的 207 Multi-Status 响应。
     *
     * @param resources   资源列表（第一个通常是目标自身）
     * @param hrefPrefix  href 前缀，形如 /fileServer/dav
     * @param lockManager 锁表，用于填充 lockdiscovery
     */
    public static String multiStatus(List<WebDavResource> resources, String hrefPrefix,
                                     WebDavLockManager lockManager) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">");
        for (WebDavResource resource : resources) {
            appendResponse(sb, resource, hrefPrefix, lockManager);
        }
        sb.append("</D:multistatus>");
        return sb.toString();
    }

    private static void appendResponse(StringBuilder sb, WebDavResource resource, String hrefPrefix,
                                        WebDavLockManager lockManager) {
        sb.append("<D:response>");
        sb.append("<D:href>").append(encodeHref(hrefPrefix, resource.getVirtualPath(), resource.isDirectory()))
                .append("</D:href>");
        sb.append("<D:propstat><D:prop>");
        sb.append("<D:displayname>").append(xmlEscape(displayName(resource))).append("</D:displayname>");
        if (resource.isDirectory()) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            sb.append("<D:resourcetype/>");
            sb.append("<D:getcontentlength>").append(resource.getContentLength()).append("</D:getcontentlength>");
            sb.append("<D:getcontenttype>").append(xmlEscape(
                    resource.getContentType() != null ? resource.getContentType() : "application/octet-stream"))
                    .append("</D:getcontenttype>");
            if (resource.getEtag() != null) {
                sb.append("<D:getetag>").append(xmlEscape(resource.getEtag())).append("</D:getetag>");
            }
        }
        sb.append("<D:getlastmodified>").append(httpDate(resource.getLastModified())).append("</D:getlastmodified>");
        sb.append("<D:creationdate>").append(isoDate(resource.getCreateTime())).append("</D:creationdate>");
        sb.append(SUPPORTED_LOCK);
        appendLockDiscovery(sb, resource.getVirtualPath(), hrefPrefix, lockManager);
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
        sb.append("</D:response>");
    }

    private static void appendLockDiscovery(StringBuilder sb, String virtualPath, String hrefPrefix,
                                            WebDavLockManager lockManager) {
        WebDavLockManager.LockEntry entry = lockManager.find(virtualPath).orElse(null);
        if (entry == null) {
            sb.append("<D:lockdiscovery/>");
            return;
        }
        sb.append("<D:lockdiscovery>");
        appendActiveLock(sb, entry, hrefPrefix);
        sb.append("</D:lockdiscovery>");
    }

    private static void appendActiveLock(StringBuilder sb, WebDavLockManager.LockEntry entry, String hrefPrefix) {
        sb.append("<D:activelock>");
        sb.append("<D:lockscope><D:exclusive/></D:lockscope>");
        sb.append("<D:locktype><D:write/></D:locktype>");
        sb.append("<D:depth>0</D:depth>");
        if (entry.owner() != null) {
            sb.append("<D:owner>").append(xmlEscape(entry.owner())).append("</D:owner>");
        }
        sb.append("<D:timeout>Second-").append(entry.timeoutSeconds()).append("</D:timeout>");
        sb.append("<D:locktoken><D:href>").append(xmlEscape(entry.token())).append("</D:href></D:locktoken>");
        sb.append("<D:lockroot><D:href>")
                .append(encodeHref(hrefPrefix, entry.path(), false))
                .append("</D:href></D:lockroot>");
        sb.append("</D:activelock>");
    }

    /** LOCK 响应体：prop/lockdiscovery。 */
    public static String lockDiscoveryProp(WebDavLockManager.LockEntry entry, String hrefPrefix) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>");
        appendActiveLock(sb, entry, hrefPrefix);
        sb.append("</D:lockdiscovery></D:prop>");
        return sb.toString();
    }

    /** PROPPATCH 响应：统一返回 200 OK，不持久化任何属性。 */
    public static String propPatchResponse(String href) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<D:multistatus xmlns:D=\"DAV:\"><D:response>"
                + "<D:href>" + xmlEscape(href) + "</D:href>"
                + "<D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                + "</D:response></D:multistatus>";
    }

    private static String displayName(WebDavResource resource) {
        if (resource.getDisplayName() != null && !resource.getDisplayName().isEmpty()) {
            return resource.getDisplayName();
        }
        return resource.getVirtualPath() == null || resource.getVirtualPath().isEmpty() ? "/" : resource.getVirtualPath();
    }

    /**
     * 构建 href：前缀 + 逐段 URL 编码的虚拟路径；目录追加尾斜杠。
     */
    public static String encodeHref(String hrefPrefix, String virtualPath, boolean directory) {
        StringBuilder sb = new StringBuilder();
        sb.append(hrefPrefix);
        if (virtualPath != null && !virtualPath.isEmpty()) {
            for (String segment : virtualPath.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                sb.append('/').append(encodeSegment(segment));
            }
        }
        if (directory) {
            sb.append('/');
        }
        return xmlEscape(sb.toString());
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
