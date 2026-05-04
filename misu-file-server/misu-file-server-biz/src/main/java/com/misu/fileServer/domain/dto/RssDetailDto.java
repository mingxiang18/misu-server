package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.persistence.Column;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * rss订阅详情
 *
 * @author misu
 */
@Data
public class RssDetailDto {

    @ApiModelProperty("rss订阅id")
    private Long id;

    @ApiModelProperty("rss订阅地址")
    private String rssUrl;

    @ApiModelProperty("rss订阅名称")
    private String rssName;

    @ApiModelProperty("rss订阅下载的路径")
    private String downloadPath;

    @ApiModelProperty("rss订阅状态，0-未知，1-可用，99-不可用")
    private Integer state;

    @ApiModelProperty("备注")
    private String remark;

    @ApiModelProperty("订阅下的torrent列表")
    private List<RssTorrentRelationDto> rssTorrentRelationList;

    @ApiModelProperty("订阅规则列表")
    private List<RssRuleDto> rssRuleList;
}
