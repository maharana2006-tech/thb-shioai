package com.multiship.backend.service.externalsystems;

/**
 * Anything a connector can't handle — missing config, missing secret,
 * decrypt failure, unreachable server, auth failure at first-connect,
 * malformed tenant scope. Callers map to their preferred wire format
 * (typically 503 with a {@code SERVICE_UNAVAILABLE} errorCode + the
 * plain-English cause the connector reports here).
 */
public class ExternalSystemException extends RuntimeException {

    public enum Kind {
        /** Row not in {@code external_system_connection}, or inactive. */
        CONNECTION_NOT_FOUND,
        /** {@code config_json} couldn't be parsed into the connector's shape. */
        INVALID_CONFIG,
        /** Required secret missing, or decrypt failed (rotated key etc.). */
        SECRET_UNAVAILABLE,
        /** Tenant / client-code invalid, missing, or format-rejected. */
        INVALID_CLIENT_CODE,
        /** Server unreachable / TCP refused / DNS. */
        UNREACHABLE,
        /** Server reached; auth denied (bad creds, expired token, ...). */
        AUTHENTICATION_FAILED,
        /** No {@code ExternalSystemConnector} bean matches the row's system_type. */
        NO_CONNECTOR,
        /** Anything else — connector-specific errors that don't fit above. */
        OTHER
    }

    private final Kind kind;
    private final String connectionName;

    public ExternalSystemException(Kind kind, String connectionName, String message) {
        super(message);
        this.kind = kind;
        this.connectionName = connectionName;
    }

    public ExternalSystemException(Kind kind, String connectionName, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.connectionName = connectionName;
    }

    public Kind kind() { return kind; }
    public String connectionName() { return connectionName; }
}
