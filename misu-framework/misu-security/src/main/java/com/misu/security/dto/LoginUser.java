package com.misu.security.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录用户身份权限
 *
 */
@Data
@NoArgsConstructor
public class LoginUser {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 权限列表
     */
    private List<String> authorities;

    /**
     * 用户唯一标识
     */
    private String token;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;

    /**
     * 登录IP地址
     */
    private String ipaddr;

    /**
     * 登录地点
     */
    private String loginLocation;

    /**
     * 浏览器类型
     */
    private String browser;

    /**
     * 操作系统
     */
    private String os;

    public LoginUser(Long userId, String userName, List<String> authorities) {
        this.userId = userId;
        this.userName = userName;
        this.authorities = authorities;
    }
}
