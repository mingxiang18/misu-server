package com.misu.net.config;

import com.misu.net.NatxClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

/**
 * nat代理工具配置map
 */
@Slf4j
@Configuration
public class NatxConfig {

    @Resource
    private NatxConfigMap natxConfigMap;

    @PostConstruct
    public void startNaxtConnect() throws IOException, InterruptedException {
        for (Map.Entry<String, NatxConfigEntity> natxConfigEntry : natxConfigMap.getNatxConfigMap().entrySet()) {
            NatxConfigEntity natxConfigEntity = natxConfigEntry.getValue();
            //如果是开启状态，连接nat代理服务端
            if (natxConfigEntity.isEnable()) {
                NatxClient client = new NatxClient();
                for (int i = 0; i < natxConfigEntity.getConnectNum(); i++) {;
                    client.connect(natxConfigEntity);
                }
            }
        }
    }
}
