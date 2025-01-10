package com.misu.account.dao.impl;

import com.misu.account.dao.UserDao;
import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.domain.entity.QSysUser;
import com.misu.account.domain.entity.QSysUserRole;
import com.misu.account.domain.entity.SysUserRole;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户数据层
 */
@Component
public class UserDaoImpl implements UserDao {

    private final QSysUser userModel = QSysUser.sysUser;
    private final QSysUserRole userRoleModel = QSysUserRole.sysUserRole;

    @Resource
    private JPAQueryFactory accountJpaQueryFactory;

    @Override
    public LoginUserDto selectUserLoginInfo(String userName) {
        //查询对应的用户数据
        LoginUserDto loginUserDto = accountJpaQueryFactory
                .select(Projections.bean(
                        LoginUserDto.class,
                        userModel.userId,
                        userModel.userName,
                        userModel.password
                ))
                .from(userModel)
                .where(userModel.userName.eq(userName))
                .limit(1)
                .fetchFirst();

        if (loginUserDto != null) {
            //查询用户的角色
            List<SysUserRole> sysUserRoles = accountJpaQueryFactory
                    .selectFrom(userRoleModel)
                    .where(userRoleModel.userId.eq(loginUserDto.getUserId()))
                    .fetch();
            //封装设置为权限
            loginUserDto.setAuthorities(sysUserRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList()));
        }

        return loginUserDto;
    }
}
