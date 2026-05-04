package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "rss_item", schema = "misu_file_server")
public class RssItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id", nullable = false)
    private Long id;

    @NotNull
    @Column(name = "rss_id", nullable = false)
    private Long rssId;

    @Size(max = 1000)
    @Column(name = "guid", length = 1000)
    private String guid;

    @Size(max = 1000)
    @NotNull
    @Column(name = "title", nullable = false, length = 1000)
    private String title;

    @Size(max = 2000)
    @Column(name = "torrent_url", length = 2000)
    private String torrentUrl;

    @Size(max = 64)
    @Column(name = "torrent_hash", length = 64)
    private String torrentHash;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Size(max = 200)
    @Column(name = "author", length = 200)
    private String author;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "match_state", nullable = false)
    private Integer matchState;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "download_state", nullable = false)
    private Integer downloadState;

    @Column(name = "matched_rule_id")
    private Long matchedRuleId;

    @Size(max = 200)
    @Column(name = "error_message", length = 200)
    private String errorMessage;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}
