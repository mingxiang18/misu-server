package com.misu.fileServer.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * staging 物理目录条目（不依赖 file_mapping，直接 Files.list 读盘）。
 */
@Data
public class StagingEntryDto {

    /** 名称（不含路径） */
    private String name;

    /** 相对 staging-root 的路径，正斜杠分隔，不带前导 / */
    private String relativePath;

    /** 是否目录 */
    private Boolean directory;

    /** 文件字节数（目录可返回 0） */
    private Long size;

    /** 最后修改时间 */
    private LocalDateTime lastModified;
}
