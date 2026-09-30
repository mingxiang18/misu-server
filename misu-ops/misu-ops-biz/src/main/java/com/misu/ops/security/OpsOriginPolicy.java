package com.misu.ops.security;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.ConsoleTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class OpsOriginPolicy {

    private final OpsProperties properties;

    public OpsOriginPolicy(OpsProperties properties) {
        this.properties = properties;
    }

    public void requireAllowedMainOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || !properties.getAllowedOrigins().contains(origin)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "请求来源不受信任");
        }
    }

    public void requireSameConsoleOrigin(HttpServletRequest request, String targetUrl) {
        String origin = request.getHeader("X-Original-Origin");
        if (origin == null) {
            origin = request.getHeader("Origin");
        }
        if (origin == null || !sameOrigin(origin, targetUrl)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "代理请求来源不受信任");
        }
    }

    public boolean isAllowedWebSocketOrigin(String origin) {
        return origin != null && properties.getAllowedOrigins().contains(origin);
    }

    public void requireConsoleWebSocketOrigin(String origin, ConsoleTarget target) {
        if (origin == null || !sameOrigin(origin, target.url(properties))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "控制台 WS 来源不受信任");
        }
    }

    public void requireConsoleHost(String host, ConsoleTarget target) {
        if (host == null || !sameHost(host, target.url(properties))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "控制台 Host 不受信任");
        }
    }

    public ConsoleTarget resolveConsoleHost(String host) {
        for (ConsoleTarget target : ConsoleTarget.values()) {
            try {
                if (sameHost(host, target.url(properties))) {
                    return target;
                }
            } catch (ServiceException ignored) {
                // An unconfigured target cannot match a trusted Host.
            }
        }
        throw new ServiceException(HttpStatus.FORBIDDEN, "控制台 Host 不受信任");
    }

    /**
     * Resolve a console exchange target from the trusted sidecar header. A
     * browser supplied target header is never accepted because only loopback
     * requests carrying the shared secret may select a target this way.
     */
    public ConsoleTarget resolveConsoleTarget(HttpServletRequest request) {
        String targetHeader = request.getHeader("X-Ops-Target");
        if (targetHeader != null) {
            if (!isLoopback(request.getRemoteAddr()) || !validProxySecret(request.getHeader("X-Ops-Proxy-Key"))) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "代理目标不受信任");
            }
            ConsoleTarget target = ConsoleTarget.parse(targetHeader);
            requireConsoleHost(request.getHeader("Host"), target);
            return target;
        }
        return resolveConsoleHost(request.getHeader("Host"));
    }

    /**
     * Console exchange is a sidecar capability endpoint. The browser Origin
     * is intentionally not part of this check because a form redirect may
     * omit or normalize it; the one-time target-bound ticket and trusted
     * loopback sidecar headers provide the authorization boundary.
     */
    public ConsoleTarget requireTrustedConsoleTarget(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !validProxySecret(request.getHeader("X-Ops-Proxy-Key"))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "代理目标不受信任");
        }
        String targetHeader = request.getHeader("X-Ops-Target");
        if (targetHeader == null || targetHeader.isBlank()) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "代理目标缺失");
        }
        ConsoleTarget target = ConsoleTarget.parse(targetHeader);
        requireConsoleHost(request.getHeader("Host"), target);
        return target;
    }

    private boolean validProxySecret(String supplied) {
        String expected = properties.getProxySharedSecret();
        return expected != null && !expected.isBlank() && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private boolean sameOrigin(String left, String right) {
        try {
            URI a = URI.create(left);
            URI b = URI.create(right);
            int aPort = a.getPort() < 0 ? defaultPort(a.getScheme()) : a.getPort();
            int bPort = b.getPort() < 0 ? defaultPort(b.getScheme()) : b.getPort();
            return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                    && aPort == bPort;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean sameHost(String hostHeader, String targetUrl) {
        try {
            URI host = URI.create("http://" + hostHeader);
            URI target = URI.create(targetUrl);
            int hostPort = host.getPort() < 0 ? defaultPort(target.getScheme()) : host.getPort();
            int targetPort = target.getPort() < 0 ? defaultPort(target.getScheme()) : target.getPort();
            return host.getHost() != null && target.getHost() != null
                    && host.getHost().equalsIgnoreCase(target.getHost())
                    && hostPort == targetPort;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
