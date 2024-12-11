package com.misu.account.domain.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对象 sys_user
 * 
 * @author misu
 */
@Data
@Entity
@Table(name = "sys_user")
public class SysUser {

    /** 用户ID */
    @Id
    @Column(name = "user_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    /** 部门ID */
    @Column(name = "dept_id")
    private Long deptId;

    /** 用户账号 */
    @Column(name = "user_name")
    private String userName;

    /** 用户昵称 */
    @Column(name = "nick_name")
    private String nickName;

    /** 用户邮箱 */
    @Column(name = "email")
    private String email;

    /** 手机号码 */
    @Column(name = "phone_number")
    private String phoneNumber;

    /** 用户性别 */
    @Column
    private String sex;

    /** 用户头像 */
    @Column
    private String avatar;

    /** 密码 */
    @Column
    private String password;

    /** 帐号状态（0正常 1停用） */
    @Column
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    @Column(name = "del_flag")
    private String delFlag;

    /** 最后登录IP */
    @Column(name = "login_ip")
    private String loginIp;

    /** 最后登录时间 */
    @Column(name = "login_date")
    private LocalDateTime loginDate;
}
