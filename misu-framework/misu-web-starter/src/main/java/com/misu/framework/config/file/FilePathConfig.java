package com.misu.framework.config.file;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 文件路径配置
 * @author misu
 */
@Data
@Configuration
public class FilePathConfig {

    @Value("${misu.static.path}")
    private String filePath;
}
