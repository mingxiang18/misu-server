package com.misu.ops.session;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;

public enum ConsoleTarget {
    NACOS("nacos"),
    HEADLAMP("headlamp");

    private final String id;

    ConsoleTarget(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String url(OpsProperties properties) {
        String url = this == NACOS ? properties.getNacosUrl() : properties.getHeadlampUrl();
        if (url == null || url.isBlank()) {
            throw new ServiceException(HttpStatus.ERROR, "运维控制台尚未配置");
        }
        return url;
    }

    public String upstreamUrl(OpsProperties properties) {
        String url = this == NACOS ? properties.getNacosUpstreamUrl() : properties.getHeadlampUpstreamUrl();
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
