package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * PostgreSQL only: a connection sees and writes the rows of the workspace in its {@code app.tenant_id} setting, which
 * {@code TenantDataSource} sets; not set means no rows, {@code *} means every workspace (deliberate server-wide work).
 * FORCE applies it to the tables' owner too, since the app usually owns them. A superuser or a role with BYPASSRLS
 * still skips it: {@code RowSecurityCheck} reports that at startup. H2 and MySQL have no row-level security.
 *
 * A later migration that reads or changes rows of these tables on PostgreSQL first needs
 * {@code SELECT set_config('app.tenant_id', '*', true)} (its own transaction only), or it finds no rows.
 */
public class V12__row_level_security extends BaseJavaMigration {

    static final List<String> TABLES = List.of(
            "td_spec", "td_test_plan", "td_environment", "td_collection_variables",
            "td_collection_settings", "td_test_case", "td_test_case_param", "td_request_file",
            "td_test_case_draft", "td_test_case_version", "td_test_case_environment", "td_run",
            "td_run_result", "td_global_variables", "td_audit_event");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        String visible = "(tenant_id = current_setting('app.tenant_id', true)"
                + " OR current_setting('app.tenant_id', true) = '*')";
        try (Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute("CREATE POLICY tenant_isolation ON " + table + " USING " + visible
                        + " WITH CHECK " + visible);
                statement.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                statement.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            }
        }
    }
}
