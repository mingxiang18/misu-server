package com.misu.chat.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 机器人Bot相关配置
 */
@Data
@Configuration
public class ChatConfig {

    @Value("${bot.enable:false}")
    private Boolean enable;

    @Value("${bot.serverPort:}")
    private Integer serverPort;

    @Value("${bot.serverWebSocketUrl:}")
    private String serverWebSocketUrl;

    // ---- 聊天数据层 / 群聊参数（chat.*）----

    /** dev 专用：本地无 bb 上游时，由服务端生成 mock 机器人回复（走真实落库+广播+流式链路） */
    @Value("${chat.mockBotReply:false}")
    private Boolean mockBotReply;

    /** bb 机器人在群里的 userId（被 @ 到它即触发回复） */
    @Value("${chat.botUserId:bot}")
    private String botUserId;

    /** 群聊里没 @bot 时，bot 小概率随机插话的概率 */
    @Value("${chat.groupRandomReplyRate:0.05}")
    private Double groupRandomReplyRate;

}
