package com.misu.fileServer.webdav;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 WebDAV 锁表。仅发放独占写锁，满足 Finder「PUT 前先 LOCK」的协议要求；
 * 不在 PUT/DELETE/MOVE 上强制校验锁（best-effort），进程重启即清空。
 */
@Slf4j
@Component
public class WebDavLockManager {

    public record LockEntry(String token, String owner, String path, long timeoutSeconds, long expiresAt) {
    }

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    private final long defaultTimeoutSeconds;

    public WebDavLockManager(@Value("${webdav.lock.timeout-seconds:600}") long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds > 0 ? defaultTimeoutSeconds : 600;
    }

    /** 在指定路径上创建独占写锁。timeoutSeconds <= 0 时使用默认超时。 */
    public LockEntry lock(String path, String owner, long timeoutSeconds) {
        long ttl = timeoutSeconds > 0 ? timeoutSeconds : defaultTimeoutSeconds;
        String token = "opaquelocktoken:" + UUID.randomUUID();
        LockEntry entry = new LockEntry(token, owner, path, ttl, System.currentTimeMillis() + ttl * 1000L);
        locks.put(path, entry);
        return entry;
    }

    /** 查询某路径上未过期的锁。 */
    public Optional<LockEntry> find(String path) {
        LockEntry entry = locks.get(path);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            locks.remove(path, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /** 续租：保持 token 不变，刷新过期时间。 */
    public Optional<LockEntry> refresh(String path) {
        return find(path).map(existing -> {
            LockEntry refreshed = new LockEntry(existing.token(), existing.owner(), existing.path(),
                    existing.timeoutSeconds(),
                    System.currentTimeMillis() + existing.timeoutSeconds() * 1000L);
            locks.put(path, refreshed);
            return refreshed;
        });
    }

    /** 按 token 释放锁。 */
    public boolean unlock(String path, String token) {
        LockEntry entry = locks.get(path);
        if (entry != null && entry.token().equals(token)) {
            locks.remove(path, entry);
            return true;
        }
        return false;
    }

    @Scheduled(fixedDelay = 600_000L)
    void sweepExpired() {
        long now = System.currentTimeMillis();
        locks.values().removeIf(entry -> entry.expiresAt() <= now);
    }
}
