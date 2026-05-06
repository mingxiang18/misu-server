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
@Table(name = "torrent_file_mapping", schema = "misu_file_server")
public class TorrentFileMapping {
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
    @Column(name = "target_path", nullable = false, length = 2000)
    private String targetPath;

    @Column(name = "torrent_hash", length = 64)
    private String torrentHash;

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
