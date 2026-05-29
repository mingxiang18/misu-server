package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.FileDownloadRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 文件读取 / 流式访问 Service。
 *
 * <p>从 {@code FileServiceImpl} 拆出的「取字节 / 流式服务 / 跨用户访问」职责：临时下载链接、
 * token 下载、登录态访问、跨用户访问（放映室 / 分享 / WebDAV）、缩略图 / 视频封面 / 转码视频、
 * 文件存在性校验、目录流式 ZIP 下载。行为与原 god class 完全一致，内部统一改走
 * {@code HttpFileResponder} 写出（Range/ETag/304/Content-Disposition），目录 ZIP 与
 * 转码视频状态判断仍由本 service 负责。</p>
 *
 * @author misu
 */
public interface FileAccessService {

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
     * 校验指定用户视角下的文件是否存在并可访问。
     */
    boolean existsUserFile(Integer openType, String userId, String filePath, boolean allowDirectory);

    /**
     * 流式打包下载文件夹为 ZIP（不落临时文件，不支持 Range，使用分块编码）。
     */
    void downloadDirectoryAsZip(FileRequestDto fileRequestDto, HttpServletResponse response);
}
