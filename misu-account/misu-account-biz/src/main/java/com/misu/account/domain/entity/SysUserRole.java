package com.misu.account.domain.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 用户和角色关联 sys_user_role
 *
 */
@Data
@Entity
@Table(name = "sys_user_role")
public class SysUserRole {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(name = "user_id")
    private Long userId;
    
    /** 角色ID */
    @Column(name = "role_id")
    private String roleId;
}
