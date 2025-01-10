package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.FileDownloadRequestDto;
import com.misu.fileServer.domain.dto.FileRenameRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.FileUploadRequest;
import com.misu.fileServer.service.FileService;
import com.misu.security.annotation.Anonymous;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
     * 上传文件
     */
    @PostMapping({"/uploadFile"})
    @ApiOperation(value="上传文件")
    public AjaxResult uploadFile(@Valid FileUploadRequest fileUploadRequest) {
        return AjaxResult.success(fileService.uploadFile(fileUploadRequest));
    }

    /**
     * 创建文件目录
     */
    @PostMapping({"/createDirectory"})
    @ApiOperation(value="创建文件目录")
    public AjaxResult createDirectory(@Valid @RequestBody FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.createDirectory(fileRequestDto));
    }

    /**
     * 移动文件（包含重命名）
     */
    @PostMapping({"/moveFile"})
    @ApiOperation(value="移动文件（包含重命名）")
    public AjaxResult moveFile(@Valid @RequestBody FileRenameRequestDto fileRenameRequestDto) {
        fileService.moveFile(fileRenameRequestDto);
        return AjaxResult.success();
    }

    /**
     * 删除文件
     */
    @PostMapping({"/deleteFile"})
    @ApiOperation(value="删除文件")
    public AjaxResult deleteFile(@Valid @RequestBody FileRequestDto fileRequestDto) {
        return AjaxResult.success(fileService.deleteFile(fileRequestDto));
    }
}
