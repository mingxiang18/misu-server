package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.ShareStagingRequestDto;
import com.misu.fileServer.service.FileService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/listUserFiles")
    @ApiOperation(value = "管理员视角：浏览指定用户在指定 openType 下的文件列表")
    public AjaxResult listUserFiles(@RequestParam("openType") Integer openType,
                                    @RequestParam(value = "userId", required = false) String userId,
                                    @RequestParam(value = "parentPath", required = false) String parentPath) {
        return AjaxResult.success(fileService.listFilesAsAdmin(openType, userId, parentPath));
    }

    @GetMapping("/getUserStorageUsage")
    @ApiOperation(value = "管理员视角：查看指定用户在指定 openType 下的存储用量")
    public AjaxResult getUserStorageUsage(@RequestParam("openType") Integer openType,
                                          @RequestParam(value = "userId", required = false) String userId) {
        return AjaxResult.success(fileService.getStorageUsageAsAdmin(openType, userId));
    }

    @GetMapping("/getStagingRoot")
    @ApiOperation(value = "查看 staging 物理目录根路径")
    public AjaxResult getStagingRoot() {
        return AjaxResult.success(fileService.getStagingRoot());
    }

    @GetMapping("/listStaging")
    @ApiOperation(value = "列出 staging 物理目录下的文件 / 子目录")
    public AjaxResult listStaging(@RequestParam(value = "subPath", required = false) String subPath) {
        return AjaxResult.success(fileService.listStaging(subPath));
    }

    @PostMapping("/shareStagingToPublic")
    @ApiOperation(value = "将 staging 物理文件 / 目录共享到公共目录")
    public AjaxResult shareStagingToPublic(@Valid @RequestBody ShareStagingRequestDto request) {
        fileService.shareStagingToPublic(request);
        return AjaxResult.success();
    }

    @PostMapping("/shareStagingToUser")
    @ApiOperation(value = "将 staging 物理文件 / 目录共享到指定用户的私人目录")
    public AjaxResult shareStagingToUser(@Valid @RequestBody ShareStagingRequestDto request) {
        fileService.shareStagingToUser(request);
        return AjaxResult.success();
    }
}
