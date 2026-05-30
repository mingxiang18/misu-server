package com.misu.framework.upload;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片上传的最底层机制：part 落盘 / 到齐检查 / 带 per-key 合并锁的顺序合并 + 清理。
 *
 * <p>只做与业务无关的纯机制：不含 MD5 校验、哈希秒传、续传策略——那些留在各业务模块。
 * chunkDir 由调用方传入，目录穿越清洗是调用方责任，本组件不做语义判断。
 */
@Slf4j
@Component
public class ChunkedUploadAssembler {

    /** 分片合并锁：每个 mergeKey 一把，保证「检查到齐 + 合并」原子，支持乱序/并发上传 */
    private final Map<String, Object> mergeLocks = new ConcurrentHashMap<>();

    /** 每个 mergeKey 最近一次被访问的时间戳，用于回收「永不到齐的上传」遗弃下来的锁条目。 */
    private final Map<String, Long> lockAccessTime = new ConcurrentHashMap<>();

    /** 遗弃合并锁的空闲过期阈值（默认 30 分钟）：超过未访问即被 GC，避免锁 map 无界增长。 */
    private static final long DEFAULT_STALE_LOCK_MILLIS = 30 * 60 * 1000L;

    /** 写第 index 个分片到 chunkDir/part{index}（自动建目录）。 */
    public void storeChunk(Path chunkDir, int index, MultipartFile part) {
        try {
            Files.createDirectories(chunkDir);
            part.transferTo(chunkDir.resolve("part" + index).toFile());
        } catch (IOException e) {
            log.error("保存分片失败 chunkDir={} idx={}", chunkDir, index, e);
            throw new ServiceException(HttpStatus.ERROR, "分片保存失败");
        }
    }

    /** chunkDir 下 part0..part{total-1} 是否全部存在。 */
    public boolean allPresent(Path chunkDir, int total) {
        for (int i = 0; i < total; i++) {
            if (!Files.exists(chunkDir.resolve("part" + i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 带 per-key 合并锁：若全片到齐，按 index 顺序合并到 target（自动建父目录），
     * 返回合并后字节数并清理 chunkDir；若未到齐，返回 -1。
     */
    public long mergeIfComplete(String mergeKey, Path chunkDir, int total, Path target) {
        // 机会式回收：每次调用顺手清掉长期未访问的遗弃锁，避免「永不到齐的上传」泄漏锁条目。
        // 活跃上传会频繁调到这里刷新自己的访问时间，不会被误回收。
        lockAccessTime.put(mergeKey, System.currentTimeMillis());
        cleanupStaleLocks(DEFAULT_STALE_LOCK_MILLIS);

        Object lock = mergeLocks.computeIfAbsent(mergeKey, k -> new Object());
        synchronized (lock) {
            // 全片到齐才合并（支持乱序/并发）
            if (!allPresent(chunkDir, total)) {
                return -1L;
            }
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream os = Files.newOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    for (int i = 0; i < total; i++) {
                        try (InputStream is = Files.newInputStream(chunkDir.resolve("part" + i))) {
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                os.write(buf, 0, n);
                            }
                        }
                    }
                }
                return Files.size(target);
            } catch (IOException e) {
                log.error("合并分片失败 mergeKey={}", mergeKey, e);
                throw new ServiceException(HttpStatus.ERROR, "文件合并失败");
            } finally {
                deleteQuietly(chunkDir);
                mergeLocks.remove(mergeKey);
                lockAccessTime.remove(mergeKey);
            }
        }
    }

    /**
     * 回收空闲超过 maxIdleMillis 的遗弃合并锁（含其访问时间记录），返回回收条目数。
     *
     * <p>「永不到齐的上传」（用户传一半就跑路）的 mergeIfComplete 会提前 return -1，不进合并 finally，
     * 锁条目会留存；本方法按空闲时长把这类条目清掉，避免锁 map 无界增长。活跃上传因频繁刷新访问时间不会被回收。
     * mergeIfComplete 每次调用会机会式调用本方法；也可由外部定时任务调用。
     */
    public int cleanupStaleLocks(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Long> e : lockAccessTime.entrySet()) {
            if (now - e.getValue() > maxIdleMillis) {
                lockAccessTime.remove(e.getKey());
                mergeLocks.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }

    private void deleteQuietly(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }
}
