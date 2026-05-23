package com.misu.chat.domain.dto;

import com.bb.bot.entity.bb.BbMessageContent;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息项。前端按 senderUserId 与当前用户比对判定左右；BOT 头像走前端 botProfile。
 */
@Data
public class MessageDto {

    private Long id;

    private Long conversationId;

    private String clientMessageId;

    /** USER / BOT */
    private String senderType;

    private String senderUserId;

    /** 发送人昵称（USER）；BOT 为 null */
    private String senderNickName;

    /** 发送人头像（USER）；BOT 为 null */
    private String senderAvatar;

    private String streamId;

    private List<BbMessageContent> content;

    private LocalDateTime createTime;
}
