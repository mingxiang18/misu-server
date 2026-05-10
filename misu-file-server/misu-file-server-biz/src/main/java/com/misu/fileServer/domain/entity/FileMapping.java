package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "file_mapping",
        schema = "misu_file_server",
        indexes = {
                // 列表 / 搜索：按 open_type + user_id + deleted 过滤后再按 file_type / file_name 排序
                @Index(name = "idx_fm_owner_deleted_type_name",
                        columnList = "open_type,user_id,deleted,file_type,file_name"),
                // 回收站列表：按 update_time 倒序展示最近删除项
                @Index(name = "idx_fm_owner_deleted_update",
                        columnList = "open_type,user_id,deleted,update_time"),
                // 哈希秒传去重查找
                @Index(name = "idx_fm_md5", columnList = "file_md5")
        }
)
public class FileMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "open_type", nullable = false)
    private Integer openType;

    @NotNull
    @ColumnDefault("''")
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @NotNull
    @Column(name = "virtual_path", nullable = false, length = 1200)
    private String virtualPath;

    @NotNull
    @ColumnDefault("''")
    @Column(name = "parent_path", nullable = false, length = 1200)
    private String parentPath;

    @NotNull
    @ColumnDefault("''")
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @NotNull
    @ColumnDefault("'other'")
    @Column(name = "file_type", nullable = false, length = 32)
    private String fileType;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @NotNull
    @Column(name = "target_path", nullable = false, length = 2000)
    private String targetPath;

    @Column(name = "file_md5", length = 32)
    private String fileMd5;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
