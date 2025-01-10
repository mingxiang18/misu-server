package com.misu.fileServer.util.torrent.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class AddTorrentRequest {

    @JsonProperty("urls")
    @ApiModelProperty("URLs，多个链接使用换行\n分隔")
    private String urls;

    @JsonProperty("torrents")
    @ApiModelProperty("Torrent 文件的二进制数据，可以多次提交, 这里是 raw 类型，表示二进制数据，通常会是 Base64 或者文件字节流")
    private MultipartFile torrents;

    @JsonProperty("savepath")
    @ApiModelProperty("下载的目标文件夹路径")
    private String savePath;

    @JsonProperty("cookie")
    @ApiModelProperty("下载 .torrent 文件时的 Cookie")
    private String cookie;

    @JsonProperty("category")
    @ApiModelProperty("Torrent 的分类")
    private String category;

    @JsonProperty("tags")
    @ApiModelProperty("Torrent 的标签，多个标签使用逗号分隔")
    private String tags;

    @JsonProperty("skip_checking")
    @ApiModelProperty("是否跳过 hash 检查")
    private String skipChecking;

    @JsonProperty("paused")
    @ApiModelProperty("是否将 torrent 添加到暂停状态")
    private Boolean paused;

    @JsonProperty("root_folder")
    @ApiModelProperty("是否创建根文件夹")
    private String rootFolder;

    @JsonProperty("rename")
    @ApiModelProperty("重命名 Torrent")
    private String rename;

    @JsonProperty("upLimit")
    @ApiModelProperty("设置上传速度限制，单位为字节/秒")
    private Integer upLimit;

    @JsonProperty("dlLimit")
    @ApiModelProperty("设置下载速度限制，单位为字节/秒")
    private Integer dlLimit;

    @JsonProperty("ratioLimit")
    @ApiModelProperty("设置 Torrent 分享比率限制")
    private Float ratioLimit;

    @JsonProperty("seedingTimeLimit")
    @ApiModelProperty("设置 Torrent 的上传时间限制，单位为分钟")
    private Integer seedingTimeLimit;

    @JsonProperty("autoTMM")
    @ApiModelProperty("是否启用自动 Torrent 管理")
    private Boolean autoTMM;

    @JsonProperty("sequentialDownload")
    @ApiModelProperty("是否启用顺序下载")
    private Boolean sequentialDownload;

    @JsonProperty("firstLastPiecePrio")
    @ApiModelProperty("是否优先下载第一和最后一片")
    private Boolean firstLastPiecePrio;
}
