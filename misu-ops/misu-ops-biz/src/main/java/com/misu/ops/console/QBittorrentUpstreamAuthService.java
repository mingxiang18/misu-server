package com.misu.ops.console;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs in to the fixed qBittorrent service on behalf of an ops session.
 * qBittorrent's SID and CSRF-related response headers never leave this class
 * except through the trusted sidecar's internal auth_request response.
 */
@Component
public class QBittorrentUpstreamAuthService {

    /** qBittorrent is deliberately pinned to the existing in-cluster Service. */
    public static final String FIXED_UPSTREAM_URL =
            "http://q-bit-torrent-pi.misu-server.svc.cluster.local:30120/";

    private static final Pattern SID_COOKIE = Pattern.compile(
            "((?:QBT_)?SID(?:_\\d+)?=[^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final String CSRF_HEADER = "X-Ops-Upstream-Csrf";

    private final OpsProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private final Map<String, CachedSession> sessions = new ConcurrentHashMap<>();

    public QBittorrentUpstreamAuthService(OpsProperties properties) {
        this(properties, buildRestClient(properties), Clock.systemUTC());
    }

    QBittorrentUpstreamAuthService(OpsProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    private static RestClient buildRestClient(OpsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAccountConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getAccountReadTimeoutMillis()));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    public UpstreamSession session(String opsSessionId) {
        if (!StringUtils.hasText(opsSessionId)) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        if (!StringUtils.hasText(properties.getQbittorrentUsername())
                || !StringUtils.hasText(properties.getQbittorrentPassword())) {
            sessions.clear();
            throw new ServiceException(HttpStatus.ERROR, "qBittorrent 上游认证未配置");
        }
        CachedSession current = sessions.get(opsSessionId);
        if (current != null && current.expiresAt().isAfter(Instant.now(clock))) {
            return new UpstreamSession(current.cookie(), current.csrfToken());
        }
        if (current != null && sessions.remove(opsSessionId, current)) {
            // The old SID is no longer usable locally before best-effort logout.
            // A logout failure must never prevent replacement or restore the SID.
            logout(current);
        }
        CachedSession cached = sessions.computeIfAbsent(opsSessionId, key -> login());
        return new UpstreamSession(cached.cookie(), cached.csrfToken());
    }

    public void removeSession(String opsSessionId) {
        if (opsSessionId != null) {
            CachedSession removed = sessions.remove(opsSessionId);
            if (removed != null) {
                logout(removed);
            }
        }
    }

    public void removeSessionsExcept(Iterable<String> activeSessionIds) {
        java.util.Set<String> active = new java.util.HashSet<>();
        activeSessionIds.forEach(active::add);
        sessions.keySet().stream()
                .filter(id -> !active.contains(id))
                .toList()
                .forEach(this::removeSession);
    }

    int cachedSessionCount() {
        return sessions.size();
    }

    private CachedSession login() {
        URI loginUri = loginUri();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", properties.getQbittorrentUsername());
        form.add("password", properties.getQbittorrentPassword());
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(loginUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, clientResponse) -> ResponseEntity
                            .status(clientResponse.getStatusCode())
                            .headers(clientResponse.getHeaders())
                            .build());
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ServiceException(HttpStatus.ERROR, "qBittorrent 上游认证失败");
            }
            String cookie = sidCookie(response.getHeaders());
            if (cookie == null) {
                throw new ServiceException(HttpStatus.ERROR, "qBittorrent 上游认证响应无效");
            }
            String csrf = firstHeader(response.getHeaders(), "X-CSRF-Token", "X-QBittorrent-Session");
            if (!safeHeader(csrf)) {
                csrf = null;
            }
            // qBittorrent has no standard token TTL. Keep a short server-side
            // lease so stale SIDs are eventually replaced without exposing one.
            long ttl = Math.max(60, Math.min(900, properties.getSessionIdleSeconds()));
            return new CachedSession(cookie, csrf, Instant.now(clock).plusSeconds(ttl));
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceException(HttpStatus.ERROR, "qBittorrent 上游认证失败");
        }
    }

    private void logout(CachedSession session) {
        try {
            restClient.post()
                    .uri(logoutUri())
                    .header(HttpHeaders.COOKIE, session.cookie())
                    .header(HttpHeaders.ORIGIN, origin())
                    .header(HttpHeaders.REFERER, origin() + "/")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // Session removal is fail-closed locally even if the upstream is
            // unavailable. Never log the SID, credentials, or response body.
        }
    }

    private URI loginUri() {
        return URI.create(FIXED_UPSTREAM_URL).resolve("api/v2/auth/login");
    }

    private URI logoutUri() {
        return loginUri().resolve("logout");
    }

    private String origin() {
        URI uri = URI.create(FIXED_UPSTREAM_URL);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private String sidCookie(HttpHeaders headers) {
        for (String value : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            Matcher matcher = SID_COOKIE.matcher(value);
            if (matcher.find() && safeHeader(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String firstHeader(HttpHeaders headers, String... names) {
        for (String name : names) {
            String value = headers.getFirst(name);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean safeHeader(String value) {
        return StringUtils.hasText(value) && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                && value.indexOf(';') < 0;
    }

    public record UpstreamSession(String cookie, String csrfToken) {
    }

    private record CachedSession(String cookie, String csrfToken, Instant expiresAt) {
    }
}
