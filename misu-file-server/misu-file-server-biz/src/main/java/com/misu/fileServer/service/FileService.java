package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
     * 访问登录用户可见文件。
     */
    void accessUserFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response, boolean attachment);

    /**
     * 访问指定用户文件，用于放映室把房主文件共享给房间观众。
     */
    void accessUserFileAsUser(Integer openType, String userId, String filePath,
                              HttpServletRequest request, HttpServletResponse response, boolean attachment);

    /**
     * 访问指定用户可见转码视频，用于放映室共享播放。
     */
    void transcodedVideoFileAsUser(Integer openType, String userId, String filePath,
                                   HttpServletRequest request, HttpServletResponse response);

    /**
     * 访问登录用户可见图片缩略图。
     */
    void previewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response);

    /**
     * 访问登录用户可见视频封面。
     */
    void videoPreviewFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response);

    /**
     * 访问登录用户可见转码视频。
     */
    void transcodedVideoFile(FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response);

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
     * 管理员触发 file_mapping 回填任务（异步）。
     */
    void startFileMappingBackfill();

    /**
     * 获取 file_mapping 回填任务状态。
     */
    Map<String, Object> getFileMappingBackfillStatus();

    /**
     * 校验指定用户视角下的文件是否存在并可访问。
     */
    boolean existsUserFile(Integer openType, String userId, String filePath, boolean allowDirectory);

    /**
     * 文件搜索（按文件名模糊匹配，分页）。
     */
    PageResponseDto<FileResponseDto> searchFiles(SearchFileRequestDto request);

    /**
     * 回收站列表（已逻辑删除项），按删除时间倒序。
     */
    PageResponseDto<TrashFileResponseDto> listTrash(Integer openType, Integer pageNumber, Integer pageSize);

    /**
     * 从回收站还原指定 mapping。
     */
    void restoreFromTrash(Long id);

    /**
     * 永久删除回收站中的某条 mapping（含物理文件，若无其他 active 映射引用）。
     */
    void purgeFromTrash(Long id);

    /**
     * 批量软删除。逐项处理，单项失败不阻塞其他项；返回成功 / 失败明细。
     */
    BatchOperationResultDto batchDelete(BatchFileRequestDto request);

    /**
     * 批量移动到目标父目录（保留原文件名）。
     */
    BatchOperationResultDto batchMove(BatchFileRequestDto request);

    /**
     * 流式打包下载文件夹为 ZIP（不落临时文件，不支持 Range，使用分块编码）。
     */
    void downloadDirectoryAsZip(FileRequestDto fileRequestDto, HttpServletResponse response);
}
