package com.misu.account.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountJpaQueryFactoryConfig {
    // 注入指定数据源的 EntityManager
    @PersistenceContext(unitName = "account")
    private EntityManager entityManager;

    @Bean(name = "accountJpaQueryFactory")
    public JPAQueryFactory accountJpaQueryFactory() {
        // 使用注入的 EntityManager 创建 JPAQueryFactory
        return new JPAQueryFactory(entityManager);
    }
}
