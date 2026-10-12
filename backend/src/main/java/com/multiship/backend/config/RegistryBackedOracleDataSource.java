package com.multiship.backend.config;

import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import com.multiship.backend.service.externalsystems.LoginContext;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Oracle NDS {@link DataSource} that resolves the real pool from the
 * {@link ExternalSystemRegistry} on every {@link #getConnection()}.
 *
 * <p>The on/off switch for the Oracle DTC sync is the {@code active} flag on
 * the {@code nds-default} row in {@code /settings/external-systems} — there is
 * no application property for it. Resolving per call (instead of once at boot)
 * means an admin toggling the row, or rotating its password, takes effect
 * without a restart. Pool caching + draining on config change is already
 * handled by {@code NdsOracleConnector}, so this class keeps no state.
 *
 * <p>When the row is inactive or missing, borrowing a connection throws the
 * same clear message as {@link InactiveOracleDataSource}.
 */
public final class RegistryBackedOracleDataSource implements DataSource {

    private final ExternalSystemRegistry registry;
    private final String connectionName;

    public RegistryBackedOracleDataSource(ExternalSystemRegistry registry, String connectionName) {
        this.registry = registry;
        this.connectionName = connectionName;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return resolve().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return resolve().getConnection(username, password);
    }

    private DataSource resolve() {
        if (!registry.isActive(connectionName)) {
            return new InactiveOracleDataSource(connectionName);
        }
        Object handle = registry.connect(connectionName, LoginContext.withProfile("PRODUCTION"));
        if (!(handle instanceof DataSource ds)) {
            throw new IllegalStateException(
                    "ExternalSystemRegistry returned non-DataSource handle for '"
                            + connectionName + "': " + handle.getClass().getName());
        }
        return ds;
    }

    @Override
    public PrintWriter getLogWriter() { return null; }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() { return 0; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
