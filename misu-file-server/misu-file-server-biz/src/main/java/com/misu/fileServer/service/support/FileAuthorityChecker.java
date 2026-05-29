package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.AuthorityUtil;
import com.misu.security.utils.LoginMessageUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 文件服务权限校验组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的「权限校验」职责：公共目录写权限门控、管理员视角门控、
 * mapping 归属校验。方法签名 / 逻辑与原 god class 完全一致，仅可见性放宽为 public 供
 * FileServiceImpl 委托。</p>
 *
 * <p>严格区分 403（FORBIDDEN，已登录但权限不足）与 401（UNAUTHORIZED，未登录 / 过期）：
 * 角色判定经 {@link AuthorityUtil#hasAuthority}（静态读 SecurityContextHolder 的权限列表），
 * 当前用户经 {@link LoginMessageUtil#getLoginUser()}。</p>
 *
 * @author misu
 */
@Component
public class FileAuthorityChecker {

    /**
     * 公共目录写操作需管理员；私人空间（openType=0）或空 openType 不校验。
     */
    public void checkPublicWriteAuthority(Integer openType) {
        if (openType != null && openType == 1
                && !AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            // 403 FORBIDDEN：用户已登录但权限不足；不能用 401（前端会当登录失效跳登录页）
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权修改公共文件");
        }
    }

    /**
     * 管理员视角门控：浏览其他用户文件需管理员，否则抛 403。
     */
    public void checkAdminViewAuthority() {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权浏览其他用户的文件");
        }
    }

    /**
     * 校验 mapping 归属：私人空间必须是当前登录用户；公共空间必须是管理员。
     * checkPublicWriteAuthority 已经覆盖公共空间，本方法只补私人空间归属。
     */
    public void ensureMappingOwnership(FileMapping mapping) {
        if (mapping.getOpenType() != null && mapping.getOpenType() == 0) {
            LoginUser loginUser = LoginMessageUtil.getLoginUser()
                    .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录"));
            if (!StringUtils.equals(mapping.getUserId(), loginUser.getUserId().toString())) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "无权操作他人文件");
            }
        }
    }
}
