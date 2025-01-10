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
@Table(name = "torrent_user_relation", schema = "misu_file_server")
public class TorrentUserRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Size(max = 64)
    @NotNull
    @ColumnDefault("''")
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Size(max = 1000)
    @NotNull
    @ColumnDefault("''")
    @Column(name = "user_file_path", nullable = false, length = 1000)
    private String userFilePath;

    @Size(max = 64)
    @NotNull
    @Column(name = "torrent_hash", nullable = false, length = 64)
    private String torrentHash;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "state", nullable = false)
    private Integer state;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Size(max = 200)
    @Column(name = "failed_reason", length = 200)
    private String failedReason;

}