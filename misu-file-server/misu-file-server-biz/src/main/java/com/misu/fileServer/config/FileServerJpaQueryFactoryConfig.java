package com.misu.fileServer.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileServerJpaQueryFactoryConfig {
    // 注入指定数据源的 EntityManager
    @PersistenceContext(unitName = "fileServer")
    private EntityManager entityManager;

    @Bean(name = "fileServerJpaQueryFactory")
    public JPAQueryFactory fileServerJpaQueryFactory() {
        // 使用注入的 EntityManager 创建 JPAQueryFactory
        return new JPAQueryFactory(entityManager);
    }
}
