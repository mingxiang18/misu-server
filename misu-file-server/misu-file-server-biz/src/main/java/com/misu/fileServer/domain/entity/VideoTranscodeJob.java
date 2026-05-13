package com.misu.fileServer.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 视频转码任务 DB 镜像。
 *
 * <p>worker / queue 仍以磁盘 .task / status.json 为事实源；本表用于：</p>
 * <ul>
 *   <li>分页 / 状态筛选 / 关键字搜索；</li>
 *   <li>保留转码任务对应的源文件 openType / userId / virtualPath，方便管理员定位；</li>
 *   <li>记录 retry / re-transcode 次数。</li>
 * </ul>
 *
 * <p>因为 taskId 为 MD5 hex（32 字符），直接作为主键存储，简化 enqueue / reconcile。</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "video_transcode_job",
        schema = "misu_file_server",
        indexes = {
                @Index(name = "idx_vtj_state_update", columnList = "state,update_time"),
                @Index(name = "idx_vtj_queue_update", columnList = "queue_state,update_time"),
                @Index(name = "idx_vtj_source_user", columnList = "source_open_type,source_user_id")
        }
)
public class VideoTranscodeJob {

    @Id
    @NotNull
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @NotNull
    @Column(name = "source_path", nullable = false, length = 1200)
    private String sourcePath;

    @Column(name = "source_open_type")
    private Integer sourceOpenType;

    @Column(name = "source_user_id", length = 64)
    private String sourceUserId;

    @Column(name = "source_virtual_path", length = 1200)
    private String sourceVirtualPath;

    @Column(name = "output_path", length = 1200)
    private String outputPath;

    @Column(name = "preview_path", length = 1200)
    private String previewPath;

    @Column(name = "profile_version", length = 64)
    private String profileVersion;

    /** {@link com.misu.fileServer.constant.VideoTranscodeState} 字符串值。 */
    @NotNull
    @ColumnDefault("'NONE'")
    @Column(name = "state", nullable = false, length = 32)
    private String state;

    /** WAITING / RUNNING / FAILED / DONE / UNKNOWN —— 由 worker 队列目录推断。 */
    @NotNull
    @ColumnDefault("'UNKNOWN'")
    @Column(name = "queue_state", nullable = false, length = 32)
    private String queueState;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "progress", nullable = false)
    private Integer progress = 0;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @NotNull
    @ColumnDefault("1")
    @Column(name = "enqueue_count", nullable = false)
    private Integer enqueueCount = 1;

    /**
     * 是否被设为最优先：worker 按 .task 文件名升序拾取，priority=true 的任务在队列中带 "!priority-" 前缀，
     * 保证在常规 taskId 之前被领取。
     */
    @NotNull
    @ColumnDefault("0")
    @Column(name = "priority", nullable = false)
    private Boolean priority = false;

    @Column(name = "last_enqueued_at")
    private LocalDateTime lastEnqueuedAt;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
