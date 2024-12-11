package com.misu.fileServer.service.impl;

import com.misu.fileServer.service.PreviewService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
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
    @Autowired
    private ThreadPoolTaskExecutor fileExecutor;

    // 使用BlockingQueue作为任务队列
    private final BlockingQueue<File> queue = new LinkedBlockingQueue<>();
    private final Set<File> pendingFiles = new ConcurrentSkipListSet<>();

    // 图片最大宽度和高度
    private final int maxWidth = 200;
    private final int maxHeight = 200;

    @PostConstruct
    public void startPreviewQueue() {
        start();
    }

    @PreDestroy
    public void stopPreviewQueue() {
        start();
    }

    // 启动消费者线程来执行图片缩略图生成
    public void start() {
        fileExecutor.submit(this::processQueue);  // 启动队列处理线程
    }

    // 停止队列处理线程
    public void stop() {
        fileExecutor.stop();
    }

    // 生产者方法：将文件添加到队列
    @Override
    public void addImgFileToQueue(File file) {
        if (file.exists() && file.isFile()) {
            // 如果文件不在队列中，则添加
            if (pendingFiles.add(file)) {
                queue.offer(file);
            }
        }
    }

    // 删除当前文件的预览文件
    @Override
    public void deletePreviewFile(File deleteFile) {
        fileExecutor.execute(() -> {
            String previewPath = deleteFile.getAbsolutePath()
                    .replace("\\", "/")
                    .replace(fileServerPath.startsWith("/") ? fileServerPath.substring(1) : fileServerPath, fileServerPath + "preview/");
            File previewFile = new File(previewPath);
            //如果目录不存在则创建
            if (previewFile.exists()) {
                previewFile.delete();
            }
        });
    }

    // 队列消费者：处理图片文件，生成缩略图
    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 阻塞直到队列中有文件
                File file = queue.take();
                // 从队列中移除文件
                pendingFiles.remove(file);
                // 生成缩略图
                generateThumbnail(file);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 生成缩略图
    private void generateThumbnail(File imgFile) {
        String previewPath = imgFile.getAbsolutePath()
                .replace("\\", "/")
                .replace(fileServerPath.startsWith("/") ? fileServerPath.substring(1) : fileServerPath, fileServerPath + "preview/");
        File previewFile = new File(previewPath);
        //如果目录不存在则创建
        if (!previewFile.getParentFile().exists()) {
            previewFile.getParentFile().mkdirs();
        }

        try {
            Thumbnails.of(imgFile)
                    //裁剪大小
                    .size(200, 200)
                    //裁剪位置
                    .crop(Positions.CENTER)
                    //将生成的缩略图写入文件
                    .toFile(previewFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
