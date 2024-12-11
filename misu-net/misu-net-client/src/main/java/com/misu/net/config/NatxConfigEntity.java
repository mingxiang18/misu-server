package com.misu.net.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * natx代理配置实体
 */
@Slf4j
@Data
public class NatxConfigEntity {

    /**
     * 是否开启
     */
    private boolean enable = false;

    /**
     * Natx服务端地址
     */
    private String serverAddress;

    /**
     * Natx服务端端口
     */
    private int serverPort;

    /**
     * Natx服务端访问密钥
     */
    private String serverSecret;

    /**
     * 代理的客户端本地访问地址
     */
    private String proxyAddress;

    /**
     * 代理的客户端本地访问端口
     */
    private int proxyPort;

    /**
     * 映射到服务端提供远程访问的端口
     */
    private int remotePort;

    /**
     * 失败重连等待时间（毫秒）
     */
    private int reconnectTime = 1;

    /**
     * tcp连接数量
     */
    private int connectNum = 1;
}
