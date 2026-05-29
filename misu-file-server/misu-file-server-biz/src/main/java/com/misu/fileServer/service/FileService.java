package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;

import java.io.IOException;
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
     * 上传文件
     */
    FileUploadResponse uploadFile(FileUploadRequest fileUploadRequest);

    /**
     * 添加文件映射
     */
    void addFileInk(AddFileInkRequest addFileInkRequest) throws IOException;

    /**
     * 创建目录
     */
    Boolean createDirectory(FileRequestDto fileRequestDto);

    /**
     * 移动文件（包含重命名）
     */
    void moveFile(FileRenameRequestDto fileRenameRequestDto);

    /**
     * 管理员将当前用户私人目录中的文件或文件夹复制到公共目录。
     */
    void sharePrivateFileToPublic(SharePrivateFileToPublicRequestDto requestDto);

    /**
     * 删除文件
     */
    Boolean deleteFile(FileRequestDto fileRequestDto);

    /**
     * 文件搜索（按文件名模糊匹配，分页）。
     */
    PageResponseDto<FileResponseDto> searchFiles(SearchFileRequestDto request);

    /**
     * 批量软删除。逐项处理，单项失败不阻塞其他项；返回成功 / 失败明细。
     */
    BatchOperationResultDto batchDelete(BatchFileRequestDto request);

    /**
     * 批量移动到目标父目录（保留原文件名）。
     */
    BatchOperationResultDto batchMove(BatchFileRequestDto request);

    /**
     * 当前用户存储用量。openType=0 私人，1 公共。
     */
    StorageUsageResponseDto getStorageUsage(Integer openType);

    /**
     * 哈希秒传校验：命中则直接落 mapping 复用底层物理文件，不命中再走完整分片上传。
     */
    HashUploadCheckResponseDto checkUploadByHash(HashUploadCheckRequestDto request);

    /**
     * 续传探测：返回已经落盘的分片索引；前端可据此跳过已传分片。
     */
    UploadStatusResponseDto getUploadStatus(Integer openType, String fileName, String filePath, Integer totalChunks);
}
