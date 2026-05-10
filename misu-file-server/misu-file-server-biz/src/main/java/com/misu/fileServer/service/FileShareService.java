package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.CreateShareRequestDto;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.ShareResponseDto;
import com.misu.fileServer.domain.dto.SharedInfoResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 外链分享 service。
 */
public interface FileShareService {

    /** 创建分享，返回含 token 的详情（含过期时间） */
    ShareResponseDto createShare(CreateShareRequestDto request);

    /** 当前用户的分享列表（按创建时间倒序，分页；revoked 不出现在列表里） */
    PageResponseDto<ShareResponseDto> listShares(Integer pageNumber, Integer pageSize);

    /** 撤销分享（只能撤销自己创建的） */
    void revokeShare(Long id);

    /** 公开查询分享元信息（不需要登录；过期 / 撤销 / 耗尽都通过返回字段反映） */
    SharedInfoResponseDto getSharedInfo(String token);

    /** 公开下载分享文件（密码可选）。会原子加 downloadCount。 */
    void downloadShared(String token, String password,
                         HttpServletRequest request, HttpServletResponse response);
}
