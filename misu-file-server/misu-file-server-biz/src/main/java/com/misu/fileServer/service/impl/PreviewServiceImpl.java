package com.misu.fileServer.service.impl;

import com.misu.fileServer.service.PreviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 文件相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class PreviewServiceImpl implements PreviewService { // 用于去重，避免重复文件

    @Value("${file-server.path}")
    private String fileServerPath;

    // 线程池，用于执行图片缩略图生成任务
    @Resource
    private ThreadPoolTaskExecutor fileExecutor;

    // 使用BlockingQueue作为任务队列
    private final Set<File> pendingFiles = new ConcurrentSkipListSet<>();

    // 图片最大宽度和高度
    private final int maxWidth = 200;
    private final int maxHeight = 200;

    // 异步生成缩略图
    @Override
    public void generatePreviewFile(File file) {
        if (file.exists() && file.isFile()) {
            fileExecutor.execute(() -> {
                // 如果文件不在队列中，则开始生成
                try {
                    if (pendingFiles.add(file)) {
                        generateThumbnail(file);
                    }
                }catch (Exception e) {
                    log.error("生成图片缩略图失败", e);
                }finally {
                    pendingFiles.remove(file);
                }
            });
        }
    }

    // 删除当前文件的预览文件
    @Override
    public void deletePreviewFile(File deleteFile) {
        fileExecutor.execute(() -> {
            File previewFile = getPreviewFile(deleteFile);
            //如果目录不存在则创建
            if (previewFile.exists()) {
                previewFile.delete();
            }
        });
    }

    // 生成缩略图
    private void generateThumbnail(File imgFile) {
        File previewFile = getPreviewFile(imgFile);
        //如果目录不存在则创建
        if (!previewFile.getParentFile().exists()) {
            previewFile.getParentFile().mkdirs();
        }

        try {
            if (imgFile.exists()) {
                Thumbnails.of(imgFile)
                        //裁剪大小
                        .size(maxWidth, maxHeight)
                        //裁剪位置
                        .crop(Positions.CENTER)
                        //将生成的缩略图写入文件
                        .toFile(previewFile);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private File getPreviewFile(File originFile) {
        Path rootPath = Paths.get(fileServerPath).toAbsolutePath().normalize();
        Path originPath = originFile.toPath().toAbsolutePath().normalize();
        Path relativePath;
        try {
            relativePath = rootPath.relativize(originPath);
        } catch (IllegalArgumentException e) {
            String extension = StringUtils.substringAfterLast(originFile.getName(), ".");
            String fileName = DigestUtils.md5Hex(originPath.toString()) + (StringUtils.isBlank(extension) ? "" : "." + extension);
            relativePath = Paths.get("external").resolve(fileName);
        }
        return rootPath.resolve("preview").resolve(relativePath).normalize().toFile();
    }

}
