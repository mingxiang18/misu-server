package com.misu.ops.security;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.ConsoleTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.URI;

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
