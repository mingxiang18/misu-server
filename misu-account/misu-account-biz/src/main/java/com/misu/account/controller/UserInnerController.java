package com.misu.account.controller;

import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.dto.VerifyCredentialsRequestDto;
import com.misu.account.dto.VerifyCredentialsResponseDto;
import com.misu.account.service.UserService;
import com.misu.security.annotation.Anonymous;
import com.misu.security.dto.LoginUser;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private static final String USER_STATUS_DISABLED = "1";
    private static final String USER_DELETED = "2";

    @Resource
    private UserService userService;

    @Resource
    private PasswordEncoder passwordEncoder;

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

    /**
     * 校验用户名+密码（供 WebDAV 等以 Basic Auth 鉴权的内部场景使用）。
     * 明文密码不离开本服务，仅在此处与 BCrypt 哈希比对。
     */
    @PostMapping("/verifyCredentials")
    @ApiOperation(value = "校验用户名+密码")
    public VerifyCredentialsResponseDto verifyCredentials(@RequestBody VerifyCredentialsRequestDto request) {
        VerifyCredentialsResponseDto response = new VerifyCredentialsResponseDto();

        LoginUserDto loginUserDto = userService.selectUserLoginInfo(request.getUserName());
        if (loginUserDto == null) {
            response.setSuccess(false);
            response.setReason("用户不存在或密码错误");
            return response;
        }
        if (USER_DELETED.equals(loginUserDto.getDelFlag())) {
            response.setSuccess(false);
            response.setReason("用户不存在或已删除");
            return response;
        }
        if (USER_STATUS_DISABLED.equals(loginUserDto.getStatus())) {
            response.setSuccess(false);
            response.setReason("用户已停用");
            return response;
        }
        if (!passwordEncoder.matches(request.getPassword(), loginUserDto.getPassword())) {
            response.setSuccess(false);
            response.setReason("用户不存在或密码错误");
            return response;
        }

        response.setSuccess(true);
        response.setUserId(loginUserDto.getUserId());
        response.setUserName(loginUserDto.getUserName());
        response.setAuthorities(loginUserDto.getAuthorities());
        return response;
    }
}
