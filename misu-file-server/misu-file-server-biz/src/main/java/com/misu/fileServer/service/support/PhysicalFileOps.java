package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.service.PreviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 物理文件 IO 组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的「物理磁盘读写」职责：递归删除、目录创建、目录下载量统计/限额、
 * 文件删除后置清理。方法签名 / 逻辑与原 god class 完全一致，仅可见性放宽为 public 供 FileServiceImpl
 * 委托，行为保持不变。</p>
 *
 * <p>{@link #fileDeleteAfter(File)} 依赖非 IO 服务 {@link PreviewService}（删物理文件后清理其缩略图），
 * 因此本组件注入 {@code PreviewService}；{@link #deleteFile(File)} 删成功后会回调 fileDeleteAfter，
 * 保持原调用顺序（先 delete 再清理）。</p>
 *
 * <p>目录下载限额阈值经 {@code @Value} 注入：{@code file.download.directory.maxBytes} /
 * {@code file.download.directory.maxFiles}，超限抛 {@link ServiceException}（BAD_REQUEST）。</p>
 *
 * @author misu
 */
@Slf4j
@Component
public class PhysicalFileOps {

    @Value("${file.download.directory.maxBytes:209715200}")
    private long directoryDownloadMaxBytes;

    @Value("${file.download.directory.maxFiles:1000}")
    private long directoryDownloadMaxFiles;

    @Resource
    private PreviewService previewService;

    /**
     * 删除文件或递归删除目录。
     *
     * <p>非符号链接的目录会先递归删子项；自身 delete 成功后回调 {@link #fileDeleteAfter(File)} 清理预览图。</p>
     */
    public Boolean deleteFile(File deleteFile) {
        boolean isSuccess = true;

        //如果不是符号链接，且是文件夹，递归删除内部文件
        if (!Files.isSymbolicLink(deleteFile.toPath()) && deleteFile.isDirectory()) {
            for (File subFile : deleteFile.listFiles()) {
                deleteFile(subFile);
            }
        }

        //删除文件
        isSuccess = isSuccess && deleteFile.delete();
        if (isSuccess) {
            //执行文件删除后置操作
            fileDeleteAfter(deleteFile);
        }

        return isSuccess;
    }

    /**
     * 递归物理删除（不触发 fileDeleteAfter）。不存在的目标视为删除成功（返回 true）。
     */
    public boolean deletePhysicalRecursively(File target) {
        if (!target.exists()) {
            return true;
        }
        boolean success = true;
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    success = deletePhysicalRecursively(child) && success;
                }
            }
        }
        return target.delete() && success;
    }

    /**
     * 确保目录存在（已存在幂等，不存在则创建）。
     */
    public void ensureDirectoryExists(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.ERROR, "无法创建 staging 目录");
        }
    }

    /**
     * 目录下载限额校验：文件数 / 总字节数超阈值抛 {@link ServiceException}（BAD_REQUEST）。
     */
    public void checkDirectoryDownloadLimit(File directory) {
        DirectoryDownloadStat stat = getDirectoryDownloadStat(directory.toPath());
        if (stat.fileCount > directoryDownloadMaxFiles) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录文件数量过多，请选择较小目录或单文件下载");
        }
        if (stat.totalBytes > directoryDownloadMaxBytes) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录体积过大，请选择较小目录或单文件下载");
        }
    }

    /**
     * 统计目录下普通文件的总字节数与文件数。
     */
    public DirectoryDownloadStat getDirectoryDownloadStat(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            DirectoryDownloadStat stat = new DirectoryDownloadStat();
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        stat.fileCount++;
                        try {
                            stat.totalBytes += Files.size(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            return stat;
        } catch (UncheckedIOException | IOException e) {
            log.error("统计目录下载大小失败", e);
            throw new ServiceException(HttpStatus.ERROR, "统计目录下载大小失败");
        }
    }

    /**
     * 文件删除后置操作：清理预览缩略图。
     */
    public void fileDeleteAfter(File deleteFile) {
        //删除预览图片
        previewService.deletePreviewFile(deleteFile);
    }

    /** 目录下载允许的最大字节数（供基于 DB 统计的限额校验复用同一阈值）。 */
    public long getDirectoryDownloadMaxBytes() {
        return directoryDownloadMaxBytes;
    }

    /** 目录下载允许的最大文件数（供基于 DB 统计的限额校验复用同一阈值）。 */
    public long getDirectoryDownloadMaxFiles() {
        return directoryDownloadMaxFiles;
    }

    /**
     * 目录下载量统计结果（文件数 + 总字节数）。
     */
    public static class DirectoryDownloadStat {
        public long fileCount;
        public long totalBytes;
    }
}
