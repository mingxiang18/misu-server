package com.misu.account.controller;

import com.misu.account.domain.dto.user.ResetPasswordRequestDto;
import com.misu.account.domain.dto.user.UserManageDto;
import com.misu.account.service.UserService;
import com.misu.common.domain.AjaxResult;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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

    @Resource
    private TokenService tokenService;

    @Resource
    private UserService userService;

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

    /**
     * 管理员分页查询用户
     */
    @GetMapping({"/manage/page"})
    @ApiOperation(value="管理员分页查询用户")
    public AjaxResult selectUserPage(@RequestParam(value = "userName", required = false) String userName,
                                     @RequestParam(value = "phoneNumber", required = false) String phoneNumber,
                                     @RequestParam(value = "status", required = false) String status) {
        return AjaxResult.success(userService.selectUserPage(userName, phoneNumber, status));
    }

    /**
     * 管理员查询用户详情
     */
    @GetMapping({"/manage/{userId}"})
    @ApiOperation(value="管理员查询用户详情")
    public AjaxResult selectUserById(@PathVariable("userId") Long userId) {
        return AjaxResult.success(userService.selectUserById(userId));
    }

    /**
     * 管理员新增用户
     */
    @PostMapping({"/manage"})
    @ApiOperation(value="管理员新增用户")
    public AjaxResult addUser(@Validated @RequestBody UserManageDto userManageDto) {
        userService.addUser(userManageDto);
        return AjaxResult.success();
    }

    /**
     * 管理员修改用户
     */
    @PutMapping({"/manage"})
    @ApiOperation(value="管理员修改用户")
    public AjaxResult updateUser(@Validated @RequestBody UserManageDto userManageDto) {
        userService.updateUser(userManageDto);
        return AjaxResult.success();
    }

    /**
     * 管理员删除用户
     */
    @DeleteMapping({"/manage/{userId}"})
    @ApiOperation(value="管理员删除用户")
    public AjaxResult deleteUser(@PathVariable("userId") Long userId) {
        userService.deleteUser(userId);
        return AjaxResult.success();
    }

    /**
     * 管理员重置密码
     */
    @PutMapping({"/manage/resetPassword"})
    @ApiOperation(value="管理员重置密码")
    public AjaxResult resetPassword(@Validated @RequestBody ResetPasswordRequestDto resetPasswordRequestDto) {
        userService.resetPassword(resetPasswordRequestDto);
        return AjaxResult.success();
    }
}
