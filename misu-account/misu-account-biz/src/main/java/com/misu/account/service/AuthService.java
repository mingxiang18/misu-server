package com.misu.account.service;

import com.misu.account.domain.dto.auth.LoginRequestDto;
import com.misu.account.domain.dto.auth.LoginResponseDto;
import com.misu.account.domain.dto.auth.RegisterRequestDto;
import com.misu.account.domain.dto.auth.RegisterResponseDto;

/**
 * 认证相关业务
 */
public interface AuthService {

    /**
     * 登录
     */
    LoginResponseDto login(LoginRequestDto loginRequestDto);

    /**
     * 注册
     */
    RegisterResponseDto register(RegisterRequestDto registerRequestDto);
}
