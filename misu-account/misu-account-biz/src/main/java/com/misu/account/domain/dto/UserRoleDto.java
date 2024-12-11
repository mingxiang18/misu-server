package com.misu.account.domain.dto;

import lombok.Data;

@Data
public class UserRoleDto {

    /** 用户ID */
    private Long userId;

    /** 部门ID */
    private Long deptId;

    /** 用户名称 */
    private String userName;

    /** 角色id */
    private Long roleId;
}
