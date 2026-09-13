package com.misu.ops.database;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DatabaseValidation {
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile("DECIMAL\\((\\d{1,3}),(\\d{1,3})\\)");
    private static final Pattern VARCHAR = Pattern.compile("VARCHAR\\((\\d{1,5})\\)");
    private static final Set<String> RESERVED = Set.of(
            "ACCESSIBLE", "ADD", "ALL", "ALTER", "ANALYZE", "AND", "AS", "ASC", "ASENSITIVE",
            "BEFORE", "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOTH", "BY", "CALL", "CASE",
            "CHANGE", "CHAR", "CHECK", "COLUMN", "CONDITION", "CONSTRAINT", "CONTINUE", "CONVERT",
            "CREATE", "CROSS", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATABASE",
            "DATABASES", "DECIMAL", "DEFAULT", "DELETE", "DESC", "DESCRIBE", "DETERMINISTIC",
            "DISTINCT", "DROP", "EACH", "ELSE", "EXISTS", "FALSE", "FLOAT", "FOR", "FORCE",
            "FROM", "FULLTEXT", "GRANT", "GROUP", "HAVING", "HIGH_PRIORITY", "IN", "INDEX",
            "INFILE", "INNER", "INSERT", "INT", "INTEGER", "INTERVAL", "INTO", "IS", "ITERATE",
            "JOIN", "KEY", "KEYS", "LEADING", "LEAVE", "LEFT", "LIKE", "LIMIT", "LINEAR",
            "LOAD", "LOCALTIME", "LOCALTIMESTAMP", "LOCK", "LONG", "LOOP", "MATCH", "MEDIUMINT",
            "NATURAL", "NOT", "NULL", "NUMERIC", "ON", "OPTIMIZE", "OPTION", "OR", "ORDER",
            "OUTER", "PRIMARY", "PROCEDURE", "PURGE", "RANGE", "READ", "REFERENCES", "REGEXP",
            "RELEASE", "RENAME", "REPEAT", "REPLACE", "REQUIRE", "RESTRICT", "RETURN", "REVOKE",
            "RIGHT", "RLIKE", "SCHEMA", "SELECT", "SET", "SHOW", "SMALLINT", "SPATIAL", "SQL",
            "STARTING", "TABLE", "TERMINATED", "THEN", "TINYINT", "TO", "TRAILING", "TRIGGER",
            "TRUE", "UNION", "UNIQUE", "UNLOCK", "UNSIGNED", "UPDATE", "USAGE", "USE", "USING",
            "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "VALUES", "VARBINARY", "VARCHAR", "VIEW",
            "WHEN", "WHERE", "WHILE", "WITH", "WRITE", "XOR", "YEAR", "ZEROFILL");

    private DatabaseValidation() {
    }

    static String identifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()
                || RESERVED.contains(value.toUpperCase(Locale.ROOT))) {
            throw error(HttpStatus.BAD_REQUEST, "OPS_DB_INVALID_REQUEST", "无效的" + what);
        }
        return value;
    }

    static String schema(String value, Set<String> allowed) {
        identifier(value, "数据库名");
        if (!allowed.contains(value)) {
            throw error(HttpStatus.NOT_FOUND, "OPS_DB_NOT_ALLOWED", "数据库不在允许范围内");
        }
        return value;
    }

    static String quote(String value) {
        identifier(value, "标识符");
        return "`" + value + "`";
    }

    static String type(String value) {
        if (value == null || value.length() > 32 || value.contains(";") || value.contains("--")
                || value.contains("/*") || value.contains("*/")) {
            throw error(500, "OPS_DB_DDL_REJECTED", "字段类型不受支持");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (Set.of("TINYINT", "INT", "BIGINT", "TEXT", "DATE", "DATETIME", "TIMESTAMP", "JSON").contains(normalized)) {
            return normalized;
        }
        Matcher decimal = DECIMAL.matcher(normalized);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision >= 1 && precision <= 65 && scale >= 0 && scale <= 30 && scale <= precision) {
                return normalized;
            }
        }
        Matcher varchar = VARCHAR.matcher(normalized);
        if (varchar.matches()) {
            int length = Integer.parseInt(varchar.group(1));
            if (length >= 1 && length <= 4000) {
                return normalized;
            }
        }
        throw error(500, "OPS_DB_DDL_REJECTED", "字段类型不受支持");
    }

    static String comment(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 256 || value.indexOf('\0') >= 0) {
            throw error(500, "OPS_DB_DDL_REJECTED", "注释过长");
        }
        return value;
    }

    static String defaultLiteral(Object value, String type) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && "CURRENT_TIMESTAMP".equals(text)) {
            if (type.equals("TIMESTAMP") || type.equals("DATETIME")) {
                return text;
            }
            throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
        }
        if (value instanceof Boolean bool) {
            if (type.equals("TINYINT")) {
                return bool ? "1" : "0";
            }
            throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
        }
        if (value instanceof Number number && Set.of("TINYINT", "INT", "BIGINT").contains(type)) {
            try {
                long parsed = Long.parseLong(number.toString());
                return Long.toString(parsed);
            } catch (NumberFormatException ignored) {
                throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
            }
        }
        if (value instanceof Number number && type.startsWith("DECIMAL")) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.precision() > 65) {
                    throw new NumberFormatException();
                }
                return decimal.toPlainString();
            } catch (NumberFormatException ignored) {
                throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
            }
        }
        if (value instanceof String text && Set.of("VARCHAR", "TEXT", "DATE", "DATETIME", "TIMESTAMP", "JSON").stream()
                .anyMatch(type::startsWith)) {
            if (text.length() > 512 || text.indexOf('\0') >= 0 || text.contains("\n") || text.contains("\r")
                    || text.contains(";") || text.contains("--") || text.contains("/*") || text.contains("*/")) {
                throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
            }
            return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'";
        }
        throw error(500, "OPS_DB_DDL_REJECTED", "默认值不受支持");
    }

    static ServiceException error(int code, String errorCode, String message) {
        return new ServiceException(code, errorCode + ": " + message);
    }
}
