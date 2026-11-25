package com.tx.api.test.generator.studio.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Tells PostgreSQL which workspace each connection handed out works for (the {@code app.tenant_id} session setting that
 * the policies of migration V12 read). A pooled connection that already carries the workspace isn't told again, so
 * nothing else may change the setting for a session. Don't put a transaction-pooling proxy such as PgBouncer between
 * the app and the database: it shares sessions between clients, and so their workspace.
 */
public class TenantDataSource extends DelegatingDataSource {

    private final Supplier<String> tenant;
    private final Map<Connection, String> current = Collections.synchronizedMap(new WeakHashMap<>());

    public TenantDataSource(DataSource target) {
        this(target, true);
    }

    /** {@code enforce} false opens every connection to every workspace (the emergency off switch). */
    public TenantDataSource(DataSource target, boolean enforce) {
        super(target);
        this.tenant = enforce ? DatabaseTenant::current : () -> DatabaseTenant.ALL;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepared(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepared(super.getConnection(username, password));
    }

    private Connection prepared(Connection connection) throws SQLException {
        try {
            String wanted = tenant.get();
            Connection session = connection.unwrap(Connection.class);
            if (!wanted.equals(current.get(session))) {
                current.remove(session);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, false)")) {
                    statement.setString(1, wanted);
                    statement.execute();
                }
                current.put(session, wanted);
            }
            return connection;
        } catch (SQLException | RuntimeException failed) {
            // Never hand out a connection that may carry another workspace.
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The first failure is the one to report.
            }
            throw failed;
        }
    }
}
