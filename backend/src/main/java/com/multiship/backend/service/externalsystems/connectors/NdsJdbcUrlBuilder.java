package com.multiship.backend.service.externalsystems.connectors;

/**
 * Builds the Oracle thin-driver TNS-descriptor JDBC URL for NDS.
 * Single place to touch when the URL shape changes; unit-tested for
 * exact-string match.
 *
 * <p>Shape:
 * <pre>
 * jdbc:oracle:thin:@(DESCRIPTION=
 *   (ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST={host})(PORT={port})))
 *   (CONNECT_DATA=(SERVER={serverMode})(SERVICE_NAME={serviceName})))
 * </pre>
 * DEDICATED is the confirmed server mode for NDS; anything else is
 * a config error and the caller (connector) will surface it.
 */
public final class NdsJdbcUrlBuilder {

    private NdsJdbcUrlBuilder() {}

    /**
     * @throws IllegalArgumentException on missing / blank host / service /
     *         non-positive port. Server mode falls back to
     *         {@code DEDICATED} on null/blank (matches NDS's default).
     */
    public static String build(String host, int port, String serviceName, String serverMode) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host required");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535 (got " + port + ")");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName required");
        }
        String mode = (serverMode == null || serverMode.isBlank())
                ? "DEDICATED"
                : serverMode.trim().toUpperCase();
        return "jdbc:oracle:thin:@(DESCRIPTION="
                + "(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=" + host.trim()
                + ")(PORT=" + port + ")))"
                + "(CONNECT_DATA=(SERVER=" + mode + ")(SERVICE_NAME=" + serviceName.trim() + ")))";
    }
}
