package com.misu.chat.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 会话成员。PRIVATE 会话只放该用户 1 条；bb 是隐式参与方，不入此表。
 */
@Getter
@Setter
@Entity
@Table(
        name = "chat_conversation_member",
        schema = "misu_chat",
        indexes = {
                @Index(name = "idx_member_user_conv", columnList = "member_user_id,conversation_id"),
                @Index(name = "idx_member_conv", columnList = "conversation_id"),
                @Index(name = "uk_member_conv_user", columnList = "conversation_id,member_user_id", unique = true)
        }
)
public class ChatConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "member_user_id", nullable = false, length = 64)
    private String memberUserId;

    /** OWNER / MEMBER */
    @ColumnDefault("'MEMBER'")
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** 预留未读基线（本期未用） */
    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;
}
