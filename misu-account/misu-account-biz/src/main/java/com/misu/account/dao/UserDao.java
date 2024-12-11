package com.misu.account.dao;

import com.misu.account.domain.dto.auth.LoginUserDto;

/**
 * 用户数据层
 */
public interface UserDao {

    /**
     * 获取用户登录数据
     */
    LoginUserDto selectUserLoginInfo(String userName);
}
