package com.misu.ops.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.console.ConsoleWebSocketBridgeService;
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

    public ProxyAuthController(OpsProperties properties, OpsSessionStore sessions, OpsOriginPolicy originPolicy) {
        this.properties = properties;
        this.sessions = sessions;
        this.originPolicy = originPolicy;
    }

    @GetMapping("/proxy-auth")
    public ResponseEntity<Void> authorizeProxy(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !validSecret(request.getHeader("X-Ops-Proxy-Key"))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "代理授权失败");
        }
        String target = request.getHeader("X-Ops-Target");
        ConsoleTarget consoleTarget = ConsoleTarget.parse(target);
        String sessionId = CookieSupport.read(request, properties.getCookieName());
        OpsSessionStore.ConsoleSession session = sessions.requireConsoleSession(sessionId, consoleTarget.id());

        String method = request.getHeader("X-Original-Method");
        if (method != null && !isSafeMethod(method)) {
            originPolicy.requireSameConsoleOrigin(request, consoleTarget.url(properties));
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        String cookieHeader = CookieSupport.header(request);
        String cookie = CookieSupport.filterUpstreamCookies(cookieHeader,
                blockedCookieNames());
        if (cookie != null) {
            responseHeaders.set(ConsoleWebSocketBridgeService.UPSTREAM_COOKIE_HEADER, cookie);
        }
        String authorization = CookieSupport.filterUpstreamAuthorization(
                request.getHeader(HttpHeaders.AUTHORIZATION), cookieHeader,
                blockedCookieNames());
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

    private java.util.Set<String> blockedCookieNames() {
        java.util.Set<String> names = new java.util.HashSet<>(properties.getMainCookieNames());
        names.add(properties.getCookieName());
        return names;
    }
}
