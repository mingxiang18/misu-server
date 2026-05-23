package com.misu.account.service.impl;

import com.misu.account.dao.UserDao;
import com.misu.account.domain.dto.auth.*;
import com.misu.account.domain.dto.user.ResetPasswordRequestDto;
import com.misu.account.domain.dto.user.UserManageDto;
import com.misu.account.domain.entity.QSysUser;
import com.misu.account.domain.entity.SysUser;
import com.misu.account.domain.entity.SysUserRole;
import com.misu.account.dto.UserBriefDto;
import com.misu.account.repository.SysUserRepository;
import com.misu.account.repository.SysUserRoleRepository;
import com.misu.account.service.UserService;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.framework.util.PageUtils;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import com.misu.security.utils.LoginMessageUtil;
import com.querydsl.core.types.dsl.BooleanExpression;
import jakarta.annotation.Resource;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户相关业务
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    private static final String USER_NORMAL = "0";
    private static final String USER_DELETED = "2";

    @Resource
    private UserDao userDao;

    @Resource
    private SysUserRepository userRepository;

    @Resource
    private SysUserRoleRepository userRoleRepository;

    @Resource
    private PasswordEncoder passwordEncoder;

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

    @Override
    public Page<UserManageDto> selectUserPage(String userName, String phoneNumber, String status) {
        checkAdmin();
        Page<SysUser> userPage = userRepository.findAll((root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(userName)) {
                predicates.add(criteriaBuilder.like(root.get("userName"), "%" + userName + "%"));
            }
            if (StringUtils.hasText(phoneNumber)) {
                predicates.add(criteriaBuilder.like(root.get("phoneNumber"), "%" + phoneNumber + "%"));
            }
            if (StringUtils.hasText(status)) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            predicates.add(criteriaBuilder.or(
                    criteriaBuilder.isNull(root.get("delFlag")),
                    criteriaBuilder.notEqual(root.get("delFlag"), USER_DELETED)
            ));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        }, PageUtils.getPageRequest());

        List<UserManageDto> userManageDtoList = buildUserManageDtoList(userPage.getContent());
        return new PageImpl<>(userManageDtoList, userPage.getPageable(), userPage.getTotalElements());
    }

    @Override
    public UserManageDto selectUserById(Long userId) {
        checkAdmin();
        SysUser sysUser = selectExistingUser(userId);
        return buildUserManageDtoList(Collections.singletonList(sysUser)).get(0);
    }

    @Override
    @Transactional(transactionManager = "accountTransactionManager")
    public void addUser(UserManageDto userManageDto) {
        checkAdmin();
        checkUserNameUnique(userManageDto.getUserName(), null);
        checkPhoneNumberUnique(userManageDto.getPhoneNumber(), null);
        if (!StringUtils.hasText(userManageDto.getPassword())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }

        SysUser sysUser = new SysUser();
        fillUser(sysUser, userManageDto);
        sysUser.setPassword(passwordEncoder.encode(userManageDto.getPassword()));
        sysUser.setStatus(StringUtils.hasText(userManageDto.getStatus()) ? userManageDto.getStatus() : USER_NORMAL);
        sysUser.setDelFlag(null);
        userRepository.save(sysUser);
        saveUserRoles(sysUser.getUserId(), userManageDto.getRoles());
    }

    @Override
    @Transactional(transactionManager = "accountTransactionManager")
    public void updateUser(UserManageDto userManageDto) {
        checkAdmin();
        if (userManageDto.getUserId() == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户id不能为空");
        }
        SysUser sysUser = selectExistingUser(userManageDto.getUserId());
        checkUserNameUnique(userManageDto.getUserName(), userManageDto.getUserId());
        checkPhoneNumberUnique(userManageDto.getPhoneNumber(), userManageDto.getUserId());

        fillUser(sysUser, userManageDto);
        sysUser.setStatus(StringUtils.hasText(userManageDto.getStatus()) ? userManageDto.getStatus() : USER_NORMAL);
        userRepository.save(sysUser);
        saveUserRoles(sysUser.getUserId(), userManageDto.getRoles());
    }

    @Override
    @Transactional(transactionManager = "accountTransactionManager")
    public void deleteUser(Long userId) {
        checkAdmin();
        Long currentUserId = LoginMessageUtil.getLoginUser()
                .map(com.misu.security.dto.LoginUser::getUserId)
                .orElse(null);
        if (Objects.equals(currentUserId, userId)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "不能删除当前登录用户");
        }
        SysUser sysUser = selectExistingUser(userId);
        sysUser.setDelFlag(USER_DELETED);
        userRepository.save(sysUser);
    }

    @Override
    @Transactional(transactionManager = "accountTransactionManager")
    public void resetPassword(ResetPasswordRequestDto resetPasswordRequestDto) {
        checkAdmin();
        SysUser sysUser = selectExistingUser(resetPasswordRequestDto.getUserId());
        sysUser.setPassword(passwordEncoder.encode(resetPasswordRequestDto.getPassword()));
        userRepository.save(sysUser);
    }

    private void checkAdmin() {
        if (!AuthorityUtil.hasAuthority(UserRole.ADMIN)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "仅管理员可以操作用户管理");
        }
    }

    private SysUser selectExistingUser(Long userId) {
        SysUser sysUser = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (USER_DELETED.equals(sysUser.getDelFlag())) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return sysUser;
    }

    private List<UserManageDto> buildUserManageDtoList(List<SysUser> sysUserList) {
        if (CollectionUtils.isEmpty(sysUserList)) {
            return Collections.emptyList();
        }

        List<Long> userIds = sysUserList.stream().map(SysUser::getUserId).collect(Collectors.toList());
        Map<Long, List<String>> roleMap = userRoleRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(
                        SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())
                ));

        return sysUserList.stream().map(sysUser -> {
            UserManageDto userManageDto = new UserManageDto();
            userManageDto.setUserId(sysUser.getUserId());
            userManageDto.setUserName(sysUser.getUserName());
            userManageDto.setNickName(sysUser.getNickName());
            userManageDto.setEmail(sysUser.getEmail());
            userManageDto.setPhoneNumber(sysUser.getPhoneNumber());
            userManageDto.setSex(sysUser.getSex());
            userManageDto.setStatus(sysUser.getStatus());
            userManageDto.setRoles(roleMap.getOrDefault(sysUser.getUserId(), Collections.emptyList()));
            return userManageDto;
        }).collect(Collectors.toList());
    }

    private void fillUser(SysUser sysUser, UserManageDto userManageDto) {
        sysUser.setUserName(userManageDto.getUserName());
        sysUser.setNickName(userManageDto.getNickName());
        sysUser.setEmail(userManageDto.getEmail());
        sysUser.setPhoneNumber(userManageDto.getPhoneNumber());
        sysUser.setSex(userManageDto.getSex());
    }

    private void saveUserRoles(Long userId, List<String> roles) {
        userRoleRepository.deleteByUserId(userId);
        List<String> userRoles = CollectionUtils.isEmpty(roles) ? Collections.singletonList(UserRole.USER) : roles;
        userRoles.stream().distinct().forEach(role -> {
            SysUserRole sysUserRole = new SysUserRole();
            sysUserRole.setUserId(userId);
            sysUserRole.setRoleId(role);
            userRoleRepository.save(sysUserRole);
        });
    }

    private void checkUserNameUnique(String userName, Long userId) {
        BooleanExpression expression = QSysUser.sysUser.userName.eq(userName);
        if (userId != null) {
            expression = expression.and(QSysUser.sysUser.userId.ne(userId));
        }
        if (userRepository.exists(expression)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
    }

    private void checkPhoneNumberUnique(String phoneNumber, Long userId) {
        if (!StringUtils.hasText(phoneNumber)) {
            return;
        }
        BooleanExpression expression = QSysUser.sysUser.phoneNumber.eq(phoneNumber);
        if (userId != null) {
            expression = expression.and(QSysUser.sysUser.userId.ne(userId));
        }
        if (userRepository.exists(expression)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "手机号已存在");
        }
    }

    @Override
    public List<UserBriefDto> listBriefByIds(List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyList();
        }
        return userRepository.findAllById(userIds).stream().map(u -> {
            UserBriefDto dto = new UserBriefDto();
            dto.setUserId(u.getUserId());
            dto.setUserName(u.getUserName());
            dto.setNickName(u.getNickName());
            dto.setAvatar(u.getAvatar());
            return dto;
        }).collect(Collectors.toList());
    }
}
