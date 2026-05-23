package com.misu.chat.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 聊天模块数据源配置：misu_chat 库，独立第二数据源 / EMF / TM。
 * 仿 com.misu.fileServer.config.FileServerDataSourceConfig。
 * 注意：file-server 那套被标 @Primary，本套非主，chat 的 service 用
 * @Transactional("chatTransactionManager") 显式指定。
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.misu.chat.repository",
        entityManagerFactoryRef = "chatEntityManagerFactory",
        transactionManagerRef = "chatTransactionManager"
)
public class ChatDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.chat")
    public DataSourceProperties chatDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "chatDataSource")
    public DataSource chatDataSource() {
        return chatDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean(name = "chatEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean chatEntityManagerFactory(
            EntityManagerFactoryBuilder builder) {
        return builder
                .dataSource(chatDataSource())
                .packages("com.misu.chat.domain.entity")
                .persistenceUnit("chat")
                .build();
    }

    @Bean(name = "chatTransactionManager")
    public PlatformTransactionManager chatTransactionManager(
            @Qualifier("chatEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
