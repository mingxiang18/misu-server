package com.misu.ops.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.misu.ops.database.DatabaseModels.*;

@Slf4j
@Service
public class OpsDatabaseService {
    private static final int MAX_PAGE = 1_000;
    private static final int MAX_FILTERS = 10;
    private static final int MAX_VALUE_BYTES = 512;
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "like", "prefix", "gt", "gte", "lt", "lte", "isNull");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final OpsProperties.DatabaseProperties properties;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrency;

    public OpsDatabaseService(@Qualifier("opsDatabaseDataSource") ObjectProvider<DataSource> dataSourceProvider,
                              OpsProperties opsProperties, ObjectMapper objectMapper) {
        this.dataSource = dataSourceProvider.getIfAvailable();
        this.jdbcTemplate = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.properties = opsProperties.getDatabase();
        this.objectMapper = objectMapper;
        this.concurrency = new Semaphore(Math.max(1, Math.min(8, properties.getMaxConcurrent())));
    }

    public List<DatabaseCatalogDto> catalogs() {
        requireReady();
        Set<String> allowed = allowedSchemas();
        return withConnection(connection -> {
            try {
                Set<String> actual = new LinkedHashSet<>();
                try (ResultSet rs = connection.getMetaData().getCatalogs()) {
                    while (rs.next()) {
                        actual.add(rs.getString("TABLE_CAT"));
                    }
                }
                return allowed.stream().filter(actual::contains)
                        .sorted().map(name -> new DatabaseCatalogDto(name, name)).toList();
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    public List<DatabaseTableDto> tables(String database) {
        String schema = validateSchema(database);
        return withConnection(connection -> {
            try {
                List<DatabaseTableDto> result = new ArrayList<>();
                DatabaseMetaData metadata = connection.getMetaData();
                try (ResultSet rs = metadata.getTables(schema, null, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        if (!validMetadataIdentifier(name)) {
                            continue;
                        }
                        TableInfo table = readTableInfo(metadata, schema, name);
                        result.add(new DatabaseTableDto(name, safe(rs.getString("REMARKS")), null,
                                table.primaryKeyMode(), table.tableType()));
                    }
                }
                return result;
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    public TableMetadataDto metadata(String database, String table) {
        TableInfo info = tableInfo(database, table);
        return new TableMetadataDto(info.database, info.table,
                info.columns.stream().map(ColumnInfo::dto).toList(),
                info.indexes.stream().map(IndexInfo::dto).toList(), info.writable(), info.primaryKey(), info.tableType());
    }

    public PageDto<RowDto> rows(String database, String table, int page, int pageSize,
                                String sort, String order, String filterJson) {
        if (page < 1 || page > MAX_PAGE || pageSize < 1 || pageSize > Math.min(100, properties.getMaxPageSize())) {
            throw invalid("分页参数超出范围");
        }
        TableInfo info = tableInfo(database, table);
        ensureSupportedColumns(info);
        String sortColumn = sort;
        if (sortColumn == null || sortColumn.isBlank()) {
            sortColumn = info.primaryKey != null ? info.primaryKey : info.columns.get(0).name;
        }
        ColumnInfo sortInfo = info.column(sortColumn);
        if (sortInfo == null) {
            throw notAllowed("排序字段不在元数据中");
        }
        String direction = "desc".equalsIgnoreCase(order) ? "DESC" : "asc".equalsIgnoreCase(order) || order == null || order.isBlank() ? "ASC" : null;
        if (direction == null) {
            throw invalid("排序方向无效");
        }
        List<FilterRequest> filters = parseFilters(filterJson);
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(info.columns.stream().map(c -> DatabaseValidation.quote(c.name)).collect(Collectors.joining(", ")))
                .append(" FROM ").append(DatabaseValidation.quote(info.database)).append(".").append(DatabaseValidation.quote(info.table));
        if (!filters.isEmpty()) {
            sql.append(" WHERE ");
            List<String> predicates = new ArrayList<>();
            for (FilterRequest filter : filters) {
                ColumnInfo column = info.column(filter.column());
                if (column == null) {
                    throw notAllowed("筛选字段不在元数据中");
                }
                String operator = filter.operator();
                if (operator == null || !OPERATORS.contains(operator)) {
                    throw invalid("筛选条件无效");
                }
                String identifier = DatabaseValidation.quote(column.name);
                if ("isNull".equals(operator)) {
                    if (filter.value() != null) {
                        throw invalid("isNull 不接受值");
                    }
                    predicates.add(identifier + " IS NULL");
                } else {
                    if (filter.value() == null || jsonBytes(filter.value()) > MAX_VALUE_BYTES) {
                        throw invalid("筛选值无效");
                    }
                    Object value = convertValue(filter.value(), column);
                    switch (operator) {
                        case "eq" -> predicates.add(identifier + " = ?");
                        case "ne" -> predicates.add(identifier + " <> ?");
                        case "gt" -> predicates.add(identifier + " > ?");
                        case "gte" -> predicates.add(identifier + " >= ?");
                        case "lt" -> predicates.add(identifier + " < ?");
                        case "lte" -> predicates.add(identifier + " <= ?");
                        case "like" -> {
                            predicates.add(identifier + " LIKE ? ESCAPE '\\\\'");
                            value = escapeLike(String.valueOf(value));
                        }
                        case "prefix" -> {
                            predicates.add(identifier + " LIKE ? ESCAPE '\\\\'");
                            value = escapeLike(String.valueOf(value)) + "%";
                        }
                        default -> throw invalid("筛选条件无效");
                    }
                    parameters.add(value);
                }
            }
            sql.append(String.join(" AND ", predicates));
        }
        int fetch = pageSize + 1;
        int offset = (page - 1) * pageSize;
        sql.append(" ORDER BY ").append(DatabaseValidation.quote(sortInfo.name)).append(" ").append(direction)
                .append(" LIMIT ? OFFSET ?");
        parameters.add(fetch);
        parameters.add(offset);
        List<RowDto> result = executeQuery(sql.toString(), parameters, info, fetch);
        boolean hasNext = result.size() > pageSize;
        if (hasNext) {
            result = new ArrayList<>(result.subList(0, pageSize));
        }
        return new PageDto<>(result, page, pageSize, null, hasNext);
    }

    @Transactional(transactionManager = "opsDatabaseTransactionManager")
    public RowDto create(String database, String table, Map<String, Object> values) {
        TableInfo info = writableTable(database, table);
        Map<String, Object> supplied = checkedValues(values, info, false);
        if (supplied.isEmpty()) {
            throw invalid("至少提供一个字段");
        }
        List<String> columns = new ArrayList<>(supplied.keySet());
        String sql = "INSERT INTO " + qualified(info) + " (" + columns.stream().map(DatabaseValidation::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        Object[] generatedKey = {supplied.get(info.primaryKey)};
        int affected = insert(sql, columns.stream().map(name -> convertValue(supplied.get(name), info.column(name))).toList(),
                info.primaryKey, info.column(info.primaryKey).autoIncrement, key -> generatedKey[0] = key);
        requireOneAffected(affected);
        audit("insert", info, columns, affected);
        return generatedKey[0] == null ? null : findByPrimaryKey(info, generatedKey[0]);
    }

    @Transactional(transactionManager = "opsDatabaseTransactionManager")
    public RowDto update(String database, String table, String primaryKey, UpdateRowRequest request) {
        TableInfo info = writableTable(database, table);
        Object key = convertPrimaryKey(primaryKey, info);
        Map<String, Object> supplied = checkedValues(request == null ? null : request.values(), info, true);
        if (supplied.isEmpty()) {
            throw invalid("至少提供一个字段");
        }
        List<String> columns = new ArrayList<>(supplied.keySet());
        StringBuilder sql = new StringBuilder("UPDATE ").append(qualified(info)).append(" SET ");
        sql.append(columns.stream().map(name -> DatabaseValidation.quote(name) + " = ?").collect(Collectors.joining(", ")))
                .append(" WHERE ").append(DatabaseValidation.quote(info.primaryKey)).append(" = ?");
        List<Object> params = columns.stream().map(name -> convertValue(supplied.get(name), info.column(name))).collect(Collectors.toCollection(ArrayList::new));
        params.add(key);
        if (request.expectedRowVersion() != null) {
            if (info.versionColumn == null) {
                throw invalid("该表不支持版本校验");
            }
            sql.append(" AND ").append(DatabaseValidation.quote(info.versionColumn.name)).append(" = ?");
            params.add(convertValue(request.expectedRowVersion(), info.versionColumn));
        }
        int affected = update(sql.toString(), params);
        requireOneAffected(affected);
        audit("update", info, columns, affected);
        return findByPrimaryKey(info, key);
    }

    @Transactional(transactionManager = "opsDatabaseTransactionManager")
    public void delete(String database, String table, String primaryKey) {
        delete(database, table, primaryKey, null);
    }

    @Transactional(transactionManager = "opsDatabaseTransactionManager")
    public void delete(String database, String table, String primaryKey, DeleteRowRequest request) {
        TableInfo info = writableTable(database, table);
        Object key = convertPrimaryKey(primaryKey, info);
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(qualified(info)).append(" WHERE ")
                .append(DatabaseValidation.quote(info.primaryKey)).append(" = ?");
        List<Object> params = new ArrayList<>(List.of(key));
        if (request != null && request.expectedRowVersion() != null) {
            if (info.versionColumn == null) {
                throw invalid("该表不支持版本校验");
            }
            sql.append(" AND ").append(DatabaseValidation.quote(info.versionColumn.name)).append(" = ?");
            params.add(convertValue(request.expectedRowVersion(), info.versionColumn));
        }
        int affected = update(sql.toString(), params);
        requireOneAffected(affected);
        audit("delete", info, List.of(info.primaryKey), affected);
    }

    public TableMetadataDto createTable(String database, CreateTableRequest request) {
        String schema = validateSchema(database);
        if (request == null || request.name() == null || request.columns() == null || request.columns().isEmpty()
                || request.columns().size() > 64) {
            throw ddlRejected("表结构无效");
        }
        String table = DatabaseValidation.identifier(request.name(), "表名");
        if (tableInfoOrNull(schema, table) != null) {
            throw ddlRejected("表已存在");
        }
        DatabaseValidation.comment(request.comment());
        Set<String> names = new LinkedHashSet<>();
        int primaryKeys = 0;
        int autoIncrementColumns = 0;
        List<String> definitions = new ArrayList<>();
        for (ColumnRequest column : request.columns()) {
            if (column == null || !names.add(DatabaseValidation.identifier(column.name(), "字段名"))) {
                throw ddlRejected("字段定义无效");
            }
            String type = DatabaseValidation.type(column.type());
            if (column.primaryKey()) {
                primaryKeys++;
                if (primaryKeys > 1) {
                    throw ddlRejected("只能有一个主键");
                }
            }
            if (column.autoIncrement() && ++autoIncrementColumns > 1) {
                throw ddlRejected("只能有一个自增字段");
            }
            if (column.autoIncrement() && (!column.primaryKey()
                    || !Set.of("TINYINT", "INT", "BIGINT").contains(type))) {
                throw ddlRejected("自增字段必须是整数主键");
            }
            definitions.add(columnDefinition(column.name(), type, column.nullable(), column.defaultValue(),
                    column.primaryKey(), column.autoIncrement()));
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(DatabaseValidation.quote(schema)).append(".").append(DatabaseValidation.quote(table))
                .append(" (").append(String.join(", ", definitions)).append(")");
        if (request.comment() != null) {
            sql.append(" COMMENT ").append(stringLiteral(request.comment()));
        }
        try {
            executeDdl(sql.toString());
        } catch (ServiceException ex) {
            TableInfo observed = rereadAfterDdlFailure(schema, table);
            if (createMatches(observed, request.columns())) return metadata(schema, table);
            throw ex;
        }
        TableInfo created = tableInfoOrNull(schema, table);
        if (!createMatches(created, request.columns())) {
            TableInfo observed = rereadAfterDdlFailure(schema, table);
            if (createMatches(observed, request.columns())) return metadata(schema, table);
            throw ddlRejected("建表结果与元数据不一致");
        }
        return metadata(schema, table);
    }

    public TableMetadataDto addColumn(String database, String table, AddColumnRequest request) {
        String schema = validateSchema(database);
        String existing = DatabaseValidation.identifier(table, "表名");
        TableInfo info = tableInfo(schema, existing);
        if (info.view || request == null) {
            throw ddlRejected("字段定义无效");
        }
        String column = DatabaseValidation.identifier(request.name(), "字段名");
        if (info.column(column) != null || "PRIMARY".equalsIgnoreCase(request.position())) {
            throw ddlRejected("字段位置无效");
        }
        String type = DatabaseValidation.type(request.type());
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualified(info)).append(" ADD COLUMN ")
                .append(columnDefinition(column, type, request.nullable(), request.defaultValue(), false, false));
        if (request.position() != null && !request.position().isBlank()) {
            if ("FIRST".equalsIgnoreCase(request.position())) {
                sql.append(" FIRST");
            } else {
                ColumnInfo after = info.column(request.position());
                if (after == null) {
                    throw ddlRejected("字段位置无效");
                }
                sql.append(" AFTER ").append(DatabaseValidation.quote(after.name));
            }
        }
        try {
            executeDdl(sql.toString());
        } catch (ServiceException ex) {
            TableInfo observed = rereadAfterDdlFailure(schema, existing);
            if (addColumnMatches(observed, request, column, type)) return metadata(schema, existing);
            throw ex;
        }
        TableInfo changed = tableInfoOrNull(schema, existing);
        if (!addColumnMatches(changed, request, column, type)) {
            TableInfo observed = rereadAfterDdlFailure(schema, existing);
            if (addColumnMatches(observed, request, column, type)) return metadata(schema, existing);
            throw ddlRejected("加字段结果与元数据不一致");
        }
        return metadata(schema, existing);
    }

    private TableInfo writableTable(String database, String table) {
        TableInfo info = tableInfo(database, table);
        ensureSupportedColumns(info);
        if (info.view || !"SINGLE".equals(info.primaryKeyMode()) || info.primaryKey == null) {
            throw DatabaseValidation.error(HttpStatus.FORBIDDEN, "OPS_DB_TABLE_READ_ONLY", "该表只读");
        }
        return info;
    }

    private TableInfo tableInfo(String database, String table) {
        String schema = validateSchema(database);
        DatabaseValidation.identifier(table, "表名");
        TableInfo info = tableInfoOrNull(schema, table);
        if (info == null) {
            throw notAllowed("表不在允许范围内");
        }
        return info;
    }

    private TableInfo tableInfoOrNull(String schema, String table) {
        return withConnection(connection -> {
            try {
                TableInfo info = readTableInfo(connection.getMetaData(), schema, table);
                return info;
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    private TableInfo readTableInfo(DatabaseMetaData metadata, String database, String table) throws SQLException {
        boolean exists = false;
        boolean view = false;
        String tableType = "TABLE";
        String comment = null;
        try (ResultSet rs = metadata.getTables(database, null, table, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME"))) {
                    exists = true;
                    comment = rs.getString("REMARKS");
                    tableType = rs.getString("TABLE_TYPE");
                    view = "VIEW".equalsIgnoreCase(tableType);
                    break;
                }
            }
        }
        if (!exists) {
            return null;
        }
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(database, null, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (validMetadataIdentifier(name)) {
                    String typeName = safe(rs.getString("TYPE_NAME"));
                    String defaultValue = safe(rs.getString("COLUMN_DEF"));
                    columns.add(new ColumnInfo(name, rs.getInt("DATA_TYPE"), typeName, typeName,
                            rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, defaultValue,
                            defaultValue != null, "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")),
                            "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"))));
                }
            }
        }
        List<String> primaryKey = new ArrayList<>();
        try (ResultSet rs = metadata.getPrimaryKeys(database, null, table)) {
            while (rs.next()) {
                primaryKey.add(rs.getString("COLUMN_NAME"));
            }
        }
        primaryKey.sort(Comparator.comparingInt(name -> columns.indexOf(columns.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null))));
        Map<String, IndexBuilder> indexes = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(database, null, table, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String indexColumn = rs.getString("COLUMN_NAME");
                if (indexName != null && indexColumn != null && validMetadataIdentifier(indexName) && validMetadataIdentifier(indexColumn)) {
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    int ordinal = rs.getInt("ORDINAL_POSITION");
                    indexes.computeIfAbsent(indexName, ignored -> new IndexBuilder(indexName, unique))
                            .columns.put(ordinal, indexColumn);
                }
            }
        }
        List<IndexInfo> indexInfo = indexes.values().stream().map(IndexBuilder::build).toList();
        ColumnInfo version = columns.stream().filter(c -> c.name.equalsIgnoreCase("updated_at")).findFirst().orElse(null);
        return new TableInfo(database, table, comment, columns, indexInfo,
                primaryKey.size() == 1 ? primaryKey.get(0) : null,
                primaryKey.size(), version, view, view ? "VIEW" : "TABLE");
    }

    private List<RowDto> executeQuery(String sql, List<Object> parameters, TableInfo info, int maxRows) {
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                ps.setMaxRows(maxRows);
                bind(ps, parameters, null);
                try (ResultSet rs = ps.executeQuery()) {
                    List<RowDto> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (ColumnInfo column : info.columns) {
                            values.put(column.name, jsonValue(rs.getObject(column.name), column));
                        }
                        String rowVersion = info.versionColumn == null || values.get(info.versionColumn.name) == null
                                ? null : String.valueOf(values.get(info.versionColumn.name));
                        rows.add(new RowDto(values, rowVersion));
                    }
                    return rows;
                }
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    private RowDto findByPrimaryKey(TableInfo info, Object key) {
        String sql = "SELECT " + info.columns.stream().map(c -> DatabaseValidation.quote(c.name)).collect(Collectors.joining(", "))
                + " FROM " + qualified(info) + " WHERE " + DatabaseValidation.quote(info.primaryKey) + " = ?";
        List<RowDto> rows = executeQuery(sql, List.of(key), info, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int update(String sql, List<Object> values) {
        requireReady();
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                bind(ps, values, null);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    private int insert(String sql, List<Object> values, String primaryKey, boolean generated,
                       java.util.function.Consumer<Object> generatedKeyConsumer) {
        requireReady();
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                bind(ps, values, null);
                int affected = ps.executeUpdate();
                if (generated && affected == 1) {
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) generatedKeyConsumer.accept(keys.getObject(1));
                    }
                }
                return affected;
            } catch (SQLException ex) {
                throw upstream(ex);
            }
        });
    }

    private void executeDdl(String sql) {
        requireReady();
        try {
            withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                    ps.executeUpdate();
                    return null;
                } catch (SQLException ex) {
                    throw ddlRejected(ex);
                }
            });
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ddlRejected(ex);
        }
    }

    /** MySQL DDL may commit before failing; observe metadata before returning the stable error. */
    private TableInfo rereadAfterDdlFailure(String schema, String table) {
        try {
            return tableInfoOrNull(schema, table);
        } catch (RuntimeException ignored) {
            // Preserve the stable DDL error even when the follow-up metadata read is unavailable.
            return null;
        }
    }

    private boolean createMatches(TableInfo actual, List<ColumnRequest> requested) {
        if (actual == null || actual.view || actual.columns.size() != requested.size()) return false;
        for (int i = 0; i < requested.size(); i++) {
            ColumnRequest expected = requested.get(i);
            ColumnInfo found = actual.columns.get(i);
            String type = DatabaseValidation.type(expected.type());
            if (!expected.name().equals(found.name) || !columnMatches(found, expected, type)
                    || expected.primaryKey() != found.name.equals(actual.primaryKey)
                    || expected.autoIncrement() != found.autoIncrement) return false;
        }
        return true;
    }

    private boolean addColumnMatches(TableInfo actual, AddColumnRequest request, String name, String type) {
        if (actual == null) return false;
        ColumnInfo column = actual.column(name);
        return column != null && columnMatches(column, request, type);
    }

    private boolean columnMatches(ColumnInfo actual, ColumnRequest expected, String type) {
        boolean nullable = expected.nullable() && !expected.primaryKey();
        return nullable == actual.nullable && typeMatches(actual, type)
                && defaultMatches(actual.defaultValue, expected.defaultValue(), type);
    }

    private boolean columnMatches(ColumnInfo actual, AddColumnRequest expected, String type) {
        return expected.nullable() == actual.nullable && typeMatches(actual, type)
                && defaultMatches(actual.defaultValue, expected.defaultValue(), type);
    }

    private static boolean defaultMatches(String actual, Object expected, String type) {
        if (expected == null) return actual == null;
        if (actual == null) return false;
        try {
            return normalizeDefault(actual, type).equals(normalizeExpectedDefault(expected, type));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String normalizeExpectedDefault(Object value, String type) {
        String literal = DatabaseValidation.defaultLiteral(value, type);
        if (isCurrentTimestamp(literal)) return "CURRENT_TIMESTAMP";
        if (isNumericType(type)) return new BigDecimal(literal).toPlainString();
        return normalizeDefault(literal, type);
    }

    private static String normalizeDefault(String value, String type) {
        String text = value.trim();
        if (isCurrentTimestamp(text)) return "CURRENT_TIMESTAMP";
        if (isNumericType(type)) return new BigDecimal(text).toPlainString();
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            text = text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    private static boolean isCurrentTimestamp(String value) {
        return value != null && value.trim().toUpperCase(Locale.ROOT)
                .matches("CURRENT_TIMESTAMP(?:\\(\\d{1,2}\\))?");
    }

    private static boolean isNumericType(String type) {
        return Set.of("TINYINT", "INT", "BIGINT").contains(type) || type.startsWith("DECIMAL");
    }

    private static boolean typeMatches(ColumnInfo actual, String expected) {
        String actualType = actual.typeName == null ? "" : actual.typeName.toUpperCase(Locale.ROOT);
        String expectedBase = expected.substring(0, expected.indexOf('(') >= 0 ? expected.indexOf('(') : expected.length());
        if (!expectedBase.equals(actualType)) return false;
        if (expected.startsWith("VARCHAR(") || expected.startsWith("DECIMAL(")) {
            int open = expected.indexOf('(');
            int comma = expected.indexOf(',', open);
            int close = expected.indexOf(')', open);
            int expectedSize = Integer.parseInt(expected.substring(open + 1, comma >= 0 ? comma : close));
            if (actual.size != expectedSize) return false;
            if (comma >= 0 && actual.scale != Integer.parseInt(expected.substring(comma + 1, close))) return false;
        }
        return true;
    }

    private Map<String, Object> checkedValues(Map<String, Object> values, TableInfo info, boolean update) {
        if (values == null || values.size() > info.columns.size()) {
            throw invalid("字段值无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ColumnInfo column = info.column(entry.getKey());
            if (column == null || column.autoIncrement || column.generated || (update && column.name.equals(info.primaryKey))) {
                throw invalid("字段不可写");
            }
            if (entry.getValue() == null && !column.nullable) {
                throw invalid("字段不能为空");
            }
            result.put(column.name, entry.getValue());
        }
        return result;
    }

    private Object convertPrimaryKey(String value, TableInfo info) {
        if (value == null || value.length() > MAX_VALUE_BYTES) {
            throw invalid("主键无效");
        }
        return convertValue(value, info.column(info.primaryKey));
    }

    private Object convertValue(Object value, ColumnInfo column) {
        if (value == null) {
            return null;
        }
        if (column == null || jsonBytes(value) > 65536) {
            throw invalid("字段值无效");
        }
        if (!column.supported()) {
            throw invalid("字段类型不受支持");
        }
        try {
            if ("JSON".equalsIgnoreCase(column.objectType)) {
                return json(value, column);
            }
            Object converted = switch (JDBCType.valueOf(column.jdbcType)) {
                case TINYINT -> integer(value, -128, 127);
                case SMALLINT -> integer(value, Short.MIN_VALUE, Short.MAX_VALUE);
                case INTEGER -> integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
                case BIGINT -> integer(value, Long.MIN_VALUE, Long.MAX_VALUE);
                case DECIMAL, NUMERIC -> decimal(value, column);
                case BOOLEAN, BIT -> booleanValue(value);
                case DATE -> LocalDate.parse(String.valueOf(value));
                case TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> Timestamp.valueOf(localDateTime(value));
                case TIME, TIME_WITH_TIMEZONE -> java.sql.Time.valueOf(String.valueOf(value));
                case CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR, CLOB -> text(value, column);
                case OTHER -> json(value, column);
                default -> throw new IllegalArgumentException();
            };
            return converted;
        } catch (IllegalArgumentException ex) {
            throw invalid("字段值类型无效");
        }
    }

    private static Object integer(Object value, long min, long max) {
        long parsed = Long.parseLong(String.valueOf(value));
        if (parsed < min || parsed > max) {
            throw new NumberFormatException();
        }
        return parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE ? (int) parsed : parsed;
    }

    private static BigDecimal decimal(Object value, ColumnInfo column) {
        BigDecimal decimal = new BigDecimal(String.valueOf(value));
        if ((column.size > 0 && decimal.precision() > column.size)
                || (column.scale >= 0 && decimal.scale() > column.scale)) {
            throw new NumberFormatException();
        }
        return decimal;
    }

    private static String text(Object value, ColumnInfo column) {
        String text = String.valueOf(value);
        if (column.size > 0 && text.length() > column.size) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private String json(Object value, ColumnInfo column) {
        if (!"JSON".equalsIgnoreCase(column.objectType)) {
            throw new IllegalArgumentException();
        }
        String text;
        try {
            text = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            objectMapper.readTree(text);
            return text;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException();
        }
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if ("0".equals(value) || "1".equals(value)) return "1".equals(value);
        throw new IllegalArgumentException();
    }

    private static LocalDateTime localDateTime(Object value) {
        String text = String.valueOf(value);
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(text.replace(" ", "T"));
        }
    }

    private void bind(PreparedStatement ps, List<Object> values, List<ColumnInfo> columns) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) ps.setObject(i + 1, null);
            else ps.setObject(i + 1, value);
        }
    }

    private List<FilterRequest> parseFilters(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<FilterRequest> filters = objectMapper.readValue(json, new TypeReference<>() {});
            if (filters == null || filters.size() > MAX_FILTERS) throw invalid("筛选条件过多");
            return filters;
        } catch (JsonProcessingException ex) {
            throw invalid("筛选条件格式无效");
        }
    }

    private String validateSchema(String database) {
        return DatabaseValidation.schema(database, allowedSchemas());
    }

    private Set<String> allowedSchemas() {
        return properties.getAllowedSchemas() == null ? Set.of() : properties.getAllowedSchemas().stream()
                .filter(s -> s != null && DatabaseValidation.IDENTIFIER.matcher(s).matches())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void requireReady() {
        if (dataSource == null || jdbcTemplate == null || !properties.isEnabled()) {
            throw DatabaseValidation.error(502, "OPS_DB_UPSTREAM", "数据库能力未配置");
        }
    }

    private <T> T withConnection(Function<Connection, T> action) {
        requireReady();
        boolean acquired;
        try {
            acquired = concurrency.tryAcquire(Math.max(1, properties.getConnectionTimeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw limit("数据库请求被中断");
        }
        if (!acquired) throw limit("数据库请求过多");
        try {
            return jdbcTemplate.execute((ConnectionCallback<T>) connection -> action.apply(connection));
        } catch (DataAccessException ex) {
            throw upstream(ex);
        } finally {
            concurrency.release();
        }
    }

    private static String qualified(TableInfo info) {
        return DatabaseValidation.quote(info.database) + "." + DatabaseValidation.quote(info.table);
    }

    private static boolean validMetadataIdentifier(String value) {
        return value != null && DatabaseValidation.IDENTIFIER.matcher(value).matches();
    }

    private static void ensureSupportedColumns(TableInfo info) {
        if (info.columns.stream().anyMatch(column -> !column.supported())) {
            throw invalid("表包含不受支持的字段类型");
        }
    }

    private static String safe(String value) {
        return value == null ? null : value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException ex) {
            throw invalid("值格式无效");
        }
    }

    private static Object jsonValue(Object value, ColumnInfo column) {
        if (value == null) return null;
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (column != null) {
            try {
                JDBCType type = JDBCType.valueOf(column.jdbcType);
                if (type == JDBCType.BIGINT) return String.valueOf(value);
                if (Set.of(JDBCType.TINYINT, JDBCType.SMALLINT, JDBCType.INTEGER).contains(type)
                        && value instanceof Number number) {
                    long integer = number.longValue();
                    return integer >= -9_007_199_254_740_991L && integer <= 9_007_199_254_740_991L
                            ? value : Long.toString(integer);
                }
                if (Set.of(JDBCType.DECIMAL, JDBCType.NUMERIC).contains(type)) {
                    return new BigDecimal(String.valueOf(value)).toPlainString();
                }
            } catch (IllegalArgumentException ignored) {
                // Keep generic JSON conversion for an unknown JDBC type.
            }
        }
        if (value instanceof Number number
                && (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            long integer = number.longValue();
            return integer >= -9_007_199_254_740_991L && integer <= 9_007_199_254_740_991L
                    ? value : Long.toString(integer);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        return String.valueOf(value);
    }

    private String columnDefinition(String name, String type, boolean nullable, Object defaultValue,
                                    boolean primaryKey, boolean autoIncrement) {
        StringBuilder definition = new StringBuilder(DatabaseValidation.quote(name)).append(" ").append(type);
        if (!nullable || primaryKey) definition.append(" NOT NULL");
        if (defaultValue != null) definition.append(" DEFAULT ").append(DatabaseValidation.defaultLiteral(defaultValue, type));
        if (primaryKey) definition.append(" PRIMARY KEY");
        if (autoIncrement) definition.append(" AUTO_INCREMENT");
        return definition.toString();
    }

    private static String stringLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private void requireOneAffected(int affected) {
        if (affected != 1) {
            throw DatabaseValidation.error(HttpStatus.CONFLICT, affected > 1 ? "OPS_DB_METADATA_CHANGED" : "OPS_DB_CONFLICT", "数据已变化，请刷新后重试");
        }
    }

    private void audit(String action, TableInfo info, List<String> columns, int affected) {
        Long actorId = LoginMessageUtil.getLoginUser().map(LoginUser::getUserId).orElse(null);
        log.info("数据库审计 actorUserId={} requestId={} action={} database={} table={} columns={} affected={}",
                actorId, MDC.get("requestId"), action, info.database, info.table, String.join(",", columns), affected);
    }

    private static ServiceException invalid(String message) { return DatabaseValidation.error(HttpStatus.BAD_REQUEST, "OPS_DB_INVALID_REQUEST", message); }
    private static ServiceException notAllowed(String message) { return DatabaseValidation.error(HttpStatus.NOT_FOUND, "OPS_DB_NOT_ALLOWED", message); }
    private static ServiceException limit(String message) { return DatabaseValidation.error(429, "OPS_DB_LIMIT", message); }
    private static ServiceException ddlRejected(String message) { return DatabaseValidation.error(500, "OPS_DB_DDL_REJECTED", message); }
    private static ServiceException ddlRejected(Throwable ignored) { return ddlRejected("DDL 执行失败"); }
    private static ServiceException upstream(Throwable ignored) { return DatabaseValidation.error(502, "OPS_DB_UPSTREAM", "数据库暂不可用"); }

    private record ColumnInfo(String name, int jdbcType, String typeName, String objectType,
                              int size, int scale, boolean nullable, String defaultValue,
                              boolean defaultValuePresent, boolean autoIncrement, boolean generated) {
        ColumnDto dto() {
            return new ColumnDto(name, jdbcType, typeName, nullable, defaultValuePresent,
                    autoIncrement, generated, autoIncrement || generated || !supported(), size, scale, objectType);
        }

        boolean supported() {
            if ("JSON".equalsIgnoreCase(objectType)) return true;
            try {
                return switch (JDBCType.valueOf(jdbcType)) {
                    case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, NUMERIC,
                            BOOLEAN, BIT, DATE, TIMESTAMP, TIMESTAMP_WITH_TIMEZONE,
                            TIME, TIME_WITH_TIMEZONE, CHAR, VARCHAR, LONGVARCHAR,
                            NCHAR, NVARCHAR, LONGNVARCHAR, CLOB -> true;
                    case OTHER -> "JSON".equalsIgnoreCase(objectType);
                    default -> false;
                };
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }

    private record IndexInfo(String name, boolean unique, List<String> columns) {
        IndexDto dto() { return new IndexDto(name, unique, columns); }
    }

    private record TableInfo(String database, String table, String comment, List<ColumnInfo> columns,
                             List<IndexInfo> indexes, String primaryKey, int primaryKeyCount,
                             ColumnInfo versionColumn, boolean view, String tableType) {
        ColumnInfo column(String name) { return columns.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null); }
        String primaryKeyMode() { return primaryKeyCount == 1 ? "SINGLE" : primaryKeyCount > 1 ? "COMPOSITE" : "NONE"; }
        boolean writable() { return !view && primaryKeyCount == 1; }
    }

    private static final class IndexBuilder {
        final String name;
        final boolean unique;
        final Map<Integer, String> columns = new HashMap<>();
        IndexBuilder(String name, boolean unique) { this.name = name; this.unique = unique; }
        IndexInfo build() { return new IndexInfo(name, unique, columns.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList()); }
    }
}
