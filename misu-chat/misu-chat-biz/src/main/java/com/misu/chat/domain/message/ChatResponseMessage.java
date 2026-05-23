package com.misu.chat.domain.message;

import com.bb.bot.entity.bb.BbMessageContent;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * bot回复消息
 */
@Data
public class ChatResponseMessage {

    /**
     * 控制消息类型：auth_ok / auth_error / bot_offline；为 null 表示是机器人正常回复。
     * 前端据此把控制消息与真实回复区分开（控制消息不渲染成聊天气泡，而是驱动重连/补发）。
     */
    private String type;

    /**
     * 要回复的消息唯一id
     */
    private String receiveMessageId;

    /**
     * 流式回复 id：同一逻辑回复的多帧共享同一值；非流式消息为 null。
     * 前端据此把 delta 帧 edit-in-place 续写到同一气泡。
     */
    private String streamId;

    /**
     * 流式帧状态：start / delta / end；非流式消息为 null。
     */
    private String streamState;

    /**
     * 所属会话 id：前端据此把消息归到正确的会话渲染（群聊广播必需）。
     */
    private Long conversationId;

    /**
     * 发送方类型：USER（群里其他成员）/ BOT（bb 回复）。
     */
    private String senderType;

    /**
     * 发送方 userId（USER 时有值，前端据此判左右；BOT 为 null）。
     */
    private String senderUserId;

    /**
     * 发送方昵称（USER 时回填，群聊展示用）。
     */
    private String senderNickname;

    /**
     * 发送方头像（USER 时回填）。
     */
    private String senderAvatar;

    /**
     * 消息列表
     */
    private List<BbMessageContent> messageList = new ArrayList<>();
}
