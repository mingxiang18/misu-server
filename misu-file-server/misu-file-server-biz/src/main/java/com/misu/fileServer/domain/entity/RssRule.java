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
@Table(name = "rss_rule", schema = "misu_file_server")
public class RssRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id", nullable = false)
    private Long id;

    @NotNull
    @Column(name = "rss_id", nullable = false)
    private Long rssId;

    @Size(max = 200)
    @Column(name = "rule_name", length = 200)
    private String ruleName;

    @Size(max = 1000)
    @Column(name = "include_keywords", length = 1000)
    private String includeKeywords;

    @Size(max = 1000)
    @Column(name = "exclude_keywords", length = 1000)
    private String excludeKeywords;

    @Size(max = 1000)
    @Column(name = "regex", length = 1000)
    private String regex;

    @Size(max = 1000)
    @NotNull
    @Column(name = "download_path", nullable = false, length = 1000)
    private String downloadPath;

    @NotNull
    @ColumnDefault("1")
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "auto_download", nullable = false)
    private Boolean autoDownload;

    @Size(max = 200)
    @Column(name = "remark", length = 200)
    private String remark;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}
