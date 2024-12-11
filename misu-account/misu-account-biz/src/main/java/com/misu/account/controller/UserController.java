package com.misu.account.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.security.dto.LoginUser;
import com.misu.security.filter.JwtAuthenticationFilter;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 用户相关Controller
 *
 * @author misu
 */
@RestController
@RequestMapping("/user")
@Api("用户相关接口")
public class UserController {

    @Autowired
    private TokenService tokenService;

    /**
     * 从token获取用户信息
     */
    @GetMapping({"/getUserFromToken"})
    @ApiOperation(value="从token获取用户信息")
    public AjaxResult getUserFromToken(HttpServletRequest request) {
        Optional<LoginUser> loginUser = LoginMessageUtil.getLoginUser();
        if (loginUser.isPresent()) {
            loginUser.get().setUserId(null);
            return AjaxResult.success(loginUser.get());
        }else {
            return AjaxResult.error();
        }
    }
}
