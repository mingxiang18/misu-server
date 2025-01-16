package com.misu.bot.domain.bot;

import com.bb.bot.entity.bb.BbMessageContent;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * bot回复消息
 */
@Data
public class BotResponseMessage {

    /**
     * 要回复的消息唯一id
     */
    private String receiveMessageId;

    /**
     * 消息列表
     */
    private List<BbMessageContent> messageList = new ArrayList<>();
}
