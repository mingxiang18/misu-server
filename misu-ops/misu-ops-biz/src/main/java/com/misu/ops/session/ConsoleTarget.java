package com.misu.ops.session;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.console.QBittorrentUpstreamAuthService;

public enum ConsoleTarget {
    NACOS("nacos"),
    HEADLAMP("headlamp"),
    QBITTORRENT("qbittorrent");

    private final String id;

    ConsoleTarget(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Cookie scope is deliberately target-specific so both consoles coexist on api.misu.chat. */
    public String cookiePath() {
        return switch (this) {
            case NACOS -> "/nacos/";
            case HEADLAMP -> "/ops/headlamp/";
            case QBITTORRENT -> "/ops/qbittorrent/";
        };
    }

    public String url(OpsProperties properties) {
        String url = switch (this) {
            case NACOS -> properties.getNacosUrl();
            case HEADLAMP -> properties.getHeadlampUrl();
            case QBITTORRENT -> properties.getQbittorrentUrl();
        };
        if (url == null || url.isBlank()) {
            throw new ServiceException(HttpStatus.ERROR, "运维控制台尚未配置");
        }
        return url;
    }

    public String upstreamUrl(OpsProperties properties) {
        String url = switch (this) {
            case NACOS -> properties.getNacosUpstreamUrl();
            case HEADLAMP -> properties.getHeadlampUpstreamUrl();
            case QBITTORRENT -> QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL;
        };
        if (url == null || url.isBlank()) {
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游尚未配置");
        }
        return url;
    }

    public static ConsoleTarget parse(String value) {
        for (ConsoleTarget target : values()) {
            if (target.id.equalsIgnoreCase(value)) {
                return target;
            }
        }
        throw new ServiceException(HttpStatus.BAD_REQUEST, "不支持的控制台目标");
    }
}
