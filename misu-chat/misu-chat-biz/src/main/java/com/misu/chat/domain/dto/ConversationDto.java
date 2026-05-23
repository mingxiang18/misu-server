package com.misu.chat.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 */
@Data
public class ConversationDto {

    private Long id;

    /** PRIVATE / GROUP */
    private String type;

    /** 展示标题（PRIVATE 用 bb 名，GROUP 用群名） */
    private String title;

    private String ownerUserId;

    /** 群成员数（含 bb）；PRIVATE 固定为 2 */
    private Integer memberCount;

    /** 最后一条消息预览文本 */
    private String lastMessage;

    /** 群聊时最后发言人昵称（用于「张三：xxx」前缀），PRIVATE 为 null */
    private String lastSenderName;

    private LocalDateTime lastMessageAt;
}
