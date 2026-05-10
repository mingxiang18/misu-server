package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.audit.AuditAction;
import com.misu.fileServer.audit.Audited;
import com.misu.fileServer.domain.dto.BatchFileRequestDto;
import com.misu.fileServer.domain.dto.FileDownloadRequestDto;
import com.misu.fileServer.domain.dto.FileRenameRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.FileUploadRequest;
import com.misu.fileServer.domain.dto.HashUploadCheckRequestDto;
import com.misu.fileServer.domain.dto.SearchFileRequestDto;
import com.misu.fileServer.domain.dto.SharePrivateFileToPublicRequestDto;
import com.misu.fileServer.service.FileService;
import com.misu.security.annotation.Anonymous;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文件相关Controller
 *
 * @author misu
 */
@Validated
@RestController
@RequestMapping("/file")
@Api("文件相关接口")
public class FileController {

    @Resource
    private FileService fileService;

    /**
     * 获取目录文件
     */
    @GetMapping({"/getFileList"})
    @ApiOperation(value="获取目录文件")
    public AjaxResult getFileList(@Valid FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.getFileList(fileRequestDto));
    }

    /**
     * 获取文件的临时下载链接
     */
    @GetMapping({"/getFileDownloadLink"})
    @ApiOperation(value="获取文件的临时下载链接")
    public AjaxResult getFileDownloadLink(@Valid FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.getFileDownloadLink(fileRequestDto));
    }

    /**
     * 下载文件
     */
    @Anonymous
    @GetMapping({"/downloadFile"})
    @ApiOperation(value="下载文件")
    public void downloadPublicFile(@Valid FileDownloadRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.downloadFile(fileRequestDto, request, response);
    }

    /**
     * 登录态下载文件
     */
    @GetMapping({"/download"})
    @ApiOperation(value="登录态下载文件")
    public void downloadUserFile(@Valid FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.accessUserFile(fileRequestDto, request, response, true);
    }

    /**
     * 登录态播放/预览原文件
     */
    @GetMapping({"/stream"})
    @ApiOperation(value="登录态播放/预览原文件")
    public void streamUserFile(@Valid FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.accessUserFile(fileRequestDto, request, response, false);
    }

    /**
     * 登录态获取图片缩略图
     */
    @GetMapping({"/preview"})
    @ApiOperation(value="登录态获取图片缩略图")
    public void previewFile(@Valid FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.previewFile(fileRequestDto, request, response);
    }

    /**
     * 登录态获取视频封面
     */
    @GetMapping({"/videoPreview"})
    @ApiOperation(value="登录态获取视频封面")
    public void videoPreviewFile(@Valid FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.videoPreviewFile(fileRequestDto, request, response);
    }

    /**
     * 登录态播放转码视频
     */
    @GetMapping({"/transcodedVideo"})
    @ApiOperation(value="登录态播放转码视频")
    public void transcodedVideoFile(@Valid FileRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        fileService.transcodedVideoFile(fileRequestDto, request, response);
    }

    /**
     * 上传文件
     */
    @PostMapping({"/uploadFile"})
    @ApiOperation(value="上传文件")
    @Audited(AuditAction.UPLOAD_FILE)
    public AjaxResult uploadFile(@Valid FileUploadRequest fileUploadRequest) {
        return AjaxResult.success(fileService.uploadFile(fileUploadRequest));
    }

    /**
     * 创建文件目录
     */
    @PostMapping({"/createDirectory"})
    @ApiOperation(value="创建文件目录")
    @Audited(AuditAction.CREATE_DIR)
    public AjaxResult createDirectory(@Valid @RequestBody FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.createDirectory(fileRequestDto));
    }

    /**
     * 移动文件（包含重命名）
     */
    @PostMapping({"/moveFile"})
    @ApiOperation(value="移动文件（包含重命名）")
    @Audited(value = AuditAction.MOVE_FILE,
            openTypeExpr = "#fileRenameRequestDto.openType",
            virtualPathExpr = "#fileRenameRequestDto.originFilePath")
    public AjaxResult moveFile(@Valid @RequestBody FileRenameRequestDto fileRenameRequestDto) {
        fileService.moveFile(fileRenameRequestDto);
        return AjaxResult.success();
    }

    /**
     * 将私人目录文件共享到公共目录
     */
    @PostMapping({"/sharePrivateFileToPublic"})
    @ApiOperation(value="将私人目录文件共享到公共目录")
    @Audited(value = AuditAction.SHARE_TO_PUBLIC,
            virtualPathExpr = "#requestDto.sourceFilePath")
    public AjaxResult sharePrivateFileToPublic(@Valid @RequestBody SharePrivateFileToPublicRequestDto requestDto) {
        fileService.sharePrivateFileToPublic(requestDto);
        return AjaxResult.success();
    }

    /**
     * 删除文件
     */
    @PostMapping({"/deleteFile"})
    @ApiOperation(value="删除文件")
    @Audited(AuditAction.DELETE_FILE)
    public AjaxResult deleteFile(@Valid @RequestBody FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.deleteFile(fileRequestDto));
    }

    // ===================== M3：搜索 + 回收站 =====================

    /**
     * 文件搜索（按文件名模糊匹配，分页）
     */
    @GetMapping({"/search"})
    @ApiOperation(value="文件搜索")
    public AjaxResult searchFiles(@Valid SearchFileRequestDto request) {
        return AjaxResult.success(fileService.searchFiles(request));
    }

    /**
     * 回收站列表（按删除时间倒序）
     */
    @GetMapping({"/listTrash"})
    @ApiOperation(value="回收站列表")
    public AjaxResult listTrash(@RequestParam("openType") @NotNull(message = "文件公开类型不能为空") Integer openType,
                                @RequestParam(value = "pageNumber", required = false) Integer pageNumber,
                                @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return AjaxResult.success(fileService.listTrash(openType, pageNumber, pageSize));
    }

    /**
     * 从回收站还原
     */
    @PostMapping({"/restoreTrash"})
    @ApiOperation(value="从回收站还原")
    @Audited(AuditAction.RESTORE_TRASH)
    public AjaxResult restoreTrash(@RequestBody Map<String, Object> body) {
        Object idValue = body == null ? null : body.get("id");
        Long id = parseLongOrThrow(idValue);
        fileService.restoreFromTrash(id);
        return AjaxResult.success();
    }

    /**
     * 永久删除回收站中的某项
     */
    @PostMapping({"/purgeTrash"})
    @ApiOperation(value="永久删除回收站中的某项")
    @Audited(AuditAction.PURGE_TRASH)
    public AjaxResult purgeTrash(@RequestBody Map<String, Object> body) {
        Object idValue = body == null ? null : body.get("id");
        Long id = parseLongOrThrow(idValue);
        fileService.purgeFromTrash(id);
        return AjaxResult.success();
    }

    private Long parseLongOrThrow(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        return Long.parseLong(value.toString());
    }

    // ===================== M4：批量操作 + ZIP 文件夹下载 =====================

    /**
     * 批量软删除（最多 500 项）
     */
    @PostMapping({"/batchDelete"})
    @ApiOperation(value="批量删除")
    @Audited(value = AuditAction.BATCH_DELETE, openTypeExpr = "#request.openType")
    public AjaxResult batchDelete(@Valid @RequestBody BatchFileRequestDto request) {
        return AjaxResult.success(fileService.batchDelete(request));
    }

    /**
     * 批量移动到目标父目录（最多 500 项）
     */
    @PostMapping({"/batchMove"})
    @ApiOperation(value="批量移动")
    @Audited(value = AuditAction.BATCH_MOVE, openTypeExpr = "#request.openType",
            virtualPathExpr = "#request.targetParentPath")
    public AjaxResult batchMove(@Valid @RequestBody BatchFileRequestDto request) {
        return AjaxResult.success(fileService.batchMove(request));
    }

    /**
     * 流式打包下载文件夹为 ZIP
     */
    @GetMapping({"/downloadDirectory"})
    @ApiOperation(value="流式 ZIP 下载文件夹")
    public void downloadDirectory(@Valid FileRequestDto fileRequestDto, HttpServletResponse response) {
        fileService.downloadDirectoryAsZip(fileRequestDto, response);
    }

    // ===================== M5：配额 + 哈希秒传 =====================

    /**
     * 当前用户存储用量
     */
    @GetMapping({"/getStorageUsage"})
    @ApiOperation(value="存储用量")
    public AjaxResult getStorageUsage(@RequestParam("openType") @NotNull(message = "文件公开类型不能为空") Integer openType) {
        return AjaxResult.success(fileService.getStorageUsage(openType));
    }

    /**
     * 哈希秒传校验。命中即秒传，不命中前端再走原本的 /uploadFile 分片上传。
     */
    @PostMapping({"/checkUploadByHash"})
    @ApiOperation(value="哈希秒传校验")
    @Audited(value = AuditAction.HASH_INSTANT_UPLOAD,
            openTypeExpr = "#request.openType",
            virtualPathExpr = "#request.filePath + '/' + #request.fileName")
    public AjaxResult checkUploadByHash(@Valid @RequestBody HashUploadCheckRequestDto request) {
        return AjaxResult.success(fileService.checkUploadByHash(request));
    }

    /**
     * 续传探测：返回已经落盘的分片索引；前端可据此跳过已传分片。
     */
    @GetMapping({"/getUploadStatus"})
    @ApiOperation(value="续传探测")
    public AjaxResult getUploadStatus(
            @RequestParam("openType") @NotNull(message = "文件公开类型不能为空") Integer openType,
            @RequestParam("fileName") @jakarta.validation.constraints.NotBlank(message = "文件名不能为空") String fileName,
            @RequestParam(value = "filePath", required = false) String filePath,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks) {
        return AjaxResult.success(fileService.getUploadStatus(openType, fileName, filePath, totalChunks));
    }
}
