package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "torrent_info", schema = "misu_file_server")
public class TorrentInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Size(max = 64)
    @NotNull
    @Column(name = "torrent_hash", nullable = false, length = 64)
    private String torrentHash;

    @Size(max = 2000)
    @NotNull
    @Column(name = "torrent_url", nullable = false, length = 2000)
    private String torrentUrl;

    @Size(max = 200)
    @Column(name = "torrent_name", nullable = false, length = 200)
    private String torrentName;

    @Size(max = 1000)
    @Column(name = "download_path", nullable = false, length = 1000)
    private String downloadPath;

    @Column(name = "total_size")
    private Long totalSize;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "state", nullable = false)
    private Integer state;

    @Size(max = 100)
    @Column(name = "remark", length = 100)
    private String remark;

    @Size(max = 64)
    @NotNull
    @ColumnDefault("''")
    @Column(name = "creator_id", nullable = false, length = 64)
    private String creatorId;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

}