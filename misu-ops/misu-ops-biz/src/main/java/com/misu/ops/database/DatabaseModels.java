package com.misu.ops.database;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public final class DatabaseModels {
    private DatabaseModels() {
    }

    public record DatabaseCatalogDto(String name, String displayName) {
    }

    public record DatabaseTableDto(String name, String comment, Long rowCountEstimate,
                                   String primaryKeyMode) {
    }

    public record ColumnDto(String name, int jdbcType, String typeName, boolean nullable,
                            boolean defaultValuePresent, boolean autoIncrement,
                            boolean generated, boolean readOnly) {
        public ColumnDto(String name, int jdbcType, String typeName, boolean nullable,
                         boolean defaultValuePresent, boolean autoIncrement) {
            this(name, jdbcType, typeName, nullable, defaultValuePresent, autoIncrement,
                    false, autoIncrement);
        }
    }

    public record IndexDto(String name, boolean unique, List<String> columns) {
    }

    public record TableMetadataDto(String database, String table, List<ColumnDto> columns,
                                   List<IndexDto> indexes, boolean writable, String primaryKey) {
    }

    public record PageDto<T>(List<T> items, int page, int pageSize, Long total, boolean hasNext) {
    }

    public record RowDto(Map<String, Object> values, String rowVersion) {
    }

    public record FilterRequest(String column, String operator, Object value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRowRequest(Map<String, Object> values) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateRowRequest(Map<String, Object> values, String expectedRowVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateTableRequest(String name, String comment, List<ColumnRequest> columns) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddColumnRequest(String name, String type, boolean nullable,
                                   Object defaultValue, String position) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ColumnRequest(String name, String type, boolean nullable,
                                Object defaultValue, boolean primaryKey, boolean autoIncrement) {
        public ColumnRequest(String name, String type, boolean nullable,
                             Object defaultValue, boolean primaryKey) {
            this(name, type, nullable, defaultValue, primaryKey, false);
        }
    }
}
