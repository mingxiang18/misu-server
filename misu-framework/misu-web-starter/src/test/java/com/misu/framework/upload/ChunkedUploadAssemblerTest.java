package com.misu.framework.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedUploadAssemblerTest {

    private final ChunkedUploadAssembler assembler = new ChunkedUploadAssembler();

    private static MockMultipartFile part(String content) {
        return new MockMultipartFile("file", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeChunk_writes_part_file_with_correct_content(@TempDir Path tmp) throws Exception {
        Path chunkDir = tmp.resolve("upload-1");
        assembler.storeChunk(chunkDir, 0, part("hello"));

        Path partFile = chunkDir.resolve("part0");
        assertTrue(Files.exists(partFile), "part0 应已落盘");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(partFile));
    }

    @Test
    void storeChunk_creates_dir_for_arbitrary_index(@TempDir Path tmp) throws Exception {
        Path chunkDir = tmp.resolve("upload-idx");
        assembler.storeChunk(chunkDir, 3, part("X"));
        assertTrue(Files.exists(chunkDir.resolve("part3")));
    }

    @Test
    void allPresent_false_when_one_missing(@TempDir Path tmp) {
        Path chunkDir = tmp.resolve("upload-2");
        assembler.storeChunk(chunkDir, 0, part("a"));
        assembler.storeChunk(chunkDir, 2, part("c"));
        // total=3，缺 part1
        assertFalse(assembler.allPresent(chunkDir, 3));
    }

    @Test
    void allPresent_true_when_all_there(@TempDir Path tmp) {
        Path chunkDir = tmp.resolve("upload-3");
        assembler.storeChunk(chunkDir, 0, part("a"));
        assembler.storeChunk(chunkDir, 1, part("b"));
        assembler.storeChunk(chunkDir, 2, part("c"));
        assertTrue(assembler.allPresent(chunkDir, 3));
    }

    @Test
    void mergeIfComplete_returns_minus_one_when_incomplete(@TempDir Path tmp) {
        Path chunkDir = tmp.resolve("upload-4");
        Path target = tmp.resolve("out/merged.bin");
        assembler.storeChunk(chunkDir, 0, part("a"));
        assembler.storeChunk(chunkDir, 1, part("b"));
        // total=3，缺 part2

        long r = assembler.mergeIfComplete("upload-4", chunkDir, 3, target);

        assertEquals(-1L, r);
        assertFalse(Files.exists(target), "未到齐不应生成 target");
        assertTrue(Files.exists(chunkDir.resolve("part0")), "未到齐应保留 chunkDir");
        assertTrue(Files.exists(chunkDir.resolve("part1")));
    }

    @Test
    void mergeIfComplete_merges_in_index_order_regardless_of_write_order(@TempDir Path tmp) throws Exception {
        Path chunkDir = tmp.resolve("upload-5");
        Path target = tmp.resolve("nested/out/merged.bin");

        // 乱序写入：验证拼接顺序由 index 决定、与写入顺序无关
        assembler.storeChunk(chunkDir, 2, part("CCC"));
        assembler.storeChunk(chunkDir, 0, part("A"));
        assembler.storeChunk(chunkDir, 1, part("BB"));

        long r = assembler.mergeIfComplete("upload-5", chunkDir, 3, target);

        String expected = "A" + "BB" + "CCC";
        assertEquals(expected.getBytes(StandardCharsets.UTF_8).length, r);
        assertTrue(Files.exists(target), "自动建父目录并写入 target");
        assertEquals(expected, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(chunkDir), "合并后应清理 chunkDir");
    }

    @Test
    void mergeIfComplete_removes_lock_from_internal_map(@TempDir Path tmp) throws Exception {
        Path chunkDir = tmp.resolve("upload-6");
        Path target = tmp.resolve("out6/merged.bin");
        assembler.storeChunk(chunkDir, 0, part("a"));
        assembler.storeChunk(chunkDir, 1, part("b"));

        assembler.mergeIfComplete("upload-6", chunkDir, 2, target);

        Map<String, Object> locks = internalLocks();
        assertFalse(locks.containsKey("upload-6"), "合并后应从内部 map 移除锁");
        assertEquals(0, locks.size());
    }

    @Test
    void concurrent_last_chunk_triggers_single_merge(@TempDir Path tmp) throws Exception {
        final int total = 8;
        final String mergeKey = "upload-concurrent";
        Path chunkDir = tmp.resolve(mergeKey);
        Path target = tmp.resolve("conc/merged.bin");

        // 多线程同时各写一片并都调用 mergeIfComplete；只有最后到齐的那次真正合并
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger mergeCount = new AtomicInteger(0);
        AtomicReference<Throwable> error = new AtomicReference<>();

        StringBuilder expectedSb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            expectedSb.append(i);
        }
        final String expected = expectedSb.toString();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    assembler.storeChunk(chunkDir, idx, part(String.valueOf(idx)));
                    long r = assembler.mergeIfComplete(mergeKey, chunkDir, total, target);
                    if (r >= 0) {
                        mergeCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在超时内完成");
        pool.shutdownNow();

        assertNull(error.get(), "并发不应抛异常");
        assertEquals(1, mergeCount.get(), "合并只应发生一次");
        assertTrue(Files.exists(target));
        assertEquals(expected, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(chunkDir), "合并后清理 chunkDir");
        assertEquals(0, internalLocks().size(), "并发结束后锁不应泄漏");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> internalLocks() throws Exception {
        Field f = ChunkedUploadAssembler.class.getDeclaredField("mergeLocks");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(assembler);
    }
}
