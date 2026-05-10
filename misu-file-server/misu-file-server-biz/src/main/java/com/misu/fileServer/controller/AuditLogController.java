package com.misu.fileServer.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.AuditLogResponseDto;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.entity.FileAuditLog;
import com.misu.fileServer.repository.FileAuditLogRepository;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计日志查询接口（仅 ADMIN）。
 */
@RestController
@RequestMapping("/audit")
@Api("审计日志接口")
public class AuditLogController {

    @Resource
    private FileAuditLogRepository fileAuditLogRepository;

    @GetMapping({"/list"})
    @ApiOperation(value="审计日志列表（按时间倒序，仅 ADMIN）")
    public AjaxResult list(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "actionType", required = false) String actionType,
            @RequestParam(value = "since", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(value = "until", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until,
            @RequestParam(value = "pageNumber", required = false) Integer pageNumber,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "仅管理员可查看审计日志");
        }

        int p = Math.max(1, pageNumber == null ? 1 : pageNumber);
        int s = pageSize == null ? 50 : Math.max(1, Math.min(500, pageSize));
        Pageable pageable = PageRequest.of(p - 1, s);

        Page<FileAuditLog> page = fileAuditLogRepository.search(
                userId == null || userId.isBlank() ? null : userId,
                actionType == null || actionType.isBlank() ? null : actionType,
                since, until, pageable);

        List<AuditLogResponseDto> items = page.getContent().stream().map(this::toDto).collect(Collectors.toList());
        return AjaxResult.success(new PageResponseDto<>(items, page.getTotalElements(), p, s));
    }

    private AuditLogResponseDto toDto(FileAuditLog log) {
        AuditLogResponseDto dto = new AuditLogResponseDto();
        dto.setId(log.getId());
        dto.setActionType(log.getActionType());
        dto.setUserId(log.getUserId());
        dto.setUserName(log.getUserName());
        dto.setTargetOpenType(log.getTargetOpenType());
        dto.setTargetVirtualPath(log.getTargetVirtualPath());
        dto.setIp(log.getIp());
        dto.setUserAgent(log.getUserAgent());
        dto.setStatusCode(log.getStatusCode());
        dto.setErrorMessage(log.getErrorMessage());
        dto.setRequestId(log.getRequestId());
        dto.setCreateTime(log.getCreateTime());
        return dto;
    }
}
