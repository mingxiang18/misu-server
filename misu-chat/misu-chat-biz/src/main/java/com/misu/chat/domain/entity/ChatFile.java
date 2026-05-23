package com.misu.chat.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 群文件索引：群里出现的每个文件（成员发的 / bb 返回的）记一行。
 * 文件内容仍存在对应消息的 content_json 里（不重复存储），下载时按 message_id 取。
 */
@Getter
@Setter
@Entity
@Table(
        name = "chat_file",
        schema = "misu_chat",
        indexes = {
                @Index(name = "idx_file_conv_deleted_ctime", columnList = "conversation_id,deleted,create_time")
        }
)
public class ChatFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 所属消息（下载时按它取 content_json 里的文件内容） */
    @Column(name = "message_id")
    private Long messageId;

    /** 上传者；bb 返回的为 null */
    @Column(name = "uploader_user_id", length = 64)
    private String uploaderUserId;

    /** USER / BOT */
    @Column(name = "sender_type", length = 16)
    private String senderType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "size")
    private Long size;

    /** image / file —— 决定前端内联渲染还是文件卡片 */
    @Column(name = "category", length = 16)
    private String category;

    /** 磁盘相对路径（{conversationId}/{uuid.ext}）；外链文件为 null */
    @Column(name = "store_path", length = 512)
    private String storePath;

    /** 外链 URL（bb 返回的 netFile）；磁盘文件为 null */
    @Column(name = "net_url", length = 1024)
    private String netUrl;

    /** 兼容旧数据：localFile / netFile（旧消息内容内联 base64 的来源标记） */
    @Column(name = "source_type", length = 16)
    private String sourceType;

    @ColumnDefault("0")
    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
