package com.misu.fileServer.service;

import java.io.File;

/**
 * 文件预览相关Service
 *
 * @author misu
 */
public interface PreviewService {

    /**
     * 异步生成缩略图
     */
    void generatePreviewFile(File imgFile);

    /**
     * 删除当前文件的预览文件
     */
    void deletePreviewFile(File deleteFile);
}
