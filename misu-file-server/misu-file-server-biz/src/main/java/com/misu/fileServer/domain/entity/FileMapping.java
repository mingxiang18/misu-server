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
@Table(name = "file_mapping", schema = "misu_file_server")
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
