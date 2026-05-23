package com.misu.chat.domain.message;

import lombok.Data;

import java.util.List;

/**
 * 机器人token内部包含的信息
 */
@Data
public class ChatTokenMessage {
    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 权限列表
     */
    private List<String> authorities;
}
