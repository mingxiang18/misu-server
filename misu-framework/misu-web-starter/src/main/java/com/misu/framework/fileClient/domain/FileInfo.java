package com.misu.framework.fileClient.domain;

import lombok.Data;

import java.io.InputStream;

/**
 * 文件信息
 */
@Data
public class FileInfo {
    private String filePath;

    private String fileName;

    private InputStream inputStream;
}
