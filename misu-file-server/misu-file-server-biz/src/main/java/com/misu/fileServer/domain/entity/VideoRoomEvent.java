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
@Table(name = "video_room_event", schema = "misu_file_server")
public class VideoRoomEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id", nullable = false)
    private Long id;

    @Size(max = 64)
    @NotNull
    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    @Size(max = 40)
    @NotNull
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "user_id")
    private Long userId;

    @Size(max = 100)
    @Column(name = "user_name", length = 100)
    private String userName;

    @Size(max = 20)
    @Column(name = "state", length = 20)
    private String state;

    @Column(name = "video_time_seconds")
    private Long videoTimeSeconds;

    @Column(name = "client_send_time")
    private Long clientSendTime;

    @Column(name = "server_receive_time")
    private Long serverReceiveTime;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
