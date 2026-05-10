package com.misu.fileServer.service;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 上传并发限流（每用户）。防止单个用户并发提交大量上传请求把 IO / 文件句柄 / 线程池吃光。
 *
 * <p>实现：每个用户一把 Semaphore，permits = file.upload.maxConcurrentPerUser；
 * 抢不到在 file.upload.acquireTimeoutMillis 内即抛 429。</p>
 *
 * <p>配置项：</p>
 * <ul>
 *   <li>file.upload.maxConcurrentPerUser，默认 8</li>
 *   <li>file.upload.acquireTimeoutMillis，默认 3000ms</li>
 * </ul>
 */
@Component
public class UploadConcurrencyGuard {

    private final int maxConcurrentPerUser;
    private final long acquireTimeoutMillis;
    private final Map<String, Semaphore> userSemaphores = new ConcurrentHashMap<>();

    public UploadConcurrencyGuard(
            @Value("${file.upload.maxConcurrentPerUser:8}") int maxConcurrentPerUser,
            @Value("${file.upload.acquireTimeoutMillis:3000}") long acquireTimeoutMillis) {
        this.maxConcurrentPerUser = Math.max(1, maxConcurrentPerUser);
        this.acquireTimeoutMillis = Math.max(0, acquireTimeoutMillis);
    }

    /**
     * 申请上传名额；返回的 Releaser 必须在 try-with-resources 内释放。
     */
    public Releaser acquire(String userKey) {
        Semaphore sem = userSemaphores.computeIfAbsent(userKey, k -> new Semaphore(maxConcurrentPerUser, true));
        try {
            if (!sem.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new ServiceException(HttpStatus.ERROR, "当前上传任务过多，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(HttpStatus.ERROR, "上传被中断，请重试");
        }
        return new Releaser(sem);
    }

    public int getMaxConcurrentPerUser() {
        return maxConcurrentPerUser;
    }

    /**
     * try-with-resources 释放器。
     */
    public static final class Releaser implements AutoCloseable {
        private final Semaphore sem;
        private boolean released;

        private Releaser(Semaphore sem) {
            this.sem = sem;
        }

        @Override
        public void close() {
            if (!released) {
                sem.release();
                released = true;
            }
        }
    }
}
