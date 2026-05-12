package com.misu.fileServer.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShareStagingRequestDto {

    /** staging-root 下的相对路径（文件或目录），正斜杠分隔 */
    @NotBlank
    private String sourceStagingPath;

    /**
     * 目标虚拟路径：
     * - 共享到公共：直接是公共目录下的 virtualPath（可空表示落到公共根）
     * - 共享到用户私人：用户私人空间下的 virtualPath（可空表示落到该用户私人根）
     *
     * 留空则使用 sourceStagingPath 的文件名作为目标根。
     */
    private String targetVirtualPath;

    /** 仅 shareStagingToUser 使用；为目标用户的 userId */
    private String targetUserId;
}
