package com.misu.chat.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群文件列表项。
 */
@Data
public class FileDto {
    private Long id;
    private String fileName;
    private String mimeType;
    private Long size;
    private String senderType;
    private String uploaderUserId;
    private String uploaderName;
    private LocalDateTime createTime;
    /** 当前用户是否可删除（群主或上传者） */
    private Boolean canDelete;
}
