package com.misu.fileServer.webdav;

import com.misu.account.dto.VerifyCredentialsRequestDto;
import com.misu.account.dto.VerifyCredentialsResponseDto;
import com.misu.account.feign.AccountFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析 HTTP Basic Auth 凭证并向 misu-account 校验。
 *
 * <p>命中本地短期缓存可免去每请求的 Feign + BCrypt 开销（WebDAV 客户端单次目录操作会发数十请求）。
 * 缓存键为凭证哈希，改密后自然失效；仅缓存成功结果，失败立即生效不锁死用户。</p>
 */
@Slf4j
@Component
public class WebDavAuthenticator {

    private record CacheEntry(WebDavPrincipal principal, long expiresAt) {
    }

    private final AccountFeignClient accountFeignClient;

    private final long cacheTtlMillis;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WebDavAuthenticator(AccountFeignClient accountFeignClient,
                               @Value("${webdav.auth.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.accountFeignClient = accountFeignClient;
        this.cacheTtlMillis = (cacheTtlSeconds > 0 ? cacheTtlSeconds : 300) * 1000L;
    }

    /**
     * 校验请求的 Basic Auth 凭证。
     *
     * @return 校验通过的用户身份；缺失 / 非法 / 校验失败时返回 null。
     */
    public WebDavPrincipal authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String userName = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        if (userName.isEmpty()) {
            return null;
        }

        String cacheKey = sha256(userName + '\0' + password);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.principal();
        }

        VerifyCredentialsRequestDto requestDto = new VerifyCredentialsRequestDto();
        requestDto.setUserName(userName);
        requestDto.setPassword(password);
        VerifyCredentialsResponseDto response;
        try {
            response = accountFeignClient.verifyCredentials(requestDto);
        } catch (Exception e) {
            log.warn("WebDAV 凭证校验调用 misu-account 失败: {}", e.getMessage());
            return null;
        }
        if (response == null || !response.isSuccess()) {
            return null;
        }
        WebDavPrincipal principal = new WebDavPrincipal(
                response.getUserId(), response.getUserName(), response.getAuthorities());
        cache.put(cacheKey, new CacheEntry(principal, now + cacheTtlMillis));
        return principal;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @Scheduled(fixedDelay = 300_000L)
    void sweepExpired() {
        long now = System.currentTimeMillis();
        cache.values().removeIf(entry -> entry.expiresAt() <= now);
    }
}
