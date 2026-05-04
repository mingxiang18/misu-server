package com.misu.account.service;

import com.misu.account.domain.dto.user.ResetPasswordRequestDto;
import com.misu.account.domain.dto.user.UserManageDto;
import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.domain.dto.auth.RegisterRequestDto;
import org.springframework.data.domain.Page;

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

    /**
     * 分页查询用户
     */
    Page<UserManageDto> selectUserPage(String userName, String phoneNumber, String status);

    /**
     * 查询用户详情
     */
    UserManageDto selectUserById(Long userId);

    /**
     * 管理员新增用户
     */
    void addUser(UserManageDto userManageDto);

    /**
     * 管理员修改用户
     */
    void updateUser(UserManageDto userManageDto);

    /**
     * 管理员删除用户
     */
    void deleteUser(Long userId);

    /**
     * 管理员重置密码
     */
    void resetPassword(ResetPasswordRequestDto resetPasswordRequestDto);
}
