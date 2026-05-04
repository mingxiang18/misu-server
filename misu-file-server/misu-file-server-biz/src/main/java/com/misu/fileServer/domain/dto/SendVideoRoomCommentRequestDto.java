package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送放映室评论请求
 *
 * @author misu
 */
@Data
public class SendVideoRoomCommentRequestDto {

    @ApiModelProperty("放映室id")
    @NotNull(message = "放映室id不能为空")
    private String roomId;

    @ApiModelProperty("评论内容")
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容不能超过500个字符")
    private String content;
}
