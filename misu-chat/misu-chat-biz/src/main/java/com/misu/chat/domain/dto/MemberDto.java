package com.misu.chat.domain.dto;

import lombok.Data;

/**
 * 群成员展示项。bb 作为隐式成员也会出现在列表里（botFlag=true）。
 */
@Data
public class MemberDto {
    private String userId;
    private String nickName;
    private String avatar;
    /** OWNER / MEMBER */
    private String role;
    private Boolean botFlag;
    /** 是否当前请求用户自己（前端据此打「我」标 + @ 列表排除自己） */
    private Boolean self;
}
