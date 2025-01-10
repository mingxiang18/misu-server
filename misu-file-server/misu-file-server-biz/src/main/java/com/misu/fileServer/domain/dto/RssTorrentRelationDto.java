package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.DateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * rss订阅详情
 *
 * @author misu
 */
@Data
public class RssTorrentRelationDto {

    @ApiModelProperty("标题")
    private String title;

    @ApiModelProperty("torrent的hash值")
    private String torrentHash;

    @ApiModelProperty("磁力链接")
    private String torrentUrl;

    @ApiModelProperty("描述")
    private String description;

    @ApiModelProperty("发布时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishDate;

    @ApiModelProperty("更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updatedDate;

    @ApiModelProperty("发布者")
    private String author;

    @ApiModelProperty("下载状态，0-不存在下载记录，1-存在下载记录")
    private Integer downloadState = 0;
}
