package com.misu.ops.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConnectionPolicyTest {
    @Test
    void productionAcceptsOnlyFixedMysqlService() {
        assertDoesNotThrow(() -> DatabaseConnectionPolicy.requireAllowed(
                DatabaseConnectionPolicy.FIXED_JDBC_URL, new String[0]));
        assertThrows(IllegalStateException.class, () -> DatabaseConnectionPolicy.requireAllowed(
                "jdbc:mysql://attacker.example:3316/misu_test", new String[0]));
    }

    @Test
    void loopbackOverrideRequiresExplicitTestProfile() {
        String local = "jdbc:mysql://127.0.0.1:3316/misu_test?useSSL=false&allowMultiQueries=false";
        assertFalse(DatabaseConnectionPolicy.isLoopbackTestUrl(
                "jdbc:mysql://attacker.example:3316/misu_test"));
        assertThrows(IllegalStateException.class, () -> DatabaseConnectionPolicy.requireAllowed(local, new String[0]));
        assertDoesNotThrow(() -> DatabaseConnectionPolicy.requireAllowed(local, new String[]{"test"}));
    }
}
