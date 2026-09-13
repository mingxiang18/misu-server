package com.misu.ops.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.console.ConsoleWebSocketBridgeService;
import com.misu.ops.console.NacosUpstreamAuthService;
import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.annotation.Anonymous;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Anonymous
@RestController
@RequestMapping("/internal")
public class ProxyAuthController {

    private final OpsProperties properties;
    private final OpsSessionStore sessions;
    private final OpsOriginPolicy originPolicy;
    private final NacosUpstreamAuthService nacosAuth;

    public ProxyAuthController(OpsProperties properties, OpsSessionStore sessions, OpsOriginPolicy originPolicy) {
        this(properties, sessions, originPolicy, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProxyAuthController(OpsProperties properties, OpsSessionStore sessions,
                               OpsOriginPolicy originPolicy, NacosUpstreamAuthService nacosAuth) {
        this.properties = properties;
        this.sessions = sessions;
        this.originPolicy = originPolicy;
        this.nacosAuth = nacosAuth;
    }

    @GetMapping("/proxy-auth")
    public ResponseEntity<Void> authorizeProxy(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !validSecret(request.getHeader("X-Ops-Proxy-Key"))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "代理授权失败");
        }
        String target = request.getHeader("X-Ops-Target");
        if (!StringUtils.hasText(target)) {
            target = targetFromOriginalUri(request.getHeader("X-Original-URI"));
        }
        ConsoleTarget consoleTarget = ConsoleTarget.parse(target);
        String sessionId = CookieSupport.read(request, properties.getCookieName());
        OpsSessionStore.ConsoleSession session = sessions.requireConsoleSession(sessionId, consoleTarget.id());

        String method = request.getHeader("X-Original-Method");
        if (method != null && !isSafeMethod(method)) {
            originPolicy.requireSameConsoleOrigin(request, consoleTarget.url(properties));
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        if (consoleTarget == ConsoleTarget.HEADLAMP) {
            String userName = session.userName();
            if (!StringUtils.hasText(userName) || containsHeaderControl(userName)) {
                throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维用户身份无效");
            }
            // Headlamp's identity-aware proxy mode consumes this internal
            // result and skips its browser token screen. Nginx overwrites the
            // public X-Forwarded-User header with this value after auth_request.
            responseHeaders.set("X-Ops-User", userName);
        }
        String cookieHeader = CookieSupport.header(request);
        String cookie = consoleTarget == ConsoleTarget.NACOS ? null
                : CookieSupport.filterUpstreamCookies(cookieHeader, blockedCookieNames());
        if (cookie != null) {
            responseHeaders.set(ConsoleWebSocketBridgeService.UPSTREAM_COOKIE_HEADER, cookie);
        }
        // Browser Authorization is never an upstream credential. Nacos may use
        // only its server-side session token; Headlamp uses its in-cluster
        // ServiceAccount and the verified X-Forwarded-User identity.
        String authorization = consoleTarget == ConsoleTarget.NACOS && nacosAuth != null
                ? nacosAuth.authorization(session.id()) : null;
        if (authorization != null) {
            responseHeaders.set(ConsoleWebSocketBridgeService.UPSTREAM_AUTH_HEADER, authorization);
        }
        return ResponseEntity.noContent().headers(responseHeaders).build();
    }

    private boolean validSecret(String supplied) {
        String expected = properties.getProxySharedSecret();
        return StringUtils.hasText(expected) && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private boolean containsHeaderControl(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private String targetFromOriginalUri(String originalUri) {
        if (originalUri != null && originalUri.startsWith("/nacos/")) {
            return ConsoleTarget.NACOS.id();
        }
        if (originalUri != null && originalUri.startsWith("/ops/headlamp/")) {
            return ConsoleTarget.HEADLAMP.id();
        }
        throw new ServiceException(HttpStatus.BAD_REQUEST, "控制台目标缺失");
    }

    private java.util.Set<String> blockedCookieNames() {
        java.util.Set<String> names = new java.util.HashSet<>(properties.getMainCookieNames());
        names.add(properties.getCookieName());
        return names;
    }
}
