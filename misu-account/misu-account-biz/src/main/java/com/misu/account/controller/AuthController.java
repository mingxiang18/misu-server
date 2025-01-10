package com.misu.account.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.security.annotation.Anonymous;
import com.misu.account.domain.dto.auth.LoginRequestDto;
import com.misu.account.domain.dto.auth.RegisterRequestDto;
import com.misu.account.service.AuthService;
import com.misu.account.dao.impl.UserDaoImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 登录相关Controller
 *
 * @author misu
 */
@RestController
@RequestMapping("/auth")
@Api("认证相关接口")
public class AuthController {

    @Resource
    private UserDaoImpl userDaoImpl;

    @Resource
    private AuthService authService;

    /**
     * 登录
     */
    @Anonymous
    @PostMapping({"/login"})
    @ApiOperation(value="登录")
    public AjaxResult login(@Validated @RequestBody LoginRequestDto loginRequestDto) {
        return AjaxResult.success(authService.login(loginRequestDto));
    }

    /**
     * 注册
     */
    @Anonymous
    @PostMapping({"/register"})
    @ApiOperation(value="注册")
    public AjaxResult register(@Validated @RequestBody RegisterRequestDto registerRequestDto) {
        return AjaxResult.success(authService.register(registerRequestDto));
    }
}
