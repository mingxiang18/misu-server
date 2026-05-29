package com.misu.framework.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 JUnit5 单测（不起 Spring 上下文），覆盖 {@link HttpFileResponder} 的全部边界。
 */
class HttpFileResponderTest {

    private final HttpFileResponder responder = new HttpFileResponder();

    @TempDir
    Path tempDir;

    private File file;
    private byte[] content;

    @BeforeEach
    void setUp() throws Exception {
        // 1000 字节的可辨识内容
        content = new byte[1000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        file = tempDir.resolve("sample.bin").toFile();
        Files.write(file.toPath(), content);
    }

    /** 1. 完整下载（无 Range）。 */
    @Test
    void write_fullDownload_returns200WithFullBody() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", true);

        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
        assertEquals(String.valueOf(content.length), resp.getHeader("Content-Length"));
        assertEquals("bytes", resp.getHeader("Accept-Ranges"));
        assertArrayEquals(content, resp.getContentAsByteArray());
        assertTrue(resp.getHeader("Content-disposition").contains("sample.bin"));
    }

    /** 2. 单段 Range bytes=0-99。 */
    @Test
    void write_rangeFromStart_returns206FirstHundredBytes() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Range", "bytes=0-99");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, resp.getStatus());
        assertEquals("bytes 0-99/1000", resp.getHeader("Content-Range"));
        assertEquals("100", resp.getHeader("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(content, 0, 100), resp.getContentAsByteArray());
    }

    /** 3. Range 起点非 0：bytes=100-（到文件尾）。 */
    @Test
    void write_rangeOpenEnded_returns206ToEnd() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Range", "bytes=100-");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, resp.getStatus());
        assertEquals("bytes 100-999/1000", resp.getHeader("Content-Range"));
        assertEquals("900", resp.getHeader("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(content, 100, 1000), resp.getContentAsByteArray());
    }

    /**
     * 4a. 越界 Range bytes=999999-（起点 > 文件长度）。
     * <p>搬来的逻辑无 416 分支：Spring HttpRange 对 "bytes=999999-" 解析出 rangeStart=999999，
     * rangeEnd=min(length-1, ...)=999；rangeStart>rangeEnd，状态置为 206、Content-Length 为负，
     * 写循环不写出任何字节（read 返回的 length 调整为负后 write(b,0,负) 不抛但 body 为空）。
     * 即：回退行为是 206 + 空 body，而非 416。此处按现有逻辑实际行为断言。</p>
     */
    @Test
    void write_rangeBeyondEof_returns206PerExistingLogic() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Range", "bytes=999999-");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        // 现有逻辑：进入 Range 分支并 setStatus(206)，不走 416
        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, resp.getStatus());
        assertEquals("bytes 999999-999/1000", resp.getHeader("Content-Range"));
    }

    /**
     * 4b. 非法 Range bytes=abc。
     * <p>HttpRange.parseRanges("bytes=abc") 抛 IllegalArgumentException，被外层 catch 吞掉并打日志；
     * setStatus(206) 在解析之后，所以异常时状态停留在默认 200。即：非法 Range 回退 200、无 body。</p>
     */
    @Test
    void write_rangeMalformed_fallsBackTo200PerExistingLogic() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Range", "bytes=abc");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        // 解析异常被吞，状态停留默认 200（既未写 206 也未写 body）
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
        assertEquals(0, resp.getContentAsByteArray().length);
    }

    /** 5. ETag 命中 → 304、无 body。 */
    @Test
    void write_ifNoneMatchHit_returns304NoBody() {
        // 先取一次响应的 ETag
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletResponse firstResp = new MockHttpServletResponse();
        responder.write(first, firstResp, file, "sample.bin", "application/octet-stream", false);
        String eTag = firstResp.getHeader("ETag");
        assertNotNull(eTag);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("If-None-Match", eTag);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        assertEquals(HttpServletResponse.SC_NOT_MODIFIED, resp.getStatus());
        assertEquals(0, resp.getContentAsByteArray().length);
    }

    /** 6. If-Modified-Since ≥ 文件 lastModified → 304。 */
    @Test
    void write_ifModifiedSinceNotModified_returns304() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // 用文件 lastModified 之后的时间
        req.addHeader("If-Modified-Since", file.lastModified() + 5000L);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        assertEquals(HttpServletResponse.SC_NOT_MODIFIED, resp.getStatus());
        assertEquals(0, resp.getContentAsByteArray().length);
    }

    /** 7a. attachment=true → header 含 attachment。 */
    @Test
    void write_attachmentTrue_dispositionAttachment() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", true);

        assertTrue(resp.getHeader("Content-disposition").startsWith("attachment"));
    }

    /** 7b. attachment=false → header 含 inline。 */
    @Test
    void write_attachmentFalse_dispositionInline() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "application/octet-stream", false);

        assertTrue(resp.getHeader("Content-disposition").startsWith("inline"));
    }

    /** 8. 文件不存在 → 404。 */
    @Test
    void write_fileNotExists_returns404() {
        File missing = tempDir.resolve("nope.bin").toFile();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, missing, "nope.bin", "application/octet-stream", true);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getStatus());
    }

    /** 9. writeBytes：200、Content-Length、body、Content-Disposition、mimeType 回退。 */
    @Test
    void writeBytes_writesBodyWithHeadersAndMimeFallback() {
        byte[] payload = "hello-world".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // mimeType 传 null → 回退 application/octet-stream
        responder.writeBytes(resp, payload, "greeting.txt", null, true);

        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
        assertEquals(String.valueOf(payload.length), resp.getHeader("Content-Length"));
        assertArrayEquals(payload, resp.getContentAsByteArray());
        String disposition = resp.getHeader("Content-disposition");
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(disposition.contains("greeting.txt"));
        assertTrue(resp.getContentType().contains("application/octet-stream"));
    }

    /** 额外：write 的 mimeType 为空回退 application/octet-stream。 */
    @Test
    void write_blankMime_fallsBackToOctetStream() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        responder.write(req, resp, file, "sample.bin", "  ", false);

        assertTrue(resp.getContentType().contains("application/octet-stream"));
    }
}
