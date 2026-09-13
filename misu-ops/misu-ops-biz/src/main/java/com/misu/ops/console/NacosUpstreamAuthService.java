package com.misu.ops.console;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obtains a short-lived Nacos token without placing Nacos credentials or the
 * resulting token in the browser. Tokens are scoped to one ops session.
 */
@Component
public class NacosUpstreamAuthService {

    /** Nacos is deliberately pinned to the existing in-cluster Service. */
    public static final String FIXED_UPSTREAM_URL =
            "http://nacos.misu-server.svc.cluster.local:8848/nacos/";

    private final OpsProperties properties;
    private final RestClient restClient;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public NacosUpstreamAuthService(OpsProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAccountConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getAccountReadTimeoutMillis()));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public String authorization(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        boolean hasUsername = StringUtils.hasText(properties.getNacosUsername());
        boolean hasPassword = StringUtils.hasText(properties.getNacosPassword());
        if (!hasUsername && !hasPassword) {
            tokens.clear();
            return null;
        }
        if (hasUsername != hasPassword) {
            tokens.clear();
            throw new ServiceException(HttpStatus.ERROR, "Nacos 上游认证配置不完整");
        }
        loginUri();
        upstreamUri();
        CachedToken token = tokens.compute(sessionId, (key, current) ->
                current != null && current.expiresAt().isAfter(Instant.now())
                        ? current : login());
        return "Bearer " + token.accessToken();
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            tokens.remove(sessionId);
        }
    }

    public void removeSessionsExcept(Iterable<String> activeSessionIds) {
        java.util.Set<String> active = new java.util.HashSet<>();
        activeSessionIds.forEach(active::add);
        tokens.keySet().removeIf(sessionId -> !active.contains(sessionId));
    }

    int cachedSessionCount() {
        return tokens.size();
    }

    private CachedToken login() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", properties.getNacosUsername());
        form.add("password", properties.getNacosPassword());
        try {
            LoginResponse response = restClient.post()
                    .uri(loginUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(LoginResponse.class);
            if (response == null || !StringUtils.hasText(response.accessToken())
                    || response.tokenTtl() == null || response.tokenTtl() <= 0) {
                throw new ServiceException(HttpStatus.ERROR, "Nacos 上游认证响应无效");
            }
            long refreshSkew = Math.min(60, Math.max(1, response.tokenTtl() / 10));
            return new CachedToken(response.accessToken(),
                    Instant.now().plusSeconds(Math.max(1, response.tokenTtl() - refreshSkew)));
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Never include the request body, credentials, or upstream token in
            // the exception that reaches logs or the browser.
            throw new ServiceException(HttpStatus.ERROR, "Nacos 上游认证失败");
        }
    }

    private URI loginUri() {
        return authBaseUri().resolve("v1/auth/users/login");
    }

    private URI upstreamUri() {
        return parseBaseUri(properties.getNacosUpstreamUrl());
    }

    private URI authBaseUri() {
        String configured = StringUtils.hasText(properties.getNacosAuthUrl())
                ? properties.getNacosAuthUrl() : properties.getNacosUpstreamUrl();
        return parseBaseUri(configured);
    }

    URI validatedUpstreamUri() {
        return upstreamUri();
    }

    URI validatedAuthBaseUri() {
        return authBaseUri();
    }

    private URI parseBaseUri(String configured) {
        if (!StringUtils.hasText(configured)) {
            throw new ServiceException(HttpStatus.ERROR, "Nacos 上游地址未配置");
        }
        try {
            URI base = URI.create(configured.endsWith("/") ? configured : configured + "/");
            if (!isFixedNacosBase(base)) {
                throw new IllegalArgumentException("invalid Nacos URL");
            }
            return base;
        } catch (IllegalArgumentException ex) {
            throw new ServiceException(HttpStatus.ERROR, "Nacos 上游地址无效");
        }
    }

    private boolean isFixedNacosBase(URI uri) {
        return "http".equals(uri.getScheme())
                && "nacos.misu-server.svc.cluster.local".equals(uri.getHost())
                && uri.getPort() == 8848
                && "/nacos/".equals(uri.getPath())
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginResponse(String accessToken, Long tokenTtl) {
    }
}
