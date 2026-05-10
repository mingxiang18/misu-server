package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 关键写操作的审计日志。读操作不进入此表（量太大）。
 */
@Getter
@Setter
@Entity
@Table(
        name = "file_audit_log",
        schema = "misu_file_server",
        indexes = {
                @Index(name = "idx_audit_user_time", columnList = "user_id,create_time"),
                @Index(name = "idx_audit_action_time", columnList = "action_type,create_time"),
                // 不包含 target_virtual_path（varchar 1200 * utf8mb4 超 3072 字节限制）；
                // path 列若需检索由 prod DDL 单独建前缀索引
                @Index(name = "idx_audit_target_owner", columnList = "target_open_type,target_user_id")
        }
)
public class FileAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "user_name", length = 64)
    private String userName;

    @Column(name = "target_open_type")
    private Integer targetOpenType;

    @Column(name = "target_user_id", length = 64)
    private String targetUserId;

    @Column(name = "target_virtual_path", length = 1200)
    private String targetVirtualPath;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 200 = 成功；其它 = 业务异常的 statusCode */
    @NotNull
    @ColumnDefault("200")
    @Column(name = "status_code", nullable = false)
    private Integer statusCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 关联同一请求的 trace id（取自 RequestId / X-Request-Id 或 UUID 兜底） */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** 任意补充信息（JSON 字符串），目前未强约束 schema */
    @Column(name = "extra", length = 1000)
    private String extra;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
