package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.audit.AuditAction;
import com.misu.fileServer.audit.Audited;
import com.misu.fileServer.domain.dto.CreateShareRequestDto;
import com.misu.fileServer.service.FileShareService;
import com.misu.security.annotation.Anonymous;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 外链分享 controller。
 * /share/* 路径含一个公开（@Anonymous）的下载入口与查询入口。
 */
@Validated
@RestController
@RequestMapping("/share")
@Api("外链分享接口")
public class FileShareController {

    @Resource
    private FileShareService fileShareService;

    @PostMapping({"/create"})
    @ApiOperation(value="创建外链分享")
    @Audited(value = AuditAction.SHARE_CREATE,
            openTypeExpr = "#request.openType",
            virtualPathExpr = "#request.filePath")
    public AjaxResult create(@Valid @RequestBody CreateShareRequestDto request) {
        return AjaxResult.success(fileShareService.createShare(request));
    }

    @GetMapping({"/list"})
    @ApiOperation(value="我的分享列表")
    public AjaxResult list(@RequestParam(value = "pageNumber", required = false) Integer pageNumber,
                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return AjaxResult.success(fileShareService.listShares(pageNumber, pageSize));
    }

    @PostMapping({"/revoke"})
    @ApiOperation(value="撤销分享")
    @Audited(AuditAction.SHARE_REVOKE)
    public AjaxResult revoke(@RequestBody Map<String, Object> body) {
        Object idValue = body == null ? null : body.get("id");
        if (idValue == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        Long id = idValue instanceof Number ? ((Number) idValue).longValue() : Long.parseLong(idValue.toString());
        fileShareService.revokeShare(id);
        return AjaxResult.success();
    }

    /**
     * 公开 — 查询分享元信息（无需登录）。
     */
    @Anonymous
    @GetMapping({"/info"})
    @ApiOperation(value="公开 — 查询分享元信息")
    public AjaxResult info(@RequestParam("token") @NotBlank(message = "token 不能为空") String token) {
        return AjaxResult.success(fileShareService.getSharedInfo(token));
    }

    /**
     * 公开 — 下载分享文件（带可选密码）。
     */
    @Anonymous
    @GetMapping({"/download"})
    @ApiOperation(value="公开 — 下载分享文件")
    public void download(@RequestParam("token") @NotBlank(message = "token 不能为空") String token,
                         @RequestParam(value = "password", required = false) String password,
                         HttpServletRequest request, HttpServletResponse response) {
        fileShareService.downloadShared(token, password, request, response);
    }
}
