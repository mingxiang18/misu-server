package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.FileUploadRequest;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.util.FileTypeUtils;
import com.misu.framework.upload.ChunkedUploadAssembler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件服务的「分片上传内部机制」组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的分片上传私有逻辑：分片落盘 / 到齐探测 / 合并 +
 * file-server 专属的 MD5 内容校验 + 上传后置回调（预览生成 / 视频转码入队）。</p>
 *
 * <p>底层「分片落盘 + 到齐检查 + per-key 合并锁 + 顺序合并」复用 web-starter 的
 * {@link ChunkedUploadAssembler}，本组件只在其上叠 file-server 专属的 MD5 计算与后置回调。
 * 合并锁（原 {@code uploadMergeLocks}）已由 assembler 的 per-key 锁接管。</p>
 *
 * <p>分片落盘布局：{@code <tmp>/<fileMD5>/part<index>}（assembler-native 子目录），由
 * {@link #chunkDir(String)} 统一解析；与 {@code getUploadStatus} / {@code cleanExpiredTmpFiles}
 * 的扫描布局保持一致，行为与原 god class 等价。</p>
 *
 * @author misu
 */
@Slf4j
@Component
public class ChunkUploadSupport {

    @Value("${file-server.path}")
    private String fileServerPath;

    @Resource
    private ChunkedUploadAssembler chunkedUploadAssembler;

    @Resource
    private PreviewService previewService;

    @Resource
    private VideoTranscodeService videoTranscodeService;

    /**
     * 校验分片参数：总块数 > 0，块索引在 [0, totalChunks) 内。
     * 与原 {@code FileServiceImpl#checkUploadChunk} 完全一致。
     */
    public void checkUploadChunk(FileUploadRequest fileUploadRequest) {
        if (fileUploadRequest.getTotalChunks() <= 0) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件总块数不合法");
        }
        if (fileUploadRequest.getChunkIndex() < 0 || fileUploadRequest.getChunkIndex() >= fileUploadRequest.getTotalChunks()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件块索引不合法");
        }
    }

    /** 某次上传的分片目录：{@code <tmp>/<fileMD5>/}。 */
    public Path chunkDir(String fileMD5) {
        return Path.of(fileServerPath + FilePathResolver.TMP_DIRECTORY + fileMD5);
    }

    /**
     * 落盘第 chunkIndex 个分片到 {@code <tmp>/<fileMD5>/part<chunkIndex>}。
     * 复用 {@link ChunkedUploadAssembler#storeChunk}。
     */
    public void storeChunk(String fileMD5, FileUploadRequest fileUploadRequest) {
        chunkedUploadAssembler.storeChunk(chunkDir(fileMD5), fileUploadRequest.getChunkIndex(), fileUploadRequest.getFile());
    }

    /**
     * 该次上传的全部分片是否已到齐。复用 {@link ChunkedUploadAssembler#allPresent}。
     */
    public boolean allChunksUploaded(String fileMD5, int totalChunks) {
        return chunkedUploadAssembler.allPresent(chunkDir(fileMD5), totalChunks);
    }

    /**
     * 合并所有分片到 {@code file} 并返回内容 MD5。
     *
     * <p>底层合并 + 清理 chunkDir 复用 {@link ChunkedUploadAssembler#mergeIfComplete}（带 per-key
     * 合并锁）；file-server 专属的内容 MD5 校验叠在其上：合并后对落盘的完整文件重算 MD5
     * （{@link MessageDigest} + {@link #bytesToHex}），与原 god class 返回的 contentMd5 等价。</p>
     *
     * @return 合并后文件内容的 MD5 十六进制串
     * @throws IOException 合并 IO 异常 / MD5 算法不可用
     */
    public String mergeChunks(File file, String fileMD5, int totalChunks) throws IOException {
        long merged = chunkedUploadAssembler.mergeIfComplete(fileMD5, chunkDir(fileMD5), totalChunks, file.toPath());
        if (merged < 0) {
            // 调用方应在 allChunksUploaded 为 true 时才调本方法；防御性处理。
            throw new ServiceException(HttpStatus.ERROR, "分片未全部到齐，无法合并");
        }
        return md5Hex(file);
    }

    /** 计算文件内容的 MD5 十六进制串。 */
    private String md5Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return bytesToHex(digest.digest());
    }

    /** 字节数组转十六进制串（小写）。 */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 文件上传后置操作：图片生成缩略图、视频创建转码状态（入队）。
     * 与原 {@code FileServiceImpl#fileAddAfter} 完全一致。
     */
    public void fileAddAfter(File uploadFile) {
        //如果文件是图片，生成缩略图
        if (FileType.IMAGE_FILE.equals(FileTypeUtils.getFileType(uploadFile))) {
            previewService.generatePreviewFile(uploadFile);
        }
        if (FileType.VIDEO_FILE.equals(FileTypeUtils.getFileType(uploadFile))) {
            videoTranscodeService.getOrCreateTranscodeStatus(uploadFile);
        }
    }
}
