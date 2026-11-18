package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MySQL table is created with the same charset and collation. A key column referencing another table's must
 * match its collation, so a table left on the server's default ({@code utf8mb4_0900_ai_ci}) can't reference
 * {@code td_organization} ({@code utf8mb4_bin}): MySQL refuses the foreign key. Only the integration job runs MySQL,
 * so this checks the scripts' text on every build.
 */
class MySqlMigrationTableOptionsTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\)([^;]*);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String OPTIONS = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";

    @Test
    void everyTableUsesTheSameCharsetAndCollation() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration_mysql/*.sql");
        assertTrue(scripts.length > 0, "no MySQL migrations found");
        List<String> wrong = new ArrayList<>();
        int tables = 0;
        for (Resource script : scripts) {
            String sql = script.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            Matcher table = CREATE_TABLE.matcher(sql);
            while (table.find()) {
                tables++;
                if (!table.group(3).trim().equals(OPTIONS)) {
                    wrong.add(script.getFilename() + ": " + table.group(1));
                }
            }
        }
        assertTrue(tables > 0, "no CREATE TABLE found");
        assertEquals(List.of(), wrong, "MySQL tables must end with ) " + OPTIONS + ";");
    }
}
