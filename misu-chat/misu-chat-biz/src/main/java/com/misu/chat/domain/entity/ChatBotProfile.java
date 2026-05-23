package com.misu.chat.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * bb 机器人全局资料（单行，id 固定 1）。头像由 ADMIN 设置，全局生效。
 */
@Getter
@Setter
@Entity
@Table(name = "chat_bot_profile", schema = "misu_chat")
public class ChatBotProfile {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 64)
    private String name;

    /** data URL（base64），全局头像；LONGTEXT 以容纳真实头像图片 */
    @Column(name = "avatar", columnDefinition = "LONGTEXT")
    private String avatar;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
