package com.misu.fileServer.service;

import java.io.File;

/**
 * 文件预览相关Service
 *
 * @author misu
 */
public interface PreviewService {

    /**
     * 将图片文件添加到处理队列
     */
    void addImgFileToQueue(File imgFile);

    /**
     * 删除当前文件的预览文件
     */
    void deletePreviewFile(File deleteFile);
}
