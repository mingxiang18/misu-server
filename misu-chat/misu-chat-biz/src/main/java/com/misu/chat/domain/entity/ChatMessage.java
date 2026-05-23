package com.misu.chat.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 一条消息。内容整存 content_json（List&lt;BbMessageContent&gt; 的 fastjson2 序列化），
 * 与 bb 协议 / 前端渲染的 wire 格式一致。
 */
@Getter
@Setter
@Entity
@Table(
        name = "chat_message",
        schema = "misu_chat",
        indexes = {
                @Index(name = "idx_msg_conv_ctime_id", columnList = "conversation_id,create_time,id")
        }
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 前端 messageId（幂等 / 回执用） */
    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    /** USER / BOT */
    @ColumnDefault("'USER'")
    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    /** USER=发言人 userId；BOT 为 null */
    @Column(name = "sender_user_id", length = 64)
    private String senderUserId;

    /** bb 流式回复合并落库用 */
    @Column(name = "stream_id", length = 64)
    private String streamId;

    // LONGTEXT：内容含图片/文件的 base64，TEXT(64KB) 装不下（一张图就超）
    @Column(name = "content_json", columnDefinition = "LONGTEXT", nullable = false)
    private String contentJson;

    /** 群 @ 的 userId 逗号分隔（长串，不进复合索引） */
    @Column(name = "at_user_ids", length = 512)
    private String atUserIds;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
