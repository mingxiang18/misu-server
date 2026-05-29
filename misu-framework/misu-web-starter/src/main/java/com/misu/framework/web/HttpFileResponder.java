package com.misu.framework.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 通用 HTTP 文件写响应组件。
 *
 * <p>从 file-server {@code FileServiceImpl} 的 {@code writeFileToResponse}/{@code generateETag}/
 * {@code isNotModified} 私有逻辑泛化而来，行为严格等价，仅把硬编码的 attachment 行为与文件名取法改为入参。
 * 支持 Range/Accept-Ranges/ETag/If-None-Match/If-Modified-Since(304)/Content-Disposition。</p>
 *
 * @author misu
 */
@Slf4j
@Component
public class HttpFileResponder {

    /**
     * 流式写出本地文件：Range/Accept-Ranges/ETag/If-None-Match/If-Modified-Since(304)/Content-Disposition。
     *
     * @param req          请求
     * @param resp         响应
     * @param file         本地文件
     * @param downloadName 响应文件名（UTF-8 编码进 Content-Disposition），为空时回退 file.getName()
     * @param mimeType     MIME 类型，为空时回退 application/octet-stream
     * @param attachment   true → Content-Disposition: attachment；false → inline
     */
    @SneakyThrows
    public void write(HttpServletRequest req, HttpServletResponse resp, File file,
                      String downloadName, String mimeType, boolean attachment) {
        if (!file.exists()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // 生成文件唯一标识
        String eTag = generateETag(file);

        String contentType = StringUtils.isBlank(mimeType) ? "application/octet-stream" : mimeType;
        String fileName = StringUtils.isBlank(downloadName) ? file.getName() : downloadName;

        // 设置部分响应信息
        resp.setContentType(MediaType.parseMediaType(contentType).toString());
        resp.setCharacterEncoding("utf-8");
        String disposition = attachment ? "attachment" : "inline";
        resp.setHeader("Content-disposition", disposition + ";filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        // 缓存24小时
        resp.setHeader("Cache-Control", "private, max-age=86400");
        // 文件资源唯一标识
        resp.setHeader("ETag", eTag);
        resp.setDateHeader("Last-Modified", file.lastModified());

        // 获取Range字段
        String rangeString = req.getHeader("Range");
        if (StringUtils.isBlank(rangeString) && isNotModified(req, file, eTag)) {
            resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        if (StringUtils.isNotBlank(rangeString)) {
            // 如果范围存在，设定文件读取开始位置后输出
            try (RandomAccessFile targetFile = new RandomAccessFile(file, "r")) {
                // Parse the Range header
                HttpRange range = HttpRange.parseRanges(rangeString).get(0);
                long rangeStart = range.getRangeStart(targetFile.length());
                long rangeEnd = Math.min(range.getRangeEnd(targetFile.length()), targetFile.length() - 1);

                // 设置此次相应返回的数据长度
                resp.setHeader("Content-Length", String.valueOf(rangeEnd - rangeStart + 1));
                resp.setHeader("Accept-Ranges", "bytes");
                // 设置此次相应返回的数据范围
                resp.setHeader("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + targetFile.length());
                // 返回码需要为206，而不是200
                resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                // 设定文件读取开始位置（以字节为单位）
                targetFile.seek(rangeStart);
                byte[] b = new byte[8192];
                int length;
                long bytesRead = 0;

                try {
                    while ((length = targetFile.read(b)) > 0) {
                        // 调整最后一个块的大小
                        if (bytesRead + length > (rangeEnd - rangeStart + 1)) {
                            length = (int) (rangeEnd - rangeStart + 1 - bytesRead);
                        }
                        resp.getOutputStream().write(b, 0, length);
                        bytesRead += length;

                        // 如果已读取完range长度的内容，则停止
                        if (bytesRead >= (rangeEnd - rangeStart + 1)) {
                            break;
                        }
                    }
                    resp.getOutputStream().flush();
                } catch (IOException e) {
                    // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                    // 比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                }
            } catch (Exception e) {
                log.error("读取文件失败", e);
            }
        } else {
            // 设置此次相应返回的数据长度
            resp.setHeader("Content-Length", String.valueOf(file.length()));
            resp.setHeader("Accept-Ranges", "bytes");

            try (BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(file))) {
                byte[] b = new byte[8192];
                int length;
                try {
                    while ((length = fileInputStream.read(b)) > 0) {
                        resp.getOutputStream().write(b, 0, length);
                    }
                    resp.getOutputStream().flush();
                } catch (IOException e) {
                    // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                    // 比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                }
            } catch (Exception e) {
                log.error("读取文件失败", e);
            }
        }
    }

    /**
     * 已在内存的小字节（无 Range），单次写出，带 Content-Disposition。
     *
     * @param resp         响应
     * @param bytes        待写出字节
     * @param downloadName 响应文件名（UTF-8 编码进 Content-Disposition）
     * @param mimeType     MIME 类型，为空时回退 application/octet-stream
     * @param attachment   true → attachment；false → inline
     */
    @SneakyThrows
    public void writeBytes(HttpServletResponse resp, byte[] bytes,
                           String downloadName, String mimeType, boolean attachment) {
        String contentType = StringUtils.isBlank(mimeType) ? "application/octet-stream" : mimeType;
        String fileName = StringUtils.isBlank(downloadName) ? "" : downloadName;

        resp.setContentType(MediaType.parseMediaType(contentType).toString());
        resp.setCharacterEncoding("utf-8");
        String disposition = attachment ? "attachment" : "inline";
        resp.setHeader("Content-disposition", disposition + ";filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        resp.setHeader("Content-Length", String.valueOf(bytes.length));

        try {
            resp.getOutputStream().write(bytes);
            resp.getOutputStream().flush();
        } catch (IOException e) {
            // 写操作IO异常几乎总是由于客户端主动关闭连接导致，吃掉异常打日志
            log.debug("写出字节失败（可能客户端已断开）", e);
        }
    }

    /**
     * 生成弱Etag标识
     */
    private String generateETag(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;  // 文件不存在或不是一个文件时返回 null
        }
        long lastModified = file.lastModified();  // 获取最后修改时间
        long fileSize = file.length();            // 获取文件大小

        String fileTag = String.valueOf(lastModified) + String.valueOf(fileSize);
        // 生成 ETag，将最后修改时间和文件大小组合，使用 MD5 编码
        return "W/\"" + DigestUtils.md5Hex(fileTag) + "\"";
    }

    private boolean isNotModified(HttpServletRequest request, File file, String eTag) {
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (StringUtils.isNotBlank(ifNoneMatch) && StringUtils.equals(ifNoneMatch, eTag)) {
            return true;
        }
        long ifModifiedSince;
        try {
            ifModifiedSince = request.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (ifModifiedSince < 0) {
            return false;
        }
        return file.lastModified() / 1000 <= ifModifiedSince / 1000;
    }
}
