package com.misu.account.service;

import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.domain.dto.auth.RegisterRequestDto;

/**
 * 用户相关业务
 */
public interface UserService {
    /**
     * 查询用户登录信息
     */
    LoginUserDto selectUserLoginInfo(String userName);

    /**
     * 注册普通用户
     */
    void registryUser(RegisterRequestDto registerRequestDto);
}
