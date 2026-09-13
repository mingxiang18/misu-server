package com.misu.ops.database;

import com.misu.ops.OpsProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Configuration
public class OpsDatabaseConfiguration {

    @Bean(name = "opsDatabaseDataSource", destroyMethod = "close")
    @Conditional(DatabaseConfiguredCondition.class)
    public DataSource opsDatabaseDataSource(OpsProperties properties) {
        OpsProperties.DatabaseProperties database = properties.getDatabase();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.getUrl());
        config.setUsername(database.getUsername());
        config.setPassword(database.getPassword());
        config.setPoolName("misu-ops-database");
        config.setMaximumPoolSize(Math.max(1, Math.min(4, database.getMaximumPoolSize())));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Math.max(250, database.getConnectionTimeoutMillis()));
        config.setIdleTimeout(Math.max(10_000, database.getIdleTimeoutMillis()));
        config.setValidationTimeout(Math.min(Math.max(250, database.getConnectionTimeoutMillis()), 1000));
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("allowMultiQueries", "false");
        config.addDataSourceProperty("useLocalSessionState", "true");
        return new HikariDataSource(config);
    }

    @Bean(name = "opsDatabaseTransactionManager")
    public PlatformTransactionManager opsDatabaseTransactionManager(
            @Qualifier("opsDatabaseDataSource") org.springframework.beans.factory.ObjectProvider<DataSource> provider) {
        DataSource dataSource = provider.getIfAvailable();
        return dataSource == null ? new UnavailableTransactionManager() : new DataSourceTransactionManager(dataSource);
    }

    private static final class UnavailableTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    }

    static final class DatabaseConfiguredCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String enabled = context.getEnvironment().getProperty("ops.database.enabled", "false");
            return Boolean.parseBoolean(enabled)
                    && hasText(context.getEnvironment().getProperty("ops.database.url"))
                    && hasText(context.getEnvironment().getProperty("ops.database.username"))
                    && hasText(context.getEnvironment().getProperty("ops.database.password"));
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
