package com.misu.ops.database;

import com.misu.ops.OpsProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpsDatabaseConfigurationTest {
    @Test
    void enabledDatabaseLoadsMysqlDriverWithoutOpeningConnection() throws Exception {
        OpsProperties properties = new OpsProperties();
        properties.getDatabase().setEnabled(true);
        properties.getDatabase().setUsername("test-user");
        properties.getDatabase().setPassword("test-password");

        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        try (HikariDataSource dataSource = (HikariDataSource) new OpsDatabaseConfiguration()
                .opsDatabaseDataSource(properties, environment)) {
            assertEquals(DatabaseConnectionPolicy.FIXED_JDBC_URL, dataSource.getJdbcUrl());
            assertNotNull(DriverManager.getDriver(DatabaseConnectionPolicy.FIXED_JDBC_URL));
        }
    }
}
