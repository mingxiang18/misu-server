package com.misu.account.service.impl;

import com.misu.account.dao.UserDao;
import com.misu.account.domain.dto.auth.*;
import com.misu.account.domain.entity.QSysUser;
import com.misu.account.domain.entity.SysUser;
import com.misu.account.domain.entity.SysUserRole;
import com.misu.account.repository.SysUserRepository;
import com.misu.account.repository.SysUserRoleRepository;
import com.misu.account.service.UserService;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.security.constant.UserRole;
import com.querydsl.core.types.dsl.BooleanExpression;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户相关业务
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserDao userDao;

    @Resource
    private SysUserRepository userRepository;

    @Resource
    private SysUserRoleRepository userRoleRepository;

    @Override
    public LoginUserDto selectUserLoginInfo(String userName) {
        return userDao.selectUserLoginInfo(userName);
    }

    @Override
    @Transactional(transactionManager = "accountTransactionManager")
    public void registryUser(RegisterRequestDto registerRequestDto) {
        // 构建条件：用户名或手机号已存在
        BooleanExpression usernameExists = QSysUser.sysUser.userName.eq(registerRequestDto.getUserName());
        if (userRepository.exists(usernameExists)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        BooleanExpression phoneNumberExists = QSysUser.sysUser.phoneNumber.eq(registerRequestDto.getPhoneNumber());
        if (userRepository.exists(phoneNumberExists)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "手机号已存在");
        }

        //封装用户实体并保存
        SysUser sysUser = new SysUser();
        sysUser.setUserName(registerRequestDto.getUserName());
        sysUser.setNickName(registerRequestDto.getNickName());
        sysUser.setEmail(registerRequestDto.getEmail());
        sysUser.setPhoneNumber(registerRequestDto.getPhoneNumber());
        sysUser.setPassword(registerRequestDto.getPassword());
        userRepository.save(sysUser);

        //封装为普通用户权限并保存
        SysUserRole sysUserRole = new SysUserRole();
        sysUserRole.setUserId(sysUser.getUserId());
        sysUserRole.setRoleId(UserRole.USER);
        userRoleRepository.save(sysUserRole);
    }
}
