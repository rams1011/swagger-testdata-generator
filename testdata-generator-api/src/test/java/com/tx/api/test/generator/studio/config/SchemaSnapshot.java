package com.tx.api.test.generator.studio.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Describes the Studio tables the same way on every database: columns in order and whether they allow null,
 * the named ix_/ux_ indexes, and the foreign keys. Types are left out because each database names them
 * differently.
 */
final class SchemaSnapshot {

    private SchemaSnapshot() {
    }

    static String describe(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        StringBuilder out = new StringBuilder();
        for (String table : tables(meta, catalog, schema)) {
            out.append(lower(table)).append('\n');
            TreeMap<Integer, String> columns = new TreeMap<>();
            try (ResultSet rows = meta.getColumns(catalog, schema, table, null)) {
                while (rows.next()) {
                    columns.put(rows.getInt("ORDINAL_POSITION"), "column " + lower(rows.getString("COLUMN_NAME"))
                            + (rows.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls ? " not null" : " null"));
                }
            }
            columns.values().forEach(line -> out.append("  ").append(line).append('\n'));
            indexes(meta, catalog, schema, table).forEach(line -> out.append("  ").append(line).append('\n'));
            foreignKeys(meta, catalog, schema, table).forEach(line -> out.append("  ").append(line).append('\n'));
        }
        return out.toString();
    }

    private static List<String> tables(DatabaseMetaData meta, String catalog, String schema) throws SQLException {
        TreeMap<String, String> names = new TreeMap<>();
        try (ResultSet rows = meta.getTables(catalog, schema, null, new String[] {"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (lower(name).startsWith("td_")) {
                    names.put(lower(name), name);
                }
            }
        }
        return new ArrayList<>(names.values());
    }

    private static List<String> indexes(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        Map<String, TreeMap<Integer, String>> columns = new TreeMap<>();
        Map<String, Boolean> unique = new TreeMap<>();
        try (ResultSet rows = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rows.next()) {
                String name = lower(rows.getString("INDEX_NAME"));
                if (!name.startsWith("ix_") && !name.startsWith("ux_")) {
                    continue;
                }
                columns.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .put((int) rows.getShort("ORDINAL_POSITION"), lower(rows.getString("COLUMN_NAME")));
                unique.put(name, !rows.getBoolean("NON_UNIQUE"));
            }
        }
        List<String> lines = new ArrayList<>();
        columns.forEach((name, cols) -> lines.add((unique.get(name) ? "unique index " : "index ") + name
                + " (" + String.join(", ", cols.values()) + ")"));
        return lines;
    }

    private static List<String> foreignKeys(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        Map<String, String> lines = new TreeMap<>();
        try (ResultSet rows = meta.getImportedKeys(catalog, schema, table)) {
            while (rows.next()) {
                String name = lower(rows.getString("FK_NAME"));
                lines.putIfAbsent(name, "foreign key " + name + " (" + lower(rows.getString("FKCOLUMN_NAME")) + ") -> "
                        + lower(rows.getString("PKTABLE_NAME")) + " on delete " + rule(rows.getShort("DELETE_RULE")));
            }
        }
        return new ArrayList<>(lines.values());
    }

    private static String rule(short deleteRule) {
        return switch (deleteRule) {
            case DatabaseMetaData.importedKeyCascade -> "cascade";
            case DatabaseMetaData.importedKeySetNull -> "set null";
            case DatabaseMetaData.importedKeySetDefault -> "set default";
            // RESTRICT and NO ACTION behave the same here; databases report one or the other.
            default -> "no action";
        };
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
