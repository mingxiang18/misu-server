package com.misu.net.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * nat代理工具配置map
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "natx")
public class NatxConfigMap {

    private Map<String, NatxConfigEntity> natxConfigMap = new HashMap<>();
}
