package com.misu.fileServer.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.audit.AuditAction;
import com.misu.fileServer.audit.Audited;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/version")
@Api("文件版本接口")
public class FileVersionController {

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FileMappingRepository fileMappingRepository;

    /** 列出指定文件的版本（按 versionNo 倒序） */
    @GetMapping({"/list"})
    @ApiOperation(value="列出文件版本")
    public AjaxResult list(@RequestParam("openType") @NotNull(message = "文件公开类型不能为空") Integer openType,
                           @RequestParam("filePath") @NotBlank(message = "文件路径不能为空") String filePath) {
        FileMapping mapping = resolveMapping(openType, filePath);
        return AjaxResult.success(fileVersionService.listVersions(mapping));
    }

    /** 还原到指定版本（当前会再产生一个 RESTORE_DEMOTE 版本） */
    @PostMapping({"/restore"})
    @ApiOperation(value="还原文件版本")
    @Audited(value = AuditAction.RESTORE_TRASH /* 复用还原标签 */)
    public AjaxResult restore(@RequestBody Map<String, Object> body) {
        Long id = parseId(body);
        fileVersionService.restoreVersion(id);
        return AjaxResult.success();
    }

    /** 删除单个版本 */
    @PostMapping({"/purge"})
    @ApiOperation(value="删除版本快照")
    @Audited(value = AuditAction.PURGE_TRASH /* 复用永久删除标签 */)
    public AjaxResult purge(@RequestBody Map<String, Object> body) {
        Long id = parseId(body);
        fileVersionService.purgeVersion(id);
        return AjaxResult.success();
    }

    private Long parseId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("id");
        if (v == null) throw new ServiceException(HttpStatus.BAD_REQUEST, "id 不能为空");
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(v.toString());
    }

    private FileMapping resolveMapping(Integer openType, String filePath) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        String userId = openType != null && openType == 1 ? "public" : loginUser.getUserId().toString();
        String relativePath = FilePathGuard.normalizeRelativePath(filePath);
        return fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, userId, relativePath)
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除"));
    }
}
