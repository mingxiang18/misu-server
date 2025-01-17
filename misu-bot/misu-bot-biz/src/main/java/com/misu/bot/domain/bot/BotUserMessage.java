package com.misu.bot.domain.bot;

import com.bb.bot.entity.bb.BbMessageContent;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人用户消息实体
 */
@Data
public class BotUserMessage {

    /**
     * 消息唯一id
     */
    private String messageId;

    /**
     * 消息内容
     */
    private List<BbMessageContent> messageContentList = new ArrayList<>();
}
