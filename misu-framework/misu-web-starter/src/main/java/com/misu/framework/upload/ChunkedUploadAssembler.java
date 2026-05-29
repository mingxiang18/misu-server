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
            }
        }
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
