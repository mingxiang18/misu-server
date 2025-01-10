package com.misu.fileServer.util.torrent.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TorrentInfoRequest {
    private String filter;    // Torrent 状态过滤器
    private String category;  // 分类（需要 URL 编码）
    private String tag;       // 标签（需要 URL 编码）
    private String sort;      // 排序字段
    private Boolean reverse;  // 是否启用反向排序
    private Integer limit;    // 返回的 torrent 数量限制
    private Integer offset;   // 偏移量
    private String hashes;    // 过滤 hash 值（多个 hash 用 `|` 分隔）
}

