package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.service.FileService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/fileAdmin")
@Api("文件管理后台接口")
public class FileAdminController {

    @Resource
    private FileService fileService;

    @PostMapping("/startFileMappingBackfill")
    @ApiOperation(value = "启动 file_mapping 回填任务")
    public AjaxResult startFileMappingBackfill() {
        fileService.startFileMappingBackfill();
        return AjaxResult.success();
    }

    @GetMapping("/getFileMappingBackfillStatus")
    @ApiOperation(value = "获取 file_mapping 回填任务状态")
    public AjaxResult getFileMappingBackfillStatus() {
        return AjaxResult.success(fileService.getFileMappingBackfillStatus());
    }
}
