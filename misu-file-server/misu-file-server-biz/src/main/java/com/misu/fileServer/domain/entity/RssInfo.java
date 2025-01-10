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
@Table(name = "rss_info", schema = "misu_file_server")
public class RssInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rss_id", nullable = false)
    private Long id;

    @Size(max = 1000)
    @NotNull
    @Column(name = "rss_url", nullable = false, length = 1000)
    private String rssUrl;

    @Size(max = 200)
    @Column(name = "rss_name", length = 200)
    private String rssName;

    @Size(max = 1000)
    @NotNull
    @Column(name = "download_path", nullable = false, length = 1000)
    private String downloadPath;

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

    @NotNull
    @Column(name = "state", nullable = false)
    private Integer state;

}