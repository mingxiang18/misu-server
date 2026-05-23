package com.misu.account.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 用户简要信息（昵称 / 头像），供跨服务展示成员、回填消息发送人用。
 */
@Data
public class UserBriefDto {

    @ApiModelProperty(value = "用户id")
    private Long userId;

    @ApiModelProperty(value = "用户名")
    private String userName;

    @ApiModelProperty(value = "昵称")
    private String nickName;

    @ApiModelProperty(value = "头像")
    private String avatar;
}
