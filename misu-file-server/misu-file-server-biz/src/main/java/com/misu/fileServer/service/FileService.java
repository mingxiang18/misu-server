package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * 文件相关Service
 *
 * @author misu
 */
public interface FileService {

    /**
     * 获取指定目录下文件列表
     */
    List<FileResponseDto> getFileList(FileRequestDto fileRequestDto);

    /**
     * 获取文件的临时访问链接
     */
    String getFileDownloadLink(FileRequestDto fileRequestDto);

    /**
     * 下载文件
     */
    void downloadFile(FileDownloadRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response);

    /**
     * 上传文件
     */
    FileUploadResponse uploadFile(FileUploadRequest fileUploadRequest);

    /**
     * 创建目录
     */
    Boolean createDirectory(FileRequestDto fileRequestDto);

    /**
     * 移动文件（包含重命名）
     */
    void moveFile(FileRenameRequestDto fileRenameRequestDto);

    /**
     * 删除文件
     */
    Boolean deleteFile(FileRequestDto fileRequestDto);
}
