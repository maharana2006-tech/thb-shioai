package com.multiship.backend.config;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * I1 (audit {@code [[inactive-external-system-skip]]}) — placeholder
 * {@link DataSource} the Oracle config returns when the backing
 * {@code external_system_connection} row is inactive. Satisfies the
 * Spring bean contract so the EMF + txManager + dependent
 * repositories/services can wire up, but any attempt to actually
 * borrow a connection throws a clear
 * {@code "Oracle NDS connection '{name}' is inactive"} message so
 * stray callers surface the real cause instead of a cryptic
 * NullPointerException.
 *
 * <p>Paired with the Hibernate boot flag
 * {@code hibernate.boot.allow_jdbc_metadata_access=false} so the EMF
 * doesn't probe JDBC metadata during context init — otherwise the
 * stub would short-circuit boot the way the pre-fix behaviour did.
 */
public final class InactiveOracleDataSource implements DataSource {

    private final String connectionName;

    public InactiveOracleDataSource(String connectionName) {
        this.connectionName = connectionName;
    }

    @Override
    public Connection getConnection() {
        throw new IllegalStateException(
                "Oracle NDS connection '" + connectionName + "' is inactive. "
                        + "Reactivate at /settings/external-systems to enable DTC sync.");
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
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
    public <T> T unwrap(Class<T> iface) { return null; }

    @Override
    public boolean isWrapperFor(Class<?> iface) { return false; }
}
