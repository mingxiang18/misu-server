package com.misu.fileServer.service;

import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadConcurrencyGuardTest {

    @Test
    void permits_up_to_max_concurrent_then_throws() {
        UploadConcurrencyGuard guard = new UploadConcurrencyGuard(2, 50);
        UploadConcurrencyGuard.Releaser r1 = guard.acquire("u-1");
        UploadConcurrencyGuard.Releaser r2 = guard.acquire("u-1");
        try {
            assertThrows(ServiceException.class, () -> guard.acquire("u-1"));
        } finally {
            r1.close();
            r2.close();
        }
        // 释放后应能重新获取
        UploadConcurrencyGuard.Releaser r3 = guard.acquire("u-1");
        r3.close();
    }

    @Test
    void per_user_limits_are_independent() {
        UploadConcurrencyGuard guard = new UploadConcurrencyGuard(1, 50);
        try (UploadConcurrencyGuard.Releaser ignored = guard.acquire("alice")) {
            // alice 已用尽 1 个 permit；bob 仍能获取
            UploadConcurrencyGuard.Releaser bob = guard.acquire("bob");
            bob.close();
        }
    }

    @Test
    void close_is_idempotent() {
        UploadConcurrencyGuard guard = new UploadConcurrencyGuard(1, 50);
        UploadConcurrencyGuard.Releaser r = guard.acquire("u-2");
        r.close();
        r.close(); // 重复 close 不应再 release
        // permit 应该还是 1 个，能再次 acquire
        UploadConcurrencyGuard.Releaser again = guard.acquire("u-2");
        // 此时应不能再获取
        assertThrows(ServiceException.class, () -> guard.acquire("u-2"));
        again.close();
    }

    @Test
    void multi_thread_total_acquired_does_not_exceed_max() throws InterruptedException {
        int permits = 4;
        int threads = 16;
        UploadConcurrencyGuard guard = new UploadConcurrencyGuard(permits, 200);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try (UploadConcurrencyGuard.Releaser ignored = guard.acquire("u-x")) {
                        int now = inFlight.incrementAndGet();
                        maxObserved.updateAndGet(prev -> Math.max(prev, now));
                        Thread.sleep(60);
                        inFlight.decrementAndGet();
                    }
                } catch (ServiceException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 在任意时刻，活跃任务不应超过 permits
        // 由于 holding 60ms > 等待 200ms 的两倍，部分线程可能等到 permit；rejected 数取决于调度
        // 这里只断言不超并发上限，rejected + accepted = threads
        org.junit.jupiter.api.Assertions.assertTrue(maxObserved.get() <= permits,
                "in-flight 数不应超过 permits=" + permits + "，实际 maxObserved=" + maxObserved.get());
    }
}
