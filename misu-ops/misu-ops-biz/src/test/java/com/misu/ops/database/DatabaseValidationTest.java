package com.misu.ops.database;

import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import com.misu.ops.database.DatabaseModels.ColumnDto;
import com.misu.ops.database.DatabaseModels.ColumnRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseValidationTest {

    @Test
    void identifiersRejectSqlFragmentsReservedWordsAndNonAscii() {
        assertThrows(ServiceException.class, () -> DatabaseValidation.identifier("orders;DROP", "表名"));
        assertThrows(ServiceException.class, () -> DatabaseValidation.identifier("select", "字段名"));
        assertThrows(ServiceException.class, () -> DatabaseValidation.identifier("订单", "字段名"));
        assertEquals("`orders_1`", DatabaseValidation.quote("orders_1"));
    }

    @Test
    void schemaMustBeConfiguredAndTypesAreStrictlyBounded() {
        assertEquals("misu_test", DatabaseValidation.schema("misu_test", Set.of("misu_test")));
        assertThrows(ServiceException.class, () -> DatabaseValidation.schema("misu_prod", Set.of("misu_test")));
        assertEquals("VARCHAR(120)", DatabaseValidation.type("varchar(120)"));
        assertEquals("DECIMAL(12,2)", DatabaseValidation.type("decimal(12,2)"));
        assertThrows(ServiceException.class, () -> DatabaseValidation.type("VARCHAR(99999)"));
        assertThrows(ServiceException.class, () -> DatabaseValidation.type("INT DEFAULT 1"));
    }

    @Test
    void ddlDefaultsAllowOnlySafeLiterals() {
        assertEquals("'O''Reilly'", DatabaseValidation.defaultLiteral("O'Reilly", "VARCHAR(120)"));
        assertEquals("'C:\\\\tmp''x'", DatabaseValidation.defaultLiteral("C:\\tmp'x", "VARCHAR(120)"));
        assertEquals("CURRENT_TIMESTAMP", DatabaseValidation.defaultLiteral("CURRENT_TIMESTAMP", "DATETIME"));
        assertThrows(ServiceException.class,
                () -> DatabaseValidation.defaultLiteral("1); DROP TABLE users; --", "VARCHAR(120)"));
        assertThrows(ServiceException.class,
                () -> DatabaseValidation.defaultLiteral("CURRENT_TIMESTAMP", "VARCHAR(120)"));
    }

    @Test
    void columnDtoMarksGeneratedColumnsReadOnlyAndRequestKeepsLegacyShape() {
        ColumnDto generated = new ColumnDto("id", java.sql.Types.BIGINT, "BIGINT", false,
                false, true, false, true);
        assertEquals(true, generated.readOnly());
        ColumnRequest legacy = new ColumnRequest("id", "BIGINT", false, null, true);
        assertEquals(false, legacy.autoIncrement());
    }
}
