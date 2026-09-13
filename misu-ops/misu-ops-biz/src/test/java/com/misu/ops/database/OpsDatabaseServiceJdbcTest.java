package com.misu.ops.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.misu.ops.database.DatabaseModels.DeleteRowRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsDatabaseServiceJdbcTest {

    @Test
    void deleteBindsPrimaryKeyAndExpectedVersionAsPreparedValues() {
        List<String> sql = new ArrayList<>();
        List<Object> bound = new ArrayList<>();
        PreparedStatement statement = proxy(PreparedStatement.class, (ignored, method, args) -> {
            if ("setObject".equals(method.getName())) {
                bound.add(args[1]);
                return null;
            }
            if ("executeUpdate".equals(method.getName())) return 1;
            return defaultValue(method.getReturnType());
        });
        ResultSet tables = resultSet(List.of(row("TABLE_NAME", "users", "TABLE_TYPE", "TABLE")));
        ResultSet columns = resultSet(List.of(
                row("COLUMN_NAME", "id", "DATA_TYPE", Types.BIGINT, "TYPE_NAME", "BIGINT",
                        "NULLABLE", DatabaseMetaData.columnNoNulls, "COLUMN_DEF", null,
                        "IS_AUTOINCREMENT", "NO", "IS_GENERATEDCOLUMN", "NO"),
                row("COLUMN_NAME", "updated_at", "DATA_TYPE", Types.VARCHAR, "TYPE_NAME", "VARCHAR",
                        "NULLABLE", DatabaseMetaData.columnNullable, "COLUMN_DEF", null,
                        "IS_AUTOINCREMENT", "NO", "IS_GENERATEDCOLUMN", "NO")));
        ResultSet primaryKeys = resultSet(List.of(row("COLUMN_NAME", "id")));
        ResultSet empty = resultSet(List.of());
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (ignored, method, args) -> switch (method.getName()) {
            case "getTables" -> tables;
            case "getColumns" -> columns;
            case "getPrimaryKeys" -> primaryKeys;
            case "getIndexInfo" -> empty;
            default -> defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if ("getMetaData".equals(method.getName())) return metadata;
            if ("prepareStatement".equals(method.getName())) {
                sql.add((String) args[0]);
                return statement;
            }
            return defaultValue(method.getReturnType());
        });
        DataSource dataSource = proxy(DataSource.class, (ignored, method, args) ->
                "getConnection".equals(method.getName()) ? connection : defaultValue(method.getReturnType()));

        OpsProperties properties = new OpsProperties();
        properties.getDatabase().setEnabled(true);
        properties.getDatabase().setAllowedSchemas(List.of("misu_test"));
        ObjectProvider<DataSource> provider = new SingleDataSourceProvider(dataSource);
        OpsDatabaseService service = new OpsDatabaseService(provider, properties, new ObjectMapper());

        service.delete("misu_test", "users", "7", new DeleteRowRequest("v1"));

        assertEquals(List.of("DELETE FROM `misu_test`.`users` WHERE `id` = ? AND `updated_at` = ?"), sql);
        assertEquals(2, bound.size());
        assertEquals(7, ((Number) bound.get(0)).intValue());
        assertEquals("v1", String.valueOf(bound.get(1)));
    }

    @Test
    void unsupportedBlobIsRejectedAsBadRequestBeforeQuery() {
        ResultSet tables = resultSet(List.of(row("TABLE_NAME", "files", "TABLE_TYPE", "TABLE")));
        ResultSet columns = resultSet(List.of(
                row("COLUMN_NAME", "payload", "DATA_TYPE", Types.BLOB, "TYPE_NAME", "BLOB",
                        "COLUMN_SIZE", 1024, "DECIMAL_DIGITS", 0,
                        "NULLABLE", DatabaseMetaData.columnNullable, "COLUMN_DEF", null,
                        "IS_AUTOINCREMENT", "NO", "IS_GENERATEDCOLUMN", "NO")));
        ResultSet empty = resultSet(List.of());
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (ignored, method, args) -> switch (method.getName()) {
            case "getTables" -> tables;
            case "getColumns" -> columns;
            case "getPrimaryKeys", "getIndexInfo" -> empty;
            default -> defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if ("getMetaData".equals(method.getName())) return metadata;
            if ("prepareStatement".equals(method.getName())) throw new AssertionError("query must not execute");
            return defaultValue(method.getReturnType());
        });
        DataSource dataSource = proxy(DataSource.class, (ignored, method, args) ->
                "getConnection".equals(method.getName()) ? connection : defaultValue(method.getReturnType()));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(dataSource).rows("misu_test", "files", 1, 10, null, "asc", null));

        assertEquals(400, exception.getCode());
    }

    private static OpsDatabaseService service(DataSource dataSource) {
        OpsProperties properties = new OpsProperties();
        properties.getDatabase().setEnabled(true);
        properties.getDatabase().setAllowedSchemas(List.of("misu_test"));
        return new OpsDatabaseService(new SingleDataSourceProvider(dataSource), properties, new ObjectMapper());
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        return proxy(ResultSet.class, new java.lang.reflect.InvocationHandler() {
            int index = -1;

            @Override
            public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] args) {
                if ("next".equals(method.getName())) return ++index < rows.size();
                if (index < 0 || index >= rows.size()) return defaultValue(method.getReturnType());
                if ("getString".equals(method.getName()) || "getObject".equals(method.getName())
                        || "getInt".equals(method.getName()) || "getBoolean".equals(method.getName())) {
                    Object value = rows.get(index).get(String.valueOf(args[0]));
                    if ("getInt".equals(method.getName())) return value == null ? 0 : ((Number) value).intValue();
                    if ("getBoolean".equals(method.getName())) return Boolean.TRUE.equals(value);
                    return value;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private record SingleDataSourceProvider(DataSource dataSource) implements ObjectProvider<DataSource> {
        @Override public DataSource getObject() { return dataSource; }
        @Override public DataSource getObject(Object... args) { return dataSource; }
        @Override public DataSource getIfAvailable() { return dataSource; }
        @Override public DataSource getIfUnique() { return dataSource; }
        @Override public DataSource getIfAvailable(Supplier<DataSource> supplier) { return dataSource; }
        @Override public DataSource getIfUnique(Supplier<DataSource> supplier) { return dataSource; }
    }
}
