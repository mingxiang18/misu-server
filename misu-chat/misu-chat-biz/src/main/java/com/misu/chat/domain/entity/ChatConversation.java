package com.misu.chat.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 会话。1对1（type=PRIVATE，仅用户 + bb）与群聊（type=GROUP）共用此模型。
 */
@Getter
@Setter
@Entity
@Table(
        name = "chat_conversation",
        schema = "misu_chat",
        indexes = {
                @Index(name = "idx_conv_owner_type_lastmsg", columnList = "owner_user_id,type,last_message_at")
        }
)
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** PRIVATE / GROUP */
    @ColumnDefault("'PRIVATE'")
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 群名；PRIVATE 为 null */
    @Column(name = "title", length = 128)
    private String title;

    /** 群主（PRIVATE 即该用户自己） */
    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    /** 发给 bb 用的 groupId（GROUP=conv-{id}；PRIVATE 为 null） */
    @Column(name = "bb_group_id", length = 64)
    private String bbGroupId;

    /** 会话列表排序用，发消息时回填 */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
