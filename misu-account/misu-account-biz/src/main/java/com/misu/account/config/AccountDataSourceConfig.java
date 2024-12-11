package com.misu.account.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 账号模块数据源配置类
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = "com.misu.account.repository",
    entityManagerFactoryRef = "accountEntityManagerFactory",
    transactionManagerRef = "accountTransactionManager"
)
public class AccountDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.account")
    public DataSourceProperties accountDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "accountDataSource")
    public DataSource accountDataSource() {
        return accountDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Primary
    @Bean(name = "accountEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean accountEntityManagerFactory(
            EntityManagerFactoryBuilder builder) {
        return builder
                .dataSource(accountDataSource())
                .packages("com.misu.account.domain.entity")
                .persistenceUnit("account")
                .build();
    }

    @Primary
    @Bean(name = "accountTransactionManager")
    public PlatformTransactionManager accountTransactionManager(
            @Qualifier("accountEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
