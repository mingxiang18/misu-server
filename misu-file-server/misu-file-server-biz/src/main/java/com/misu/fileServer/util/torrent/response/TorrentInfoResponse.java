package com.misu.fileServer.util.torrent.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TorrentInfoResponse {

    public static final List<String> ERROR_STATE_LIST = List.of("error", "missingFiles");
    public static final List<String> NOT_START_STATE_LIST = List.of("metaDL", "queuedDL");
    public static final List<String> DOWNLOADING_STATE_LIST = List.of("downloading", "stoppedDL", "pausedDL", "stalledDL",
            "checkingDL", "forcedDL", "allocating", "checkingResumeData", "moving");
    public static final List<String> COMPLETE_STATE_LIST = List.of("uploading", "pausedUP", "queuedUP", "stalledUP", "checkingUP", "forcedUP", "stoppedUP");

    @JsonProperty("added_on")
    @ApiModelProperty("时间（Unix Epoch）添加 torrent 的时间")
    private Long addedOn;

    @JsonProperty("amount_left")
    @ApiModelProperty("剩余下载数据（字节）")
    private Long amountLeft;

    @JsonProperty("auto_tmm")
    @ApiModelProperty("是否由自动 Torrent 管理")
    private Boolean autoTmm;

    @ApiModelProperty("文件片段的可用百分比")
    private Float availability;

    @ApiModelProperty("Torrent 分类")
    private String category;

    @JsonProperty("completed")
    @ApiModelProperty("已完成的传输数据（字节）")
    private Long completed;

    @JsonProperty("completion_on")
    @ApiModelProperty("完成时间（Unix Epoch）")
    private Long completionOn;

    @JsonProperty("content_path")
    @ApiModelProperty("Torrent 内容的绝对路径")
    private String contentPath;

    @JsonProperty("dl_limit")
    @ApiModelProperty("Torrent 下载速度限制（字节/秒），无限制为 -1")
    private Integer dlLimit;

    @JsonProperty("dlspeed")
    @ApiModelProperty("Torrent 下载速度（字节/秒）")
    private Integer dlSpeed;

    @JsonProperty("downloaded")
    @ApiModelProperty("下载的数据量（字节）")
    private Long downloaded;

    @JsonProperty("downloaded_session")
    @ApiModelProperty("本会话下载的数据量（字节）")
    private Long downloadedSession;

    @ApiModelProperty("Torrent ETA（秒）")
    private Long eta;

    @JsonProperty("f_l_piece_prio")
    @ApiModelProperty("是否优先下载第一和最后一片")
    private Boolean fLPiecePrio;

    @JsonProperty("force_start")
    @ApiModelProperty("是否启用强制启动")
    private Boolean forceStart;

    @ApiModelProperty("Torrent hash")
    private String hash;

    @JsonProperty("is_private")
    @ApiModelProperty("是否来自私有跟踪器")
    private Boolean isPrivate;

    @JsonProperty("last_activity")
    @ApiModelProperty("最后活动时间（Unix Epoch）")
    private Long lastActivity;

    @JsonProperty("magnet_uri")
    @ApiModelProperty("对应的 Magnet URI")
    private String magnetUri;

    @JsonProperty("max_ratio")
    @ApiModelProperty("最大分享比率，超过则停止上传")
    private Float maxRatio;

    @JsonProperty("max_seeding_time")
    @ApiModelProperty("最大上传时间（秒）")
    private Long maxSeedingTime;

    @ApiModelProperty("Torrent 名称")
    private String name;

    @JsonProperty("num_complete")
    @ApiModelProperty("完成的种子数量")
    private Integer numComplete;

    @JsonProperty("num_incomplete")
    @ApiModelProperty("未完成的数量")
    private Integer numIncomplete;

    @JsonProperty("num_leechs")
    @ApiModelProperty("当前下载的数量")
    private Integer numLeechs;

    @JsonProperty("num_seeds")
    @ApiModelProperty("当前上传的数量")
    private Integer numSeeds;

    @ApiModelProperty("Torrent 优先级")
    private Integer priority;

    @ApiModelProperty("Torrent 进度（百分比）")
    private Double progress;

    @JsonProperty("ratio_limit")
    @ApiModelProperty("比率限制")
    private Double ratioLimit;

    @JsonProperty("save_path")
    @ApiModelProperty("Torrent 数据保存路径")
    private String savePath;

    @JsonProperty("seeding_time")
    @ApiModelProperty("Torrent 完成后的上传时间（秒）")
    private Long seedingTime;

    @JsonProperty("seeding_time_limit")
    @ApiModelProperty("上传时间限制")
    private Long seedingTimeLimit;

    @JsonProperty("seen_complete")
    @ApiModelProperty("最后一次看到 Torrent 完成的时间")
    private Long seenComplete;

    @JsonProperty("seq_dl")
    @ApiModelProperty("是否启用顺序下载")
    private Boolean seqDl;

    @ApiModelProperty("下载的文件总大小（字节）")
    private Long size;

    @ApiModelProperty("Torrent 状态")
    private String state;

    @JsonProperty("super_seeding")
    @ApiModelProperty("是否启用超种")
    private Boolean superSeeding;

    @ApiModelProperty("Torrent 的标签（逗号分隔）")
    private String tags;

    @JsonProperty("time_active")
    @ApiModelProperty("Torrent 的活动时间（秒）")
    private Long timeActive;

    @JsonProperty("total_size")
    @ApiModelProperty("Torrent 的总文件大小（包括未选择的文件）")
    private Long totalSize;

    @ApiModelProperty("第一个可用的 Tracker（为空时无 Tracker）")
    private String tracker;

    @JsonProperty("up_limit")
    @ApiModelProperty("Torrent 上传速度限制（字节/秒），无限制为 -1")
    private Integer upLimit;

    @ApiModelProperty("上传的数据量（字节）")
    private Long uploaded;

    @JsonProperty("uploaded_session")
    @ApiModelProperty("本会话上传的数据量（字节）")
    private Long uploadedSession;

    @JsonProperty("upspeed")
    @ApiModelProperty("Torrent 上传速度（字节/秒）")
    private Integer upSpeed;

}
