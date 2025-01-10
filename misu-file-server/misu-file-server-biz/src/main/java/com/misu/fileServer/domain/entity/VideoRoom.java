package com.misu.fileServer.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Setter
@Entity
@Table(name = "video_room", schema = "misu_file_server")
public class VideoRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Size(max = 64)
    @NotNull
    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    @Size(max = 30)
    @Column(name = "room_name", length = 30)
    private String roomName;

    @Size(max = 1000)
    @NotNull
    @Column(name = "video_path", nullable = false, length = 1000)
    private String videoPath;

    @Size(max = 64)
    @ColumnDefault("''")
    @Column(name = "creator_id", nullable = false, length = 64)
    private String creatorId;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Size(max = 500)
    @Column(name = "remark", length = 500)
    private String remark;

    @Size(max = 20)
    @ColumnDefault("'pause'")
    @Column(name = "state", nullable = false, length = 20)
    private String state;

    @ColumnDefault("'00:00:00'")
    @Column(name = "video_time", nullable = false)
    private LocalTime videoTime;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "sync_time", nullable = false)
    private LocalDateTime syncTime;

    @NotNull
    @Column(name = "expire_time", nullable = false)
    private LocalDateTime expireTime;

}