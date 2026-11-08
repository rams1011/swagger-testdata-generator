package db.migration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves each case's environment names (a JSON list in td_test_case.environment_names) into
 * td_test_case_environment, then drops the column. A name with no environment in the case's collection gets an
 * empty environment of that name, so the case keeps its scope instead of becoming visible everywhere.
 * Written in Java because none of the three databases parse JSON arrays the same way in SQL.
 * MySQL runs the same code through db.migration_mysql.V3__environment_links.
 */
public class V3__environment_links extends BaseJavaMigration {

    private static final Gson GSON = new Gson();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, String> environmentIds = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT environment_id, spec_id, name FROM td_environment")) {
            while (rows.next()) {
                environmentIds.put(key(rows.getString("spec_id"), rows.getString("name")), rows.getString("environment_id"));
            }
        }
        Set<String> linked = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT test_case_id, spec_id, environment_names FROM td_test_case WHERE environment_names IS NOT NULL");
             PreparedStatement link = connection.prepareStatement(
                     "INSERT INTO td_test_case_environment (test_case_id, environment_id) VALUES (?, ?)")) {
            while (rows.next()) {
                String caseId = rows.getString("test_case_id");
                String specId = rows.getString("spec_id");
                for (String name : names(rows.getString("environment_names"))) {
                    String environmentId = environmentIds.get(key(specId, name));
                    if (environmentId == null) {
                        environmentId = createEnvironment(connection, specId, name);
                        environmentIds.put(key(specId, name), environmentId);
                    }
                    if (linked.add(caseId + "\n" + environmentId)) {
                        link.setString(1, caseId);
                        link.setString(2, environmentId);
                        link.executeUpdate();
                    }
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE td_test_case DROP COLUMN environment_names");
        }
    }

    private static List<String> names(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> names = GSON.fromJson(json, new TypeToken<List<String>>() { }.getType());
        return names == null ? List.of() : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String createEnvironment(Connection connection, String specId, String name) throws SQLException {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO td_environment (environment_id, spec_id, name, document_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, id);
            insert.setString(2, specId);
            insert.setString(3, name);
            insert.setString(4, CollectionDocuments.toJson(EnvironmentMapper.fromInputs(name, null, "NONE", List.of())));
            insert.setTimestamp(5, now);
            insert.setTimestamp(6, now);
            insert.executeUpdate();
        }
        return id;
    }

    /** Environment names match regardless of case, as EnvironmentScope does. */
    private static String key(String specId, String name) {
        return specId + "\n" + name.trim().toLowerCase(Locale.ROOT);
    }
}
