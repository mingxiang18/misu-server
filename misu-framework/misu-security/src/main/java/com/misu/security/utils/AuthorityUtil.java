package com.misu.security.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

/**
 * 用户权限检查工具
 */
public class AuthorityUtil {

    /**
     * 判断当前用户是否拥有指定角色权限
     */
    public static Boolean hasAuthority(String userRole) {
        return hasAuthority(Collections.singletonList(userRole));
    }

    /**
     * 判断当前用户是否拥有指定角色权限
     */
    public static Boolean hasAuthority(List<String> userRoleList) {
        boolean hasAuthority = false;

        //获取登陆用户的权限列表
        Collection<? extends GrantedAuthority> authorities = Optional.ofNullable(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getAuthorities)
                .orElse(new ArrayList<>());

        //判断是否拥有该角色
        for (GrantedAuthority grantedAuthority : authorities) {
            if (userRoleList.contains(grantedAuthority.getAuthority())) {
                hasAuthority = true;
                break;
            }
        }

        return hasAuthority;
    }
}
