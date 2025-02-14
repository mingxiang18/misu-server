package com.misu.account.service.impl;

import com.misu.account.service.UserService;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.account.domain.dto.auth.*;
import com.misu.account.service.AuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证相关业务
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserService userService;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private TokenService tokenService;

    @Value("${register.enable:false}")
    private Boolean registerEnable;

    @Override
    public LoginResponseDto login(LoginRequestDto loginRequestDto) {

        LoginUserDto loginUserDto = userService.selectUserLoginInfo(loginRequestDto.getUserName());

        if (loginUserDto == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户不存在或密码错误");
        }

        //判断密码是否匹配
        if (passwordEncoder.matches(loginRequestDto.getPassword(), loginUserDto.getPassword())) {
            //登录成功后获取用户名并设置token返回
            LoginResponseDto loginResponseDto = new LoginResponseDto();
            loginResponseDto.setUserName(loginUserDto.getUserName());
            loginResponseDto.setToken(
                    tokenService.createUserToken(new LoginUser(loginUserDto.getUserId(), loginUserDto.getUserName(), loginUserDto.getAuthorities())));
            return loginResponseDto;
        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户不存在或密码错误");
        }
    }

    @Override
    public RegisterResponseDto register(RegisterRequestDto registerRequestDto) {
        if (!registerEnable) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前不开放注册");
        }

        //加密密码
        String encodePassword = passwordEncoder.encode(registerRequestDto.getPassword());
        registerRequestDto.setPassword(encodePassword);

        //注册用户
        userService.registryUser(registerRequestDto);

        //返回成功信息
        RegisterResponseDto registerResponseDto = new RegisterResponseDto();
        registerResponseDto.setUserName(registerRequestDto.getUserName());
        return registerResponseDto;
    }
}
