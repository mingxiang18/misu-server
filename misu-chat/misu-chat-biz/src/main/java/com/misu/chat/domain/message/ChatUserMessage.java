package com.misu.chat.domain.message;

import com.bb.bot.entity.bb.BbMessageContent;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人用户消息实体
 */
@Data
public class ChatUserMessage {

    /**
     * 消息唯一id
     */
    private String messageId;

    /**
     * 所属会话 id（前端发送时带上，服务端据此落库与路由）
     */
    private Long conversationId;

    /**
     * 群聊 @ 的 userId 列表；私聊为空
     */
    private List<String> atUserIds = new ArrayList<>();

    /**
     * 消息内容
     */
    private List<BbMessageContent> messageContentList = new ArrayList<>();
}
