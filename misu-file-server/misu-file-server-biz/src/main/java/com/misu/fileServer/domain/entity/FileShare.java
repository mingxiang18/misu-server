package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 外链分享。一个 share token 对外暴露一个 file_mapping 的只读访问。
 *
 * <p>一条 share 不直接持久化 mappingId，而是记录 (openType, sourceUserId, virtualPath)
 * 复刻定位语义，避免 mapping 被还原 / 重命名后链接失效；如果原文件被软删则视为失效。</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "file_share",
        schema = "misu_file_server",
        indexes = {
                @Index(name = "idx_share_token", columnList = "share_token", unique = true),
                @Index(name = "idx_share_owner_revoked_ctime", columnList = "owner_user_id,revoked,create_time")
        }
)
public class FileShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 32 位 hex 短 token，外链中直接出现在 URL 路径上 */
    @NotNull
    @Column(name = "share_token", nullable = false, length = 64, unique = true)
    private String shareToken;

    /** 创建者 userId（创建时取 LoginUser.userId 字符串形式） */
    @NotNull
    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    /** 0 私人 / 1 公共 — 与 file_mapping.open_type 对齐 */
    @NotNull
    @ColumnDefault("0")
    @Column(name = "open_type", nullable = false)
    private Integer openType;

    /** 在 file_mapping 视角下的所有者 userId（私人 = ownerUserId；公共 = "public"） */
    @NotNull
    @Column(name = "source_user_id", nullable = false, length = 64)
    private String sourceUserId;

    /** 被分享文件的 virtual_path */
    @NotNull
    @Column(name = "source_virtual_path", nullable = false, length = 1200)
    private String sourceVirtualPath;

    /** 过期时间 */
    @NotNull
    @Column(name = "expire_time", nullable = false)
    private LocalDateTime expireTime;

    /** 可选密码的 BCrypt hash；null 代表无密码 */
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    /** 可选下载次数上限；null 代表不限制 */
    @Column(name = "max_downloads")
    private Integer maxDownloads;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "download_count", nullable = false)
    private Integer downloadCount = 0;

    /** 是否已被创建者主动撤销 */
    @NotNull
    @ColumnDefault("0")
    @Column(name = "revoked", nullable = false)
    private Boolean revoked = false;

    @NotNull
    // 使用 CURRENT_TIMESTAMP(6) 与 Hibernate 生成的 datetime(6) 精度一致；MySQL 8 STRICT 模式下不加精度会拒
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
