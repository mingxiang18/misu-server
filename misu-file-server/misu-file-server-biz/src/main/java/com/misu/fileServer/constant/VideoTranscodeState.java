package com.misu.fileServer.constant;

public class VideoTranscodeState {
    public static final String NONE = "NONE";
    public static final String WAITING = "WAITING";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String TOO_LARGE = "TOO_LARGE";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    /**
     * 直通：源文件已经满足 Safari 播放条件（HEVC+hvc1+AAC+MP4+≤max-height+≤max-bitrate），
     * worker 跳过 libx265 转码，仅截预览封面；前端播放时直接拉源文件流。
     */
    public static final String PASSTHROUGH = "PASSTHROUGH";
}
