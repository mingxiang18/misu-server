package com.misu.security.utils;

import com.misu.security.dto.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.parameters.P;

import java.util.Optional;

/**
 * 登录信息获取工具
 */
public class LoginMessageUtil {

    /**
     * 获取当前登录信息
     */
    public static Optional<LoginUser> getLoginUser() {
        return Optional.ofNullable(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .map(principal -> {
                    if (principal instanceof LoginUser) {
                        return (LoginUser) principal;
                    }else {
                        return null;
                    }
                });
    }
}
