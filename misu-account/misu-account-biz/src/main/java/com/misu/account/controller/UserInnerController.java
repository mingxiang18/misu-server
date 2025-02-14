package com.misu.account.controller;

import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.service.UserService;
import com.misu.security.annotation.Anonymous;
import com.misu.security.dto.LoginUser;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户相关Controller（内部接口）
 *
 * @author misu
 */
@Anonymous
@RestController
@RequestMapping("/inner/user")
@Api("用户相关接口（内部接口）")
public class UserInnerController {

    @Resource
    private UserService userService;

    /**
     * 根据用户名获取用户信息
     */
    @GetMapping({"/getUserFromUsername"})
    @ApiOperation(value="根据用户名获取用户信息")
    public LoginUser getUserFromUsername(@RequestParam("username") String username) {
        LoginUser loginUser = new LoginUser();
        LoginUserDto loginUserDto = userService.selectUserLoginInfo(username);
        BeanUtils.copyProperties(loginUserDto, loginUser);
        return loginUser;
    }
}
