package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 文件版本快照。在 file_mapping 内容被覆盖（uploadFile/coverFlag、
 * hashCheck 命中覆盖、saveText）前打的物理快照。
 */
@Getter
@Setter
@Entity
@Table(
        name = "file_version",
        schema = "misu_file_server",
        indexes = {
                @Index(name = "idx_fv_mapping_versionno", columnList = "mapping_id,version_no"),
                @Index(name = "idx_fv_mapping_ctime", columnList = "mapping_id,create_time"),
                @Index(name = "idx_fv_md5", columnList = "file_md5")
        }
)
public class FileVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 关联 file_mapping.id */
    @NotNull
    @Column(name = "mapping_id", nullable = false)
    private Long mappingId;

    /** 同一 mapping 下递增的版本号，1 开始 */
    @NotNull
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 快照那一刻的文件大小（字节） */
    @NotNull
    @ColumnDefault("0")
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_md5", length = 32)
    private String fileMd5;

    /** 快照物理路径（绝对路径） */
    @NotNull
    @Column(name = "snapshot_target_path", nullable = false, length = 2000)
    private String snapshotTargetPath;

    /** 快照那一刻的原文件名 */
    @NotNull
    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    /** OVERWRITE / TEXT_EDIT / HASH_DEDUP / RESTORE_DEMOTE */
    @NotNull
    @Column(name = "snapshot_reason", nullable = false, length = 32)
    private String snapshotReason;

    /** 触发快照的用户 id */
    @Column(name = "snapshot_by_user_id", length = 64)
    private String snapshotByUserId;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
